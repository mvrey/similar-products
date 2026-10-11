package com.inditex.similarproducts.domain.exception;

public class InvalidProductIdException extends IllegalArgumentException {

    public InvalidProductIdException(String message) {
        super(message);
    }
}
