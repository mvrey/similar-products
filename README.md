# Similar Products API

A Spring Boot service that returns the products similar to a given one. It is the solution to the
[backendDevTest](https://github.com/dalogax/backendDevTest) exercise.

```
GET /product/{productId}/similar
```

```json
[
  {"id": "2", "name": "Dress",  "price": 19.99, "availability": true},
  {"id": "3", "name": "Blazer", "price": 29.99, "availability": false},
  {"id": "4", "name": "Boots",  "price": 39.99, "availability": true}
]
```

The service combines two existing APIs. The first gives the ids of the similar products and the second gives the
details of one product. The service returns the details of all similar products, ordered by similarity, in one
response. It follows the contract in
[`similarProducts.yaml`](https://github.com/dalogax/backendDevTest/blob/main/similarProducts.yaml).

**Stack:** Java 25 · Spring Boot 4.1 · Spring MVC on virtual threads · Caffeine · Resilience4j · JUnit 5 · Docker

---

## Running it

The service expects the existing APIs on `http://localhost:3001`. Start them from the test repository first:

```bash
git clone https://github.com/dalogax/backendDevTest.git
cd backendDevTest
docker-compose up -d simulado influxdb grafana
```

Then start the service on port **5000** with one of these options.

### Option A: Docker (no Java needed)

```bash
docker compose up --build
```

The container has a health check on `/actuator/health`, so `docker ps` shows it as `healthy` once it is ready.

### Option B: Maven wrapper (needs JDK 25)

```bash
./mvnw spring-boot:run        # Linux / macOS / Git Bash
.\mvnw.cmd spring-boot:run    # Windows PowerShell or cmd
```

You don't need to install Maven. The wrapper downloads the right version.

### Try it

```bash
curl http://localhost:5000/product/1/similar
```

### Run the load test

Run this from the `backendDevTest` folder while the service is running:

```bash
docker-compose run --rm k6 run scripts/test.js
```

You can see the results at http://localhost:3000/d/Le2Ku9NMk/k6-performance-test.

### Run the tests

```bash
./mvnw test
```

---

## Behaviour

| Situation | Response |
|---|---|
| Product exists | `200` with its similar products, ordered by similarity |
| Product has no similar products | `200` with `[]` |
| A similar product doesn't exist, fails, or is too slow | `200`: that product is **left out** and the others are returned |
| The requested product doesn't exist | `404` |
| The product id is blank or contains control characters | `400` |
| The similar-ids service fails or is unreachable | `502` |
| The similar-ids service doesn't answer within the lookup timeout | `504` |
| Too many upstream calls are already in progress (bulkhead full) | `503` |

Successful responses carry `Cache-Control: max-age=60` (`similar-products.response-max-age`), so clients and proxies
can reuse them for a minute.

All errors, including unknown paths, unsupported methods and unexpected failures, use the standard
[RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) *problem details* format:

```json
{"title": "Product not found", "status": 404,
 "detail": "Product not found: 99", "instance": "/product/99/similar"}
```

---

## Architecture

The code uses a **hexagonal (ports and adapters)** layout with a small **DDD**-style domain. Dependencies point
inward only, and the domain and application layers have no Spring annotations: they don't know about Spring, HTTP
or JSON. All the wiring happens in `infrastructure.config`.

```
com.inditex.similarproducts
├── domain                      Business model, no framework code
│   ├── model                   Product, ProductId (immutable records, validated on construction)
│   └── exception               InvalidProductIdException
├── application                 GetSimilarProductsUseCase, ProductNotFoundException
│   └── port                    ProductCatalog: what the use case needs from the outside world, and its failures
└── infrastructure              Technical details (adapters)
    ├── rest                    Inbound: controller, response DTO, exception → HTTP mapping
    ├── client                  Outbound: HTTP adapter for the existing APIs
    ├── cache                   Caching decorator
    ├── resilience              Time limit, bulkhead and circuit breaker decorators
    └── config                  Composition root: wiring, settings, virtual-thread executor
```

The domain is deliberately small. The feature only reads and combines products, so there are no aggregates or
domain services to model; the value is in validated value objects and in keeping business rules (what a missing
product means) in the use case.

The `ProductCatalog` port is implemented as a **chain of decorators**. Each layer adds one concern and implements
the same interface. The use case doesn't know which layers are there:

```
GetSimilarProductsUseCase
        │  ProductCatalog
        ▼
PerProductCircuitBreakingCatalog  skips a product that keeps failing or timing out
        │  ProductCatalog
        ▼
TimeLimitingProductCatalog        stops waiting after the lookup timeout (the lookup itself carries on)
        │  ProductCatalog
        ▼
CachingProductCatalog             caches lookups, merges concurrent requests, refreshes popular entries
        │  ProductCatalog
        ▼
BulkheadProductCatalog            caps how many upstream calls run at the same time
        │  ProductCatalog
        ▼
SimilarIdsCircuitBreakingCatalog  fails fast while the similar-ids service keeps failing
        │  ProductCatalog
        ▼
HttpProductCatalog                calls the existing APIs over HTTP, with connect and read timeouts
        │
        ▼
existing product APIs (:3001)
```

The order matters. Each position is explained where it is wired (`ProductCatalogConfig`) and in the design
decisions below.

How this maps to SOLID:

- **Single responsibility:** the controller only translates HTTP. The use case only coordinates. Each decorator
  handles one concern.
- **Open/closed:** the time limit, caching, bulkhead and circuit breakers were added as new classes. The use case,
  the controller and the HTTP adapter didn't change.
- **Liskov substitution:** any `ProductCatalog` can replace another. The decorators keep the port's contract,
  including which exceptions it throws.
- **Interface segregation:** the port has only the two operations the use case needs.
- **Dependency inversion:** the use case depends on the `ProductCatalog` interface, not on HTTP or caching code.
  All wiring is in one place (`ProductCatalogConfig`).

---

## Design decisions

### Similar product details are fetched in parallel

The service fetches each similar product on its own **virtual thread**, then collects the results in the original
order. A request therefore takes as long as its slowest lookup, not the sum of all of them. Incoming requests also
run on virtual threads (`spring.threads.virtual.enabled=true`), so a request waiting on a slow upstream costs almost
nothing. The code stays plain blocking code, with no reactive programming.

### Missing or failing similar products are left out

The contract doesn't say what to do when one similar product can't be retrieved. I chose to **leave it out and
return the rest**. One broken product shouldn't hide the others, which is the expected behaviour for a
"you may also like" feature. A `404` is returned only when the **requested** product doesn't exist.

The port separates the two cases, in the same way for both of its operations:

- `Optional.empty()` means the product doesn't exist.
- `ProductCatalogUnavailableException` means the product couldn't be retrieved (error, timeout, invalid data, or
  circuit open).

This lets each layer treat them differently. For example, "not found" doesn't count as a circuit-breaker failure.
What a missing product means is a business decision, so it lives in the use case, not in the adapters: a missing
**requested** product becomes a `404`, while a missing or unavailable **similar** product is left out.

### Two timeouts: how long a request waits, and how long an upstream call may run

| Setting | Default | Meaning |
|---|---|---|
| `product-catalog.lookup-timeout` | `3s` | How long a **request** waits for a product before leaving it out |
| `product-api.read-timeout` | `10s` | How long a single **upstream call** may run before it counts as a failure |
| `product-api.connect-timeout` | `1s` | How long to wait when opening a connection |

When a product takes longer than the lookup timeout, the request returns without it. The time limit is a separate
decorator *above* the cache, and it never cancels the lookup it stops waiting for. So the upstream call keeps
running in the background, and when it finishes the result goes into the cache. In the mock data, product `1000`
takes 5 s. The first request leaves it out after 3 s, and every later request gets it from the cache in
milliseconds. Product `10000` takes 50 s, which is longer than the read timeout, so it is treated as broken.

So **waiting is bounded**, whatever the upstream does. A request makes two rounds of lookups, one after the other:
first the similar ids, then all the product details in parallel. Each round waits at most the lookup timeout, so the
worst case is about 6 s, when both rounds hit slow, uncached upstream calls. In practice similar ids are cached
and fast, so a request waits at most about 3 s, and a product that keeps timing out stops costing even that once
its circuit breaker opens (see below). The values are configuration, not code, so they can be tuned for each
environment.

### Caching with request coalescing

The cache stores product details and similar-ids lists, using [Caffeine](https://github.com/ben-manes/caffeine) in
async mode:

- **Errors are never cached.** Errors and timeouts are usually temporary, so the next request retries them.
- **"Not found" is cached briefly** (`1m`, `product-catalog.cache.not-found-ttl`). Without it, every request for an
  unknown product would reach the upstream, and the circuit breakers deliberately ignore 404s, so nothing else would
  stop them. The short TTL means a product created upstream appears within a minute.
- **Popular entries are refreshed ahead of expiry.** An entry read when it is older than `8m`
  (`product-catalog.cache.refresh-after`) is reloaded in the background while the cached value is still served, so
  frequently requested products never make a request wait for an expired entry. If the reload fails, the cached
  value is kept until it expires.
- **Concurrent requests for the same product share one upstream call.** The cache stores the in-flight lookup, not
  only the finished result. When 200 users ask for the same uncached product at the same time, the upstream gets
  one call, not 200.
- **Giving up doesn't cancel the shared lookup.** A request that stops waiting after the lookup timeout leaves the
  call running for the other waiting requests and for the cache.
- Found entries expire after `10m` (`product-catalog.cache.ttl`), with at most 10,000 entries per cache.

**Trade-off:** prices and availability can be up to 10 minutes old. That is acceptable for product
recommendations. It would not be acceptable for checkout, which should read live data.

### Circuit breakers, one per product

The service uses [Resilience4j](https://resilience4j.readme.io/) circuit breakers in two places:

- **One breaker for the similar-ids service.** If the service keeps failing, requests get a `502` immediately
  instead of each one waiting for a timeout.
- **One breaker per product** for product details. A product that keeps failing (product `6` in the mock data
  always returns `500`) or keeps timing out (product `10000` takes 50 s) is skipped immediately, and the other
  products are not affected.

The two kinds of breaker sit at different places in the chain, for different reasons:

- The **similar-ids breaker** sits *below the cache*, so cached similar ids are still served while it is open.
- The **per-product breakers** sit *above the time limit*, so a lookup that doesn't answer in time counts as a
  failure. Below it, a product that never answers would fail only when the upstream call finally gives up after
  the read timeout, and because concurrent requests share one upstream call, that is a single failure every
  10 s: far too few to ever open the breaker. Every request would wait the full 3 s for it, forever. Up here,
  each request that times out counts, so the breaker opens after a few of them.

**Trade-off:** a product that is slow only until it is cached (product `1000` takes 5 s once) can open its breaker
during those first seconds, and is then left out for up to `10s` (`wait-duration-in-open-state`) even though it is
already in the cache. After that, the breaker lets a test call through, the cached product answers at once, and
the breaker closes.

I didn't use a single global breaker. Once the cache is warm, most upstream traffic is for products that always
fail. A global breaker would see an almost 100 % failure rate, open, and block healthy products too.

Per-product breakers are created when first needed. Breakers that are idle for 30 minutes are removed, so memory
stays bounded. Only an unavailable catalog (an error, a timeout or invalid data) counts as a failure. A `404` is a
valid answer, and a call refused by the bulkhead (`ProductCatalogBusyException`) is our own load, not a failure of
the upstream.

| Setting (`product-catalog.circuit-breaker.*`) | Default |
|---|---|
| `failure-rate-threshold` | `50` (%) |
| `sliding-window-size` | `10` calls |
| `minimum-number-of-calls` | `5` |
| `wait-duration-in-open-state` | `10s` |
| `idle-product-breaker-expiry` | `30m` |
| `max-product-breakers` | `10000` |

Because the cache sits above the similar-ids breaker, cached data keeps being served while the upstream is down.
I tested this by stopping the mock server: cached products were still returned with `200` in about 3 ms, uncached
ones failed fast with `502`, and the similar-ids breaker opened after 5 failures.

### A bulkhead protects the existing APIs

Requests run on virtual threads, which are almost free, so nothing would stop a traffic spike from opening thousands
of upstream calls at once. A [Resilience4j](https://resilience4j.readme.io/) bulkhead caps them: at most `200` calls
run at the same time, and a call that can't get a slot within `500ms` is rejected as busy (the product is left out,
or the request gets a `503`). Cached and coalesced lookups never reach the bulkhead, so in normal traffic it is far
from full.

A call rejected because of our own load says nothing about the health of the upstream, so it must never open a
breaker. The bulkhead sits *above* the similar-ids breaker, so its rejections never reach it, and the per-product
breakers, which sit above the bulkhead, ignore them (`ProductCatalogBusyException`).

| Setting (`product-catalog.bulkhead.*`) | Default |
|---|---|
| `max-concurrent-calls` | `200` |
| `max-wait-duration` | `500ms` |

### Metrics

Actuator exposes `/actuator/health` and `/actuator/metrics`, including:

| Metric | Meaning |
|---|---|
| `cache.gets`, `cache.puts`, `cache.evictions`… (tag `cache`: `products`, `similar-ids`) | Cache hit rate and size |
| `resilience4j.circuitbreaker.*` (tag `name`: `similar-ids`) | State and failure rate of the similar-ids breaker |
| `product.catalog.product.breakers.open` | How many per-product breakers are open right now |
| `resilience4j.bulkhead.*` | Free and maximum concurrent upstream calls |

Per-product breakers are reported as a single count: one set of meters per product would create thousands of
time series.

For this exercise Actuator shares port 5000 with the API. In production it would move to a separate port that only
the monitoring system can reach (`management.server.port`), since metrics reveal details about the service.

### Quiet logs for expected failures

Upstream failures are expected and can be frequent (product `6` in the mocks fails on every call). A failed request
is logged as one `WARN` line with its root cause and no stack trace. Skipped similar products are logged at `DEBUG`,
and circuit breakers log when they open and close. Stack traces are kept for unexpected errors, which are bugs.

Product ids end up in log lines, so `ProductId` rejects control characters (with a `400`): an id containing a line
break could otherwise forge fake log entries.

### No retries

I didn't add automatic retries on purpose. Retrying a slow or overloaded upstream adds more load to it, and every
retry makes the user wait longer. With caching and circuit breakers in place, a failed product is retried
naturally on the next request.

### No database

The service doesn't own any product data. It reads from other services. All persistent state belongs to those
services, and the cache is the only local state.

---

## Load test results

These are results of the provided k6 test (`scripts/test.js`): five scenarios with 200 virtual users each, run
against the service in Docker and the provided mocks, on a development laptop (absolute numbers depend on the
machine). The final version was measured twice: right after starting the service (empty cache), and again
straight after (warm cache).

| | Parallel lookups + timeouts only | **Final**, cold start | **Final**, warm cache |
|---|---|---|---|
| Median response time | 753 ms | **4.4 ms** | **3.4 ms** |
| 95th percentile | 3.38 s | **50 ms** | **23 ms** |
| Maximum | 3.71 s | **3.04 s** | **3.01 s** |
| Throughput | 94 req/s | **289 req/s** | **331 req/s** |
| Error responses | 7 | **0** | **0** |

The maximum of about 3 s comes from the first requests for the slow products, which wait up to the lookup timeout
until the product is cached or its breaker opens. Before the per-product breakers moved above the time limit, every
request for product `10000` waited those 3 s, and the cold-start 95th percentile was 2.98 s.

k6 reports about 600 "interrupted iterations": the test script stops its first three scenarios with
`gracefulStop: '0s'`, which cuts the iterations still running at that moment. They are not failed requests.

---

## Tests

74 tests, run with `./mvnw test`:

| Test | Scope |
|---|---|
| `ProductTest` | Domain validation: invalid products and ids can't be created |
| `ProductCatalogPropertiesTest` | Settings bind from `application.properties`, and invalid or missing ones stop startup |
| `GetSimilarProductsUseCaseTest` | Ordering, skipping, parallelism (uses a mocked port) |
| `HttpProductCatalogTest` | HTTP adapter: JSON mapping and how each error status is handled (uses Spring's `MockRestServiceServer`) |
| `CachingProductCatalogTest` | Caching, expiry, brief caching of "not found", background refresh, no caching of failures, request coalescing, metrics |
| `TimeLimitingProductCatalogTest` | Gives up after the timeout without cancelling the lookup |
| `BulkheadProductCatalogTest` | Rejects calls beyond the concurrency limit, metrics |
| `PerProductCircuitBreakingCatalogTest` | Breakers open on failures and timeouts, stay per product, ignore 404s and busy rejections, recover, metrics |
| `SimilarIdsCircuitBreakingCatalogTest` | Fails fast while the similar-ids service is down, ignores 404s, metrics |
| `SimilarProductsControllerTest` | Web layer: JSON format, status codes, `Cache-Control`, problem details for every error (`@WebMvcTest`) |
| `SimilarProductsEndToEndTest` | The full application on a random port against a fake HTTP API built on the JDK's `HttpServer`, including a product that keeps timing out and the Actuator metrics |

The tests don't depend on wall-clock timing: they use latches, barriers, Awaitility and a fake cache clock, so they
are fast and don't fail randomly on a busy machine.

---

## Configuration

All settings are in `src/main/resources/application.properties`. Any of them can be overridden with an
environment variable, for example:

```bash
PRODUCT_API_BASE_URL=http://other-host:3001
PRODUCT_CATALOG_LOOKUP_TIMEOUT=2s
```

The settings are validated at startup: a missing or invalid value (say, a bulkhead of `0` calls) stops the
application with a message naming the setting, instead of failing later in some unrelated place.

---

## Possible improvements

- **Dashboards and alerts** on the metrics above (for example, alert when the similar-ids breaker opens).
- **Shared cache:** with several instances behind a load balancer, a shared cache such as Redis would avoid each
  instance warming its own cache.
- **Contract tests:** check responses automatically against `similarProducts.yaml`.
- **Rate limiting:** limit incoming requests per client, to protect this service itself.
- **CI pipeline:** build, test and publish the Docker image on every push.
