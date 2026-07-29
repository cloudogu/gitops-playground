package com.cloudogu.gitops.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
public class ClusterResourcesCopyFilter {

	private static final Pattern LEADING_SLASHES = Pattern.compile("^/+");
	private static final Pattern TRAILING_SLASHES = Pattern.compile("/+$");

	private ClusterResourcesCopyFilter() {
	}

	public static FileFilter forSubDir(String copyFromDirectory, String subDirToCopy) {
		return forSubDirs(copyFromDirectory, java.util.List.of(subDirToCopy));
	}

	public static FileFilter forSubDirs(String copyFromDirectory, Collection<String> subDirsToCopy) {
		if (subDirsToCopy == null || subDirsToCopy.isEmpty()) {
			return allowAllFilter();
		}

		File srcRoot = canonicalFile(copyFromDirectory);
		Set<String> prefixes = normalizedPrefixes(subDirsToCopy);
		Set<String> templateIncludePrefixes = Set.of("apps/argocd/argocd/templates/");

		return candidateFile -> matches(candidateFile, srcRoot, prefixes, templateIncludePrefixes);
	}

	private static File canonicalFile(String path) {
		try {
			return new File(path).getCanonicalFile();
		} catch (IOException e) {
			throw new UncheckedIOException("Failed to get canonical file for " + path, e);
		}
	}

	private static Set<String> normalizedPrefixes(Collection<String> subDirsToCopy) {
		return subDirsToCopy.stream().map(ClusterResourcesCopyFilter::normalizePrefix).collect(Collectors.toSet());
	}

	private static String normalizePrefix(String subDir) {
		String norm = subDir.replace('\\', '/');
		norm = TRAILING_SLASHES.matcher(LEADING_SLASHES.matcher(norm).replaceAll("")).replaceAll("");
		return norm + "/";
	}

	private static boolean matches(File candidateFile,
	                               File srcRoot,
	                               Set<String> prefixes,
	                               Set<String> templateIncludePrefixes) {
		String rel = relativePath(candidateFile, srcRoot);
		if (rel == null) {
			return false;
		}
		if (rel.isEmpty() || ".".equals(rel)) {
			return true;
		}

		boolean isDir = candidateFile.isDirectory();
		String relDir = rel.endsWith("/") ? rel : (rel + "/");

		if (templateIncludePrefixes.stream().anyMatch((isDir ? relDir : rel)::startsWith)) {
			return true;
		}

		if (rel.startsWith("apps/") && relDir.contains("/templates/")) {
			return false;
		}

		if (isDir) {
			return prefixes.stream()
			               .anyMatch(prefix -> relDir.equals(prefix) || relDir.startsWith(prefix) || prefix.startsWith(relDir));
		}

		return prefixes.stream().anyMatch(rel::startsWith);
	}

	private static String relativePath(File candidateFile, File srcRoot) {
		try {
			File canon = candidateFile.getCanonicalFile();
			String rel = srcRoot.toURI().relativize(canon.toURI()).toString();
			return rel.replace('\\', '/');
		} catch (IOException e) {
			log.debug("Failed to compute relative path for {} against {}", candidateFile, srcRoot, e);
			return null;
		}
	}

	private static FileFilter allowAllFilter() {
		return file -> true;
	}
}
