package com.risk.gateway.feature;

public class FeatureUnavailableException extends RuntimeException {
    public FeatureUnavailableException(String message) { super(message); }
    public FeatureUnavailableException(String message, Throwable cause) { super(message, cause); }
}
