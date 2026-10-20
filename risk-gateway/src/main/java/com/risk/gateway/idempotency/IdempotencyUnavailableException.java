package com.risk.gateway.idempotency;

/** 发生该异常必须停止转发；写入超时也可能已经生效，不得认为可以重新发送。 */
public class IdempotencyUnavailableException extends RuntimeException {
    public IdempotencyUnavailableException(String message) { super(message); }
    public IdempotencyUnavailableException(String message, Throwable cause) { super(message, cause); }
}
