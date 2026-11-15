package com.hardwarebilling.service;

/** A rule violation that should be shown to the user (not a server fault). */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }
}
