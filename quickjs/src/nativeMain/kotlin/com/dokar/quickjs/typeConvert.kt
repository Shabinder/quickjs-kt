package com.dokar.quickjs

import kotlin.reflect.KClass
import kotlin.reflect.KType

@Suppress("unchecked_cast")
@PublishedApi
internal fun <T : Any?> typeConvertOr(
    value: Any?,
    expectedType: KType,
    fallback: (value: Any) -> T
): T {
    val nonNull = value ?: return null as T
    val expectedClass = expectedType.classifier as KClass<*>
    if (expectedClass.isInstance(value)) {
        return nonNull as T
    }
    when (expectedClass) {
        Int::class -> {
            when (value) {
                is Long -> {
                    // Long -> int
                    return value.toInt() as T
                }

                is Double -> {
                    // Double -> int
                    return value.toInt() as T
                }
            }
        }

        Float::class -> {
            when (value) {
                is Double -> {
                    // Double -> float
                    return value.toFloat() as T
                }

                is Long -> {
                    // Long -> float
                    return value.toFloat() as T
                }
            }
        }

        Double::class -> {
            if (value is Long) {
                // Long -> double
                return value.toDouble() as T
            }
        }
    }
    return fallback(value)
}
