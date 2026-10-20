package com.risk.gateway.adapter;

public class AdapterException extends RuntimeException {
    public enum Outcome { NOT_DISPATCHED, UNCERTAIN, REJECTED }
    private final Outcome outcome;
    private final String reason;
    public AdapterException(Outcome outcome, String reason, Throwable cause) {
        super(reason, cause);
        this.outcome = outcome;
        this.reason = reason;
    }
    public Outcome outcome() { return outcome; }
    public String reason() { return reason; }
}
