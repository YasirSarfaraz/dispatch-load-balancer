package com.freightfox.dispatch.service.impl;

import javax.sql.DataSource;
import java.io.Closeable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only JDBC spy with zero extra dependencies: wraps a {@link DataSource} in JDK dynamic
 * proxies and counts, per SQL kind, how often Hibernate calls {@code addBatch()},
 * {@code executeBatch()}, {@code executeUpdate()} and {@code executeQuery()} on prepared
 * statements. This is what actually reaches the JDBC driver, so it proves (or disproves) batching.
 */
final class JdbcStatementRecorder {

    enum Kind { SELECT, INSERT, UPDATE, DELETE, OTHER }

    enum Call { ADD_BATCH, EXECUTE_BATCH, EXECUTE_UPDATE, EXECUTE_QUERY, EXECUTE }

    private final Map<Call, Map<Kind, AtomicInteger>> counts = new EnumMap<>(Call.class);

    JdbcStatementRecorder() {
        for (Call call : Call.values()) {
            Map<Kind, AtomicInteger> byKind = new EnumMap<>(Kind.class);
            for (Kind kind : Kind.values()) {
                byKind.put(kind, new AtomicInteger());
            }
            counts.put(call, byKind);
        }
    }

    void reset() {
        counts.values().forEach(byKind -> byKind.values().forEach(counter -> counter.set(0)));
    }

    int count(Call call, Kind kind) {
        return counts.get(call).get(kind).get();
    }

    /** Number of round trips of the given SQL kind (single executions plus batch executions). */
    int roundTrips(Kind kind) {
        return count(Call.EXECUTE_BATCH, kind) + count(Call.EXECUTE_UPDATE, kind)
                + count(Call.EXECUTE_QUERY, kind) + count(Call.EXECUTE, kind);
    }

    @Override
    public String toString() {
        return counts.toString();
    }

    DataSource wrap(DataSource target) {
        Class<?>[] interfaces = target instanceof Closeable
                ? new Class<?>[]{DataSource.class, Closeable.class}
                : new Class<?>[]{DataSource.class};
        return (DataSource) proxy(target, interfaces, (proxy, method, args) -> {
            Object result = invoke(target, method, args);
            return result instanceof Connection connection ? wrapConnection(connection) : result;
        });
    }

    private Connection wrapConnection(Connection target) {
        return (Connection) proxy(target, new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            Object result = invoke(target, method, args);
            if (result instanceof PreparedStatement statement && method.getName().equals("prepareStatement")) {
                return wrapStatement(statement, kindOf((String) args[0]));
            }
            return result;
        });
    }

    private PreparedStatement wrapStatement(PreparedStatement target, Kind kind) {
        return (PreparedStatement) proxy(target, new Class<?>[]{PreparedStatement.class}, (proxy, method, args) -> {
            Call call = callOf(method, args);
            if (call != null) {
                counts.get(call).get(kind).incrementAndGet();
            }
            return invoke(target, method, args);
        });
    }

    private static Call callOf(Method method, Object[] args) {
        boolean noArgs = args == null || args.length == 0;
        return switch (method.getName()) {
            case "addBatch" -> noArgs ? Call.ADD_BATCH : null;
            case "executeBatch", "executeLargeBatch" -> Call.EXECUTE_BATCH;
            case "executeUpdate", "executeLargeUpdate" -> noArgs ? Call.EXECUTE_UPDATE : null;
            case "executeQuery" -> noArgs ? Call.EXECUTE_QUERY : null;
            case "execute" -> noArgs ? Call.EXECUTE : null;
            default -> null;
        };
    }

    private static Kind kindOf(String sql) {
        String normalized = sql.strip().toLowerCase(Locale.ROOT);
        for (Kind kind : Kind.values()) {
            if (normalized.startsWith(kind.name().toLowerCase(Locale.ROOT))) {
                return kind;
            }
        }
        return Kind.OTHER;
    }

    private static Object proxy(Object target, Class<?>[] interfaces, InvocationHandler delegate) {
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> delegate.invoke(proxy, method, args);
        };
        return Proxy.newProxyInstance(JdbcStatementRecorder.class.getClassLoader(), interfaces, handler);
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
