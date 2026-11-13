package com.freightfox.dispatch.controller;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.Locale;

/**
 * Adds a standard {@code Server-Timing} header ({@code app;dur=<ms>}) to every JSON response of this
 * API: the time from the request reaching the application until the body is about to be written.
 * It separates server work (validation, upsert, planning) from network transfer, which dominates
 * for large plans; Postman and browser dev tools show the header directly.
 */
@RestControllerAdvice(basePackages = "com.freightfox.dispatch")
public class ServerTimingAdvice implements ResponseBodyAdvice<Object> {

    public static final String HEADER = "Server-Timing";
    private static final double NANOS_PER_MILLI = 1_000_000.0;

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        if (request instanceof ServletServerHttpRequest servletRequest
                && servletRequest.getServletRequest().getAttribute(RequestStartFilter.START_NANOS) instanceof Long start) {
            double millis = (System.nanoTime() - start) / NANOS_PER_MILLI;
            response.getHeaders().set(HEADER, String.format(Locale.ROOT, "app;dur=%.1f", millis));
        }
        return body;
    }
}
