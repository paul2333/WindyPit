package com.windpvp.windspigot.scheduler;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Structural deep-immutability check for cross-owner messages (W1-REGION-REAL / Prompt 70).
 *
 * <p>Java has no language-level deep immutability, so the owner boundary enforces a conservative
 * shape: the message class must be {@code final}, every instance field must be {@code final}, and
 * every field type must be a primitive, {@link String} or enum. This rejects live NMS references,
 * collections and mutable value objects at send time instead of relying on a comment.</p>
 */
final class OwnerMessageValidator {

    private OwnerMessageValidator() {
    }

    static void requireImmutable(Object payload) {
        if (payload == null) {
            throw new IllegalArgumentException("cross-owner payload is null");
        }
        if (!(payload instanceof ImmutableOwnerMessage)) {
            throw new IllegalArgumentException("cross-owner payload must implement ImmutableOwnerMessage: "
                    + payload.getClass().getName());
        }
        final Class<?> type = payload.getClass();
        if (!Modifier.isFinal(type.getModifiers())) {
            throw new IllegalArgumentException("cross-owner message class must be final: " + type.getName());
        }
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            if (!Modifier.isFinal(field.getModifiers())) {
                throw new IllegalArgumentException("cross-owner message field must be final: "
                        + type.getName() + "." + field.getName());
            }
            if (!isDeeplyImmutableType(field.getType())) {
                throw new IllegalArgumentException("cross-owner message field type is not deeply immutable: "
                        + type.getName() + "." + field.getName() + " (" + field.getType().getName() + ")");
            }
        }
    }

    private static boolean isDeeplyImmutableType(Class<?> type) {
        return type.isPrimitive() || type == String.class || type.isEnum();
    }
}
