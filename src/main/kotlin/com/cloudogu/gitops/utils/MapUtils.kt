package com.cloudogu.gitops.utils

object MapUtils {

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun <K, V> deepMerge(src: Map<out K, out V>, target: MutableMap<K, V>): MutableMap<K, V> {
        src.forEach { (key, value) ->
            val oldVal = target[key]
            if (oldVal is Map<*, *> && value is Map<*, *>) {
                target[key] = deepMerge(value as Map<out K, out V>, oldVal as MutableMap<K, V>) as V
            } else {
                target[key] = value
            }
        }
        return target
    }

    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun <K, V> deepMergeDefaults(src: Map<out K, out V>, target: MutableMap<K, V>): MutableMap<K, V> {
        src.forEach { (key, value) ->
            if (value == null && target.containsKey(key)) {
                return@forEach
            }
            val oldVal = target[key]
            if (oldVal is Map<*, *> && value is Map<*, *>) {
                target[key] = deepMergeDefaults(value as Map<out K, out V>, oldVal as MutableMap<K, V>) as V
            } else {
                target[key] = value
            }
        }
        return target
    }
}
