package com.cloudogu.gitops.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public final class Version {

	private static final String VERSION_RESOURCE = "/com/cloudogu/gitops/cli/version-name.txt";

	public static final String NAME = loadName();

	private Version() {
	}

	private static String loadName() {
		try (InputStream input = Version.class.getResourceAsStream(VERSION_RESOURCE)) {
			if (input == null) {
				throw new IllegalStateException("Version resource not found: " + VERSION_RESOURCE);
			}
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to read version resource: " + VERSION_RESOURCE, e);
		}
	}
}
