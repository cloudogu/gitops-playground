package com.cloudogu.gitops.utils;

import lombok.RequiredArgsConstructor;

public class DockerImageParser {

private static final int MIN_SEGMENTS_WITH_REGISTRY = 2;
private static final int REPOSITORY_SEGMENT_COUNT = 2;

@lombok.Getter
@RequiredArgsConstructor
public static class Image {
	private final String registry;
	private final String repository;
	private final String tag;

	public String getRegistryAndRepositoryAsString() {
	if (registry == null || registry.isEmpty()) {
		return repository;
	}
	return registry + "/" + repository;
	}

	@Override
	public String toString() {
	return getRegistryAndRepositoryAsString() + ":" + tag;
	}
}

public static Image parse(String image) {
	int lastSlash = image.lastIndexOf('/');
	int lastColon = image.lastIndexOf(':');
	if (lastColon == -1 || lastColon < lastSlash) {
	throw new IllegalArgumentException(
		"Cannot set image '"
			+ image
			+ "' due to missing tag. Must be the format '$repository:$tag'");
	}

	ImageAndTag tuple = splitTag(image);
	String imageWithoutTag = tuple.imageWithoutTag();
	String tag = tuple.tag();

	String[] parts = imageWithoutTag.split("/");
	String repository;
	String registry;

	if (parts.length >= MIN_SEGMENTS_WITH_REGISTRY) {
	repository = parts[parts.length - REPOSITORY_SEGMENT_COUNT] + "/" + parts[parts.length - 1];
	StringBuilder registryBuilder = new StringBuilder();
	for (int i = 0; i < parts.length - REPOSITORY_SEGMENT_COUNT; i++) {
		if (i > 0) {
		registryBuilder.append("/");
		}
		registryBuilder.append(parts[i]);
	}
	registry = registryBuilder.toString();
	} else {
	repository = imageWithoutTag;
	registry = "";
	}

	return new Image(registry, repository, tag);
}

private static ImageAndTag splitTag(String image) {
	int lastColon = image.lastIndexOf(':');
	String imageWithoutTag = image.substring(0, lastColon);
	String tag = image.substring(lastColon + 1);
	return new ImageAndTag(imageWithoutTag, tag);
}

private record ImageAndTag(String imageWithoutTag, String tag) {}
}
