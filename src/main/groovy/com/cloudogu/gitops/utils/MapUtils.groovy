package com.cloudogu.gitops.utils

class MapUtils {

	static Map deepMerge(Map src, Map target) {
		src.each { key, value ->
			if (value == null) {
				target[key] = null
				return
			}
			def oldVal = target.containsKey(key) ? target[key] : null
			if (oldVal instanceof Map && value instanceof Map) {
				target[key] = deepMerge((Map) value, (Map) oldVal)
			} else {
				target[key] = value
			}
		}
		return target
	}

}