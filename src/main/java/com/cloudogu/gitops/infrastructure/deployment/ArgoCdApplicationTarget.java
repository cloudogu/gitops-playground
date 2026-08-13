package com.cloudogu.gitops.infrastructure.deployment;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class ArgoCdApplicationTarget {

	private final String applicationName;
	private final String namespace;
	private final String project;
	private final boolean createDestinationNamespace;
}
