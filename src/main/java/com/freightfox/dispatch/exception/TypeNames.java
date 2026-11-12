package com.freightfox.dispatch.exception;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Set;

/** Client-friendly (JSON) names for Java types, used in "must be of type ..." error messages. */
public final class TypeNames {

    public static final String INTEGER = "integer";
    public static final String NUMBER = "number";
    public static final String BOOLEAN = "boolean";
    public static final String STRING = "string";
    public static final String ARRAY = "array";
    public static final String OBJECT = "object";
    public static final String VALUE = "value";

    private static final Set<Class<?>> INTEGER_TYPES = Set.of(Integer.class, int.class, Long.class, long.class,
            Short.class, short.class, Byte.class, byte.class, BigInteger.class);
    private static final Set<Class<?>> DECIMAL_TYPES = Set.of(Double.class, double.class, Float.class, float.class,
            BigDecimal.class);

    private TypeNames() {
    }

    /** @return e.g. "integer" for Integer, "number" for Double, "array" for a List; "value" for null */
    public static String of(Class<?> type) {
        if (type == null) {
            return VALUE;
        }
        if (INTEGER_TYPES.contains(type)) {
            return INTEGER;
        }
        if (DECIMAL_TYPES.contains(type)) {
            return NUMBER;
        }
        if (type == Boolean.class || type == boolean.class) {
            return BOOLEAN;
        }
        if (CharSequence.class.isAssignableFrom(type) || type == Character.class || type == char.class) {
            return STRING;
        }
        if (type.isArray() || Collection.class.isAssignableFrom(type)) {
            return ARRAY;
        }
        if (type.isEnum()) {
            return STRING;
        }
        return OBJECT;
    }
}
