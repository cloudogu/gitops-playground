package com.cloudogu.gitops.utils;

public record Tuple<F, S>(F first, S second) {

public F getFirst() {
	return first;
}

public S getSecond() {
	return second;
}

public F getV1() {
	return first;
}

public S getV2() {
	return second;
}
}
