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
| The product id is blank | `400` |
| The similar-ids service fails or is unreachable | `502` |

All errors, including unknown paths, unsupported methods and unexpected failures, use the standard
[RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) *problem details* format:

```json
{"title": "Product not found", "status": 404,
 "detail": "Product not found: 99", "instance": "/product/99/similar"}
```

---

## Architecture

The code uses a **hexagonal (ports and adapters)** layout with a small **DDD**-style domain. Dependencies point
inward only: the domain doesn't know about Spring, HTTP or JSON.

```
com.inditex.similarproducts
├── domain                      Business model, no framework code
│   ├── model                   Product, ProductId (immutable records, validated on construction)
│   ├── port                    ProductCatalog: what the domain needs from the outside world
│   └── exception               ProductNotFoundException, ProductCatalogUnavailableException
├── application                 GetSimilarProductsUseCase: the use case
└── infrastructure              Technical details
    ├── rest                    Controller, response DTO, exception → HTTP mapping
    ├── client                  HTTP adapter for the existing APIs, plus the wiring of the catalog
    ├── cache                   Caching decorator
    ├── resilience              Time limit, bulkhead and circuit breaker decorators
    └── config                  Virtual-thread executor
```

The `ProductCatalog` port is implemented as a **chain of decorators**. Each layer adds one concern and implements
the same interface. The use case doesn't know which layers are there:

```
GetSimilarProductsUseCase
        │  ProductCatalog
        ▼
TimeLimitingProductCatalog     stops waiting after the lookup timeout (the lookup itself carries on)
        │  ProductCatalog
        ▼
CachingProductCatalog          caches successful lookups, merges concurrent requests
        │  ProductCatalog
        ▼
BulkheadProductCatalog         caps how many upstream calls run at the same time
        │  ProductCatalog
        ▼
CircuitBreakingProductCatalog  stops calling upstreams that keep failing
        │  ProductCatalog
        ▼
HttpProductCatalog             calls the existing APIs over HTTP, with connect and read timeouts
        │
        ▼
existing product APIs (:3001)
```

How this maps to SOLID:

- **Single responsibility:** the controller only translates HTTP. The use case only coordinates. Each decorator
  handles one concern.
- **Open/closed:** the time limit, caching, bulkhead and circuit breaking were added as new classes. The use case, the controller and the
  HTTP adapter didn't change.
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

So **no request waits more than about 3 s**, whatever the upstream does. The values are configuration, not code,
so they can be tuned for each environment.

### Caching with request coalescing

The cache stores product details and similar-ids lists, using [Caffeine](https://github.com/ben-manes/caffeine) in
async mode:

- **Only successful results are cached.** Errors and timeouts are usually temporary, so the next request retries
  them. Missing products aren't cached either.
- **Concurrent requests for the same product share one upstream call.** The cache stores the in-flight lookup, not
  only the finished result. When 200 users ask for the same uncached product at the same time, the upstream gets
  one call, not 200.
- **Giving up doesn't cancel the shared lookup.** A request that stops waiting after the lookup timeout leaves the
  call running for the other waiting requests and for the cache.
- Entries expire after `10m` (`product-catalog.cache.ttl`), with at most 10,000 entries per cache.

**Trade-off:** prices and availability can be up to 10 minutes old. That is acceptable for product
recommendations. It would not be acceptable for checkout, which should read live data.

### Circuit breakers, one per product

The service uses [Resilience4j](https://resilience4j.readme.io/) circuit breakers in two places:

- **One breaker for the similar-ids service.** If the service keeps failing, requests get a `502` immediately
  instead of each one waiting for a timeout.
- **One breaker per product** for product details. A product that keeps failing (product `6` in the mock data
  always returns `500`) is skipped immediately, and the other products are not affected.

I didn't use a single global breaker. Once the cache is warm, most upstream traffic is for products that always
fail. A global breaker would see an almost 100 % failure rate, open, and block healthy products too.

Per-product breakers are created when first needed. Breakers that are idle for 30 minutes are removed, so memory
stays bounded. Only `ProductCatalogUnavailableException` counts as a failure. A `404` is a valid answer.

| Setting (`product-catalog.circuit-breaker.*`) | Default |
|---|---|
| `failure-rate-threshold` | `50` (%) |
| `sliding-window-size` | `10` calls |
| `minimum-number-of-calls` | `5` |
| `wait-duration-in-open-state` | `10s` |

The cache wraps the breakers, so cached products are still served while a breaker is open. I tested this by
stopping the mock server. Cached products were still returned with `200` in about 3 ms. Uncached products failed
fast with `502`, and the similar-ids breaker opened after 5 failures.

### A bulkhead protects the existing APIs

Requests run on virtual threads, which are almost free, so nothing would stop a traffic spike from opening thousands
of upstream calls at once. A [Resilience4j](https://resilience4j.readme.io/) bulkhead caps them: at most `200` calls
run at the same time, and a call that can't get a slot within `500ms` is treated as unavailable (the product is
left out, or the request gets a `502`). Cached and coalesced lookups never reach the bulkhead, so in normal traffic
it is far from full.

The bulkhead sits *above* the circuit breakers. A call rejected because of our own load says nothing about the
health of the upstream, so it must not count towards opening a breaker.

| Setting (`product-catalog.bulkhead.*`) | Default |
|---|---|
| `max-concurrent-calls` | `200` |
| `max-wait-duration` | `500ms` |

### Quiet logs for expected failures

Upstream failures are expected and can be frequent (product `6` in the mocks fails on every call). A failed request
is logged as one `WARN` line with its root cause and no stack trace. Skipped similar products are logged at `DEBUG`,
and circuit breakers log when they open and close. Stack traces are kept for unexpected errors, which are bugs.

### No retries

I didn't add automatic retries on purpose. Retrying a slow or overloaded upstream adds more load to it, and every
retry makes the user wait longer. With caching and circuit breakers in place, a failed product is retried
naturally on the next request.

### No database

The service doesn't own any product data. It reads from other services. All persistent state belongs to those
services, and the cache is the only local state.

---

## Load test results

These are results of the provided k6 test (`scripts/test.js`): five scenarios with 200 virtual users each. They
were measured on a development laptop against the provided mocks, so absolute numbers depend on the machine.

| | Parallel lookups + timeouts | **Final** (+ cache, coalescing, circuit breakers) |
|---|---|---|
| Median response time | 753 ms | **~4–6 ms** |
| 95th percentile | 3.38 s | **~65 ms** |
| Maximum | 3.71 s | **3.03 s** |
| Throughput | 94 req/s | **274 req/s** |
| `502` responses | 7 | **0** |

The maximum of about 3 s comes from the first requests in the slow scenarios, which wait up to the lookup timeout
before the cache is warm. The results are the same with the service running in Docker.

---

## Tests

55 tests, run with `./mvnw test`:

| Test | Scope |
|---|---|
| `ProductTest` | Domain validation: invalid products can't be created |
| `GetSimilarProductsUseCaseTest` | Ordering, skipping, parallelism (uses a mocked port) |
| `HttpProductCatalogTest` | HTTP adapter: JSON mapping and how each error status is handled (uses Spring's `MockRestServiceServer`) |
| `CachingProductCatalogTest` | Caching, expiry, no caching of failures or missing products, request coalescing |
| `TimeLimitingProductCatalogTest` | Gives up after the timeout without cancelling the lookup |
| `BulkheadProductCatalogTest` | Rejects calls beyond the concurrency limit |
| `CircuitBreakingProductCatalogTest` | Breakers open, stay per product, ignore 404s, and recover |
| `SimilarProductsControllerTest` | Web layer: JSON format, status codes and problem details for every error (`@WebMvcTest`) |
| `SimilarProductsEndToEndTest` | The full application on a random port against a fake HTTP API built on the JDK's `HttpServer` |

---

## Configuration

All settings are in `src/main/resources/application.properties`. Any of them can be overridden with an
environment variable, for example:

```bash
PRODUCT_API_BASE_URL=http://other-host:3001
PRODUCT_CATALOG_LOOKUP_TIMEOUT=2s
```

---

## Possible improvements

- **Metrics:** publish cache hit rate and circuit breaker state through Micrometer and Actuator, and add dashboards.
- **Refresh-ahead:** reload popular entries in the background before they expire (Caffeine's
  `refreshAfterWrite`), so users never wait for an expired entry.
- **Shared cache:** with several instances behind a load balancer, a shared cache such as Redis would avoid each
  instance warming its own cache.
- **Contract tests:** check responses automatically against `similarProducts.yaml`.
- **Rate limiting:** limit incoming requests per client, to protect this service itself.
- **CI pipeline:** build, test and publish the Docker image on every push.
