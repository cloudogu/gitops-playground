package com.cloudogu.gitops.utils

import java.util.Map.Entry

/**
 * Flattens helm values.yaml into parameters using dot notation.
 * The dot notation then can be used when referencing helm values using {@code --set}
 *
 * @example
 * <pre>
 *  # values.yaml
 *  foo:
 *      bar:
 *         baz: Hello
 *      stuff: foo
 *  # converted to:
 *  - name: "foo.bar.baz"
 *    value: "Hello"
 *  - name: "foo.stuff"
 *    value: "foo"
 * </pre>
 */
class HelmValuesConverter {
    List flattenValues(Map values) {
        return flattenValuesImpl(values, "")
    }


    private List flattenValuesImpl(Object value, String prefix) {
        if (value instanceof Map) {
            return flattenValuesImpl(value as Map, prefix)
        } else if (value instanceof List) {
            return flattenValuesImpl(value as List, prefix)
        } else {
            return [
                    // We need to escape the dollar symbol with a dollar symbol to prevent them being substituted
                    // refs https://github.com/argoproj/argo-cd/pull/7961
                    [name: prefix, value: value.toString().replace('$', '$$')]
            ]
        }
    }

    private List flattenValuesImpl(Map values, String prefix) {
        def ret = []
        for (value in values.entrySet()) {
            String newPrefix = prefix.empty ? value.key : "$prefix.${value.key}"
            ret.addAll(flattenValuesImpl(value.value, newPrefix))
        }

        return ret
    }

    private List flattenValuesImpl(List values, String prefix) {
        def ret = []

        values.eachWithIndex { Object entry, int idx ->
            ret.addAll(flattenValuesImpl(entry, "$prefix[$idx]"))
        }

        return ret
    }
}
