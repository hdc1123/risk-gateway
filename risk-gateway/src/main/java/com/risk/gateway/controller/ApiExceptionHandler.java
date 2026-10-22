package com.risk.gateway.controller;

import com.risk.gateway.Models.ApiResponse;
import com.risk.gateway.common.BusinessException;
import com.risk.gateway.config.ApiSecurityFilter;
import com.risk.gateway.idempotency.IdempotencyUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.concurrent.RejectedExecutionException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    @ExceptionHandler({MethodArgumentNotValidException.class, MissingRequestHeaderException.class,
        HttpMessageNotReadableException.class, IllegalArgumentException.class, SecurityException.class})
    public ResponseEntity<?> invalid(Exception e, HttpServletRequest request) {
        return reply(400, 1001, "请求参数不合法", request);
    }
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<?> business(BusinessException e, HttpServletRequest request) {
        return reply(e.code() == 1404 ? 404 : e.code() == 1403 ? 403 : e.code() == 1400 ? 400 : 409, e.code(), e.getMessage(), request);
    }
    @ExceptionHandler({IdempotencyUnavailableException.class, RejectedExecutionException.class})
    public ResponseEntity<?> unavailable(Exception e, HttpServletRequest request) {
        log.error("基础设施不可用 traceId={}", request.getAttribute(ApiSecurityFilter.TRACE), e);
        return reply(503, 1503, "服务暂不可用，请勿更换订单标识重投", request);
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> internal(Exception e, HttpServletRequest request) {
        log.error("接口异常 traceId={}", request.getAttribute(ApiSecurityFilter.TRACE), e);
        return reply(500, 1500, "系统内部异常", request);
    }
    private ResponseEntity<?> reply(int http, int code, String message, HttpServletRequest request) {
        return ResponseEntity.status(http).body(ApiResponse.of(code, message,
            (String) request.getAttribute(ApiSecurityFilter.TRACE), null));
    }
}
