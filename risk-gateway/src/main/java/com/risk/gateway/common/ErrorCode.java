package com.risk.gateway.common;

/** 1001、1002 来自需求，其余为项目内部约定，正式联调时确认。 */
public enum ErrorCode {
    SUCCESS(0, "success"),
    INVALID_ARGUMENT(1001, "请求参数不合法"),
    DUPLICATE_REQUEST(1002, "重复请求"),
    INTERNAL_ERROR(1500, "系统内部异常");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
}
