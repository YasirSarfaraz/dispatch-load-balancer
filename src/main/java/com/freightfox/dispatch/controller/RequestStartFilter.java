package com.freightfox.dispatch.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Records when each request reached the application, so {@link ServerTimingAdvice} can report the
 * server-side processing time. Runs first, before any other filter.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestStartFilter extends OncePerRequestFilter {

    /** Request attribute holding the {@link System#nanoTime()} at which the request arrived. */
    public static final String START_NANOS = RequestStartFilter.class.getName() + ".startNanos";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        request.setAttribute(START_NANOS, System.nanoTime());
        chain.doFilter(request, response);
    }
}
