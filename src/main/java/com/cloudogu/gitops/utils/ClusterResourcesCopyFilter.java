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
                s -> {
                  String norm = s.replace('\\', '/');
                  norm = norm.replaceAll("^/+", "").replaceAll("/+$", "");
                  return norm + "/";
                })
            .collect(Collectors.toSet());

    Set<String> templateIncludePrefixes = Set.of("apps/argocd/argocd/templates/");

    return f -> {
      File canon;
      try {
        canon = f.getCanonicalFile();
      } catch (IOException e) {
        return false;
      }
      String rel = srcRoot.toURI().relativize(canon.toURI()).toString();
      rel = rel.replace('\\', '/');

      if (rel.isEmpty() || ".".equals(rel)) {
        return true;
      }

      boolean isDir = f.isDirectory();
      String relDir = rel.endsWith("/") ? rel : rel + "/";

      final String finalRel = rel;
      if (templateIncludePrefixes.stream()
          .anyMatch(p -> (isDir ? relDir : finalRel).startsWith(p))) {
        return true;
      }

      if (rel.startsWith("apps/") && relDir.contains("/templates/")) {
        return false;
      }

      if (isDir) {
        return prefixes.stream()
            .anyMatch(p -> relDir.equals(p) || relDir.startsWith(p) || p.startsWith(relDir));
      }

      return prefixes.stream().anyMatch(p -> finalRel.startsWith(p));
    };
  }

  private static FileFilter allowAllFilter() {
    return f -> true;
  }
}
