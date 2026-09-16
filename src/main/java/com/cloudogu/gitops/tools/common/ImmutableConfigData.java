package com.cloudogu.gitops.tools.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Creates defensive, immutable copies of configuration data while retaining insertion order and
 * allowing {@code null} values.
 *
 * <p>{@link Map#copyOf(Map)} and {@link List#copyOf(Collection)} are intentionally not used here:
 * freely configurable Helm values may contain {@code null} values, which both factory methods
 * reject.
 */
public final class ImmutableConfigData {

	private ImmutableConfigData() {
	}

	public static <K, V> Map<K, V> copyMap(Map<? extends K, ? extends V> source) {
		if (source == null || source.isEmpty()) {
			return Collections.emptyMap();
		}

		Map<K, V> copy = new LinkedHashMap<>();
		for (Map.Entry<? extends K, ? extends V> entry : source.entrySet()) {
			copy.put(entry.getKey(), copyValue(entry.getValue()));
		}
		return Collections.unmodifiableMap(copy);
	}

	public static <T> List<T> copyList(Collection<? extends T> source) {
		if (source == null || source.isEmpty()) {
			return Collections.emptyList();
		}

		List<T> copy = new ArrayList<>(source.size());
		for (T value : source) {
			copy.add(copyValue(value));
		}
		return Collections.unmodifiableList(copy);
	}

	@SuppressWarnings("unchecked")
	private static <T> T copyValue(T value) {
		if (value instanceof Map<?, ?> map) {
			return (T) copyMap(map);
		}
		if (value instanceof List<?> list) {
			return (T) copyList(list);
		}
		if (value instanceof Set<?> set) {
			Set<Object> copy = new LinkedHashSet<>();
			for (Object element : set) {
				copy.add(copyValue(element));
			}
			return (T) Collections.unmodifiableSet(copy);
		}
		if (value instanceof Collection<?> collection) {
			List<Object> copy = new ArrayList<>(collection.size());
			for (Object element : collection) {
				copy.add(copyValue(element));
			}
			return (T) Collections.unmodifiableCollection(copy);
		}
		return value;
	}
}
