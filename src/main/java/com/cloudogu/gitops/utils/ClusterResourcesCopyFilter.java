package com.cloudogu.gitops.utils;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

public class ClusterResourcesCopyFilter {

  public static FileFilter forSubDir(String copyFromDirectory, String subDirToCopy) {
    return forSubDirs(copyFromDirectory, java.util.List.of(subDirToCopy));
  }

  public static FileFilter forSubDirs(String copyFromDirectory, Collection<String> subDirsToCopy) {
    if (subDirsToCopy == null || subDirsToCopy.isEmpty()) {
      return allowAllFilter();
    }

    final File srcRoot;
    try {
      srcRoot = new File(copyFromDirectory).getCanonicalFile();
    } catch (IOException e) {
      throw new RuntimeException("Failed to get canonical file for " + copyFromDirectory, e);
    }

    Set<String> prefixes =
        subDirsToCopy.stream()
            .map(
                subDir -> {
                  String norm = subDir.replace('\\', '/');
                  norm = norm.replaceAll("^/+", "").replaceAll("/+$", "");
                  return norm + "/";
                })
            .collect(Collectors.toSet());

    Set<String> templateIncludePrefixes = Set.of("apps/argocd/argocd/templates/");

    return candidateFile -> {
      File canon;
      try {
        canon = candidateFile.getCanonicalFile();
      } catch (IOException e) {
        return false;
      }
      String rel = srcRoot.toURI().relativize(canon.toURI()).toString();
      rel = rel.replace('\\', '/');

      if (rel.isEmpty() || ".".equals(rel)) {
        return true;
      }

      boolean isDir = candidateFile.isDirectory();
      String relDir = rel.endsWith("/") ? rel : rel + "/";

      final String finalRel = rel;
      if (templateIncludePrefixes.stream()
          .anyMatch(prefix -> (isDir ? relDir : finalRel).startsWith(prefix))) {
        return true;
      }

      if (rel.startsWith("apps/") && relDir.contains("/templates/")) {
        return false;
      }

      if (isDir) {
        return prefixes.stream()
            .anyMatch(
                prefix ->
                    relDir.equals(prefix)
                        || relDir.startsWith(prefix)
                        || prefix.startsWith(relDir));
      }

      return prefixes.stream().anyMatch(prefix -> finalRel.startsWith(prefix));
    };
  }

  private static FileFilter allowAllFilter() {
    return file -> true;
  }
}
