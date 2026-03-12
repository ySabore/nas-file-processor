package com.example.nasprocessor.exception;

public class ApiCallException extends RuntimeException {
    public ApiCallException(String message, Throwable cause) {
        super(message, cause);
    }

    public ApiCallException(String message) {
        super(message);
    }
}
