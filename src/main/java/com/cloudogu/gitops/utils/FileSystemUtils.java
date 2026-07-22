package com.cloudogu.gitops.utils;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import jakarta.inject.Singleton;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;

@Singleton
@Slf4j
public class FileSystemUtils {

  private static final TypeReference<Map<String, Object>> YAML_MAP_TYPE = new TypeReference<>() {};
  private static final String TEMP_FILE_PREFIX = "gitops-playground-";

  private static final ObjectMapper yamlMapper =
      new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER));

  /**
   * Replaces text in files. If you want to change a YAML field, better use {@link #readYaml(Path)}
   * and {@link #writeYaml(Map, File)}.
   */
  public File replaceFileContent(String folder, String fileToChange, String from, String to) {
    File file = new File(folder, fileToChange);

    try {
      String newConfig = Files.readString(file.toPath()).replace(from, to);

      Files.writeString(file.toPath(), newConfig);

      return file;
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to replace file content: " + file, exception);
    }
  }

  public String replaceFileContent(String fileToChange, String from, String to) {
    Path file = Path.of(fileToChange);

    try {
      String newConfig = Files.readString(file).replaceAll(from, to);

      Files.writeString(file, newConfig);

      return newConfig;
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to replace file content: " + file, exception);
    }
  }

  public String getSubstringOfFile(String fileLocation, CharSequence pattern, int from, int to) {
    String line = findLastMatchingLine(fileLocation, pattern);
    return line != null ? line.substring(from, to) : "";
  }

  public String getSubstringOfFile(String fileLocation, CharSequence pattern, int from) {
    String line = findLastMatchingLine(fileLocation, pattern);
    return line != null ? line.substring(from) : "";
  }

  public String getLineFromFile(String fileLocation, CharSequence pattern) {
    String line = findLastMatchingLine(fileLocation, pattern);
    return line != null ? line : "";
  }

  /**
   * Scans the given file and returns the last line containing {@code pattern}, or {@code null} if
   * no line matches.
   */
  private static String findLastMatchingLine(String fileLocation, CharSequence pattern) {
    Path file = Path.of(fileLocation);
    String found = null;

    try {
      for (String line : Files.readAllLines(file)) {
        if (line.contains(pattern)) {
          found = line;
        }
      }

      return found;
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to read file: " + fileLocation, exception);
    }
  }

  public List<String> getAllLinesFromFile(String fileLocation, CharSequence pattern) {
    Path file = Path.of(fileLocation);
    List<String> foundLines = new ArrayList<>();

    try {
      for (String line : Files.readAllLines(file)) {
        if (line.contains(pattern)) {
          foundLines.add(line);
        }
      }

      return foundLines;
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to read file: " + fileLocation, exception);
    }
  }

  public static void deleteFile(String path) {
    try {
      Files.deleteIfExists(Path.of(path));
    } catch (IOException exception) {
      log.warn("Failed to delete file {}", path, exception);
    }
  }

  public static void deleteDir(String path) {
    try {
      FileUtils.deleteDirectory(new File(path));
    } catch (IOException exception) {
      log.warn("Failed to delete directory {}", path, exception);
    }
  }

  public String goBackToDir(String filePath, String directory) {
    int directoryIndex = filePath.indexOf(directory);

    if (directoryIndex < 0) {
      throw new IllegalArgumentException(
          "Directory '%s' is not part of path '%s'".formatted(directory, filePath));
    }

    return filePath.substring(0, directoryIndex + directory.length());
  }

  public String getRootDir() {
    return System.getProperty("user.dir");
  }

  public List<File> getAllFilesFromDirectoryWithEnding(String directory, String ending) {
    Path root = Path.of(directory);

    if (Files.notExists(root)) {
      return Collections.emptyList();
    }

    List<File> foundFiles = new ArrayList<>();

    try {
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
              if (file.getFileName().toString().endsWith(ending)) {
                foundFiles.add(file.toFile());
              }

              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception)
                throws IOException {
              if (exception instanceof NoSuchFileException) {
                return FileVisitResult.CONTINUE;
              }

              throw exception;
            }
          });

      return foundFiles;
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to walk directory: " + directory, exception);
    }
  }

  public void listDirectories(String parentDir) {
    List<File> files = getAllFilesFromDirectoryWithEnding(parentDir, "");

    for (File file : files) {
      log.debug("{}", file.getPath());
    }
  }

  /**
   * Compatibility overload for callers that still use {@link File}.
   *
   * @param directory root directory; {@code null} is ignored
   */
  public static void makeWritable(File directory) {
    if (directory != null) {
      makeWritable(directory.toPath());
    }
  }

  /**
   * Makes the given root path and all contained files and directories writable.
   *
   * <p>Git and JGit may create and remove temporary lock files while a repository is being
   * traversed. Paths that disappear during traversal are therefore skipped. Other I/O failures
   * abort the operation.
   *
   * @param root root path; {@code null} or missing paths are ignored
   * @throws UncheckedIOException if the directory tree cannot be processed
   */
  public static void makeWritable(Path root) {
    if (root == null || Files.notExists(root)) {
      return;
    }

    try {
      Files.walkFileTree(root, new WritableFileVisitor());
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to make directory tree writable: " + root, exception);
    }
  }

  private static final class WritableFileVisitor extends SimpleFileVisitor<Path> {

    @Override
    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
        throws IOException {
      makePathWritable(directory);
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
      makePathWritable(file);
      return FileVisitResult.CONTINUE;
    }

    @Override
    public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
      if (exception instanceof NoSuchFileException) {
        log.debug("Skipping path that disappeared during traversal: {}", file);

        return FileVisitResult.CONTINUE;
      }

      throw exception;
    }

    private static void makePathWritable(Path path) throws IOException {
      try {
        if (path.toFile().setWritable(true)) {
          return;
        }

        /*
         * The path may have disappeared between discovery and the
         * permission change. Temporary Git lock files commonly exhibit
         * this behavior.
         */
        if (Files.notExists(path)) {
          return;
        }

        throw new IOException("Failed to make path writable: " + path);
      } catch (SecurityException exception) {
        throw new IOException("Insufficient permissions to make path writable: " + path, exception);
      }
    }
  }

  public void copyDirectory(String source, String destination) {
    copyDirectory(source, destination, null);
  }

  public void copyDirectory(String source, String destination, FileFilter fileFilter) {
    log.debug("Copying directory {} to {}", source, destination);

    try {
      FileUtils.copyDirectory(new File(source), new File(destination), fileFilter);
    } catch (IOException exception) {
      throw new UncheckedIOException(
          "Failed to copy directory from " + source + " to " + destination, exception);
    }
  }

  public void copyFile(String sourcePath, String destinationPath) {
    File sourceFile = new File(sourcePath);
    File destinationFile = new File(destinationPath);

    log.debug("Copying file from {} to {}", sourcePath, destinationPath);

    try {
      File parentDirectory = destinationFile.getParentFile();

      if (parentDirectory != null) {
        Files.createDirectories(parentDirectory.toPath());
      }

      FileUtils.copyFile(sourceFile, destinationFile);
    } catch (IOException exception) {
      throw new UncheckedIOException(
          "Failed to copy file from " + sourcePath + " to " + destinationPath, exception);
    }
  }

  public void createDirectory(String directory) {
    log.trace("Creating directory: {}", directory);

    try {
      Files.createDirectories(Path.of(directory));
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to create directory: " + directory, exception);
    }
  }

  public Path copyToTempDir(String filePath) {
    Path sourcePath = Path.of(filePath);

    try {
      Path destinationDirectory = Files.createTempDirectory(TEMP_FILE_PREFIX);

      Path destinationPath = destinationDirectory.resolve(sourcePath.getFileName());

      return Files.copy(sourcePath, destinationPath, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException exception) {
      throw new UncheckedIOException(
          "Failed to copy " + filePath + " to temporary directory", exception);
    }
  }

  public void deleteEmptyFiles(Path path, Pattern pathPattern) {
    if (Files.notExists(path)) {
      return;
    }

    try {
      Files.walkFileTree(
          path,
          new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                throws IOException {
              if (attributes.size() == 0 && pathPattern.matcher(file.toString()).find()) {
                log.trace("Deleting empty file {}", file);

                Files.deleteIfExists(file);
              }

              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exception)
                throws IOException {
              if (exception instanceof NoSuchFileException) {
                return FileVisitResult.CONTINUE;
              }

              throw exception;
            }
          });
    } catch (IOException exception) {
      throw new UncheckedIOException(
          "Failed to walk path for deleting empty files: " + path, exception);
    }
  }

  public Path createTempDir() {
    try {
      return Files.createTempDirectory(TEMP_FILE_PREFIX);
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to create temporary directory", exception);
    }
  }

  public Path createTempFile() {
    try {
      Path file = Files.createTempFile(TEMP_FILE_PREFIX, "");

      file.toFile().deleteOnExit();

      return file;
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to create temporary file", exception);
    }
  }

  public Map<String, Object> readYaml(Path path) {
    if (Files.exists(path)) {
      try {
        return yamlMapper.readValue(path.toFile(), YAML_MAP_TYPE);
      } catch (IOException exception) {
        throw new UncheckedIOException("Failed to parse YAML file: " + path, exception);
      }
    }

    String resourceName = normalizeClasspathResource(path);

    log.debug("Path {} not found on filesystem, trying classpath: {}", path, resourceName);

    try (InputStream inputStream = FileSystemUtils.class.getResourceAsStream(resourceName)) {

      if (inputStream == null) {
        log.warn("Could not find YAML at {} or on classpath {}", path, resourceName);

        return Collections.emptyMap();
      }

      return yamlMapper.readValue(inputStream, YAML_MAP_TYPE);
    } catch (IOException exception) {
      throw new UncheckedIOException(
          "Failed to read YAML resource from classpath: " + resourceName, exception);
    }
  }

  private static String normalizeClasspathResource(Path path) {
    String resourceName =
        path.toString()
            .replace('\\', '/')
            .replace("/src/main/resources", "")
            .replace("src/main/resources", "");

    if (!resourceName.startsWith("/")) {
      resourceName = "/" + resourceName;
    }

    return resourceName;
  }

  public Path writeTempFile(Map<String, Object> mapValues) {
    Path temporaryHelmValues = createTempFile();

    writeYaml(mapValues, temporaryHelmValues.toFile());

    return temporaryHelmValues;
  }

  public void writeYaml(Map<String, ?> yaml, File file) {
    try {
      yamlMapper.writeValue(file, yaml);
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to write YAML to file: " + file, exception);
    }
  }

  public void deleteFilesExcept(File parentPath, String... fileOrFolderNamesToKeep) {
    File[] files = parentPath.listFiles();

    if (files == null) {
      return;
    }

    Set<String> namesToKeep = Set.of(fileOrFolderNamesToKeep);

    for (File file : files) {
      if (namesToKeep.contains(file.getName())) {
        continue;
      }

      try {
        if (file.isDirectory()) {
          FileUtils.deleteDirectory(file);
        } else {
          Files.deleteIfExists(file.toPath());
        }
      } catch (IOException exception) {
        throw new UncheckedIOException("Failed to delete path: " + file, exception);
      }
    }
  }

  /**
   * Moves all direct children of {@code sourceDir} into {@code targetDir}.
   *
   * <p>Existing files are overwritten. Directories are merged recursively.
   */
  public void moveDirectoryMergeOverwrite(Path sourceDir, Path targetDir) {
    try {
      if (Files.notExists(targetDir)) {
        if (tryMoveDirectoryDirect(sourceDir, targetDir)) {
          return;
        }
      } else if (!Files.isDirectory(targetDir)) {
        Files.delete(targetDir);
        Files.createDirectories(targetDir);
      } else {
        // targetDir already exists as a directory; merge into it below
      }

      mergeDirectoryChildren(sourceDir, targetDir);

      Files.deleteIfExists(sourceDir);
    } catch (IOException exception) {
      throw new UncheckedIOException(
          "Failed to move directory " + sourceDir + " to " + targetDir, exception);
    }
  }

  private boolean tryMoveDirectoryDirect(Path sourceDir, Path targetDir) throws IOException {
    Path parent = targetDir.getParent();

    if (parent != null) {
      Files.createDirectories(parent);
    }

    try {
      Files.move(sourceDir, targetDir);
      return true;
    } catch (IOException moveException) {
      log.debug(
          "Could not move directory directly from {} to {}; falling back to recursive merge",
          sourceDir,
          targetDir,
          moveException);

      Files.createDirectories(targetDir);
      return false;
    }
  }

  private void mergeDirectoryChildren(Path sourceDir, Path targetDir) throws IOException {
    try (Stream<Path> children = Files.list(sourceDir)) {
      for (Path child : children.toList()) {
        Path destination = targetDir.resolve(child.getFileName());

        if (Files.isDirectory(child)) {
          moveDirectoryMergeOverwrite(child, destination);
        } else {
          moveFileOverwrite(child, destination);
        }
      }
    }
  }

  private void moveFileOverwrite(Path sourceFile, Path targetFile) {
    try {
      Path parent = targetFile.getParent();

      if (parent != null) {
        Files.createDirectories(parent);
      }

      moveOrCopyFile(sourceFile, targetFile);
    } catch (IOException exception) {
      throw new UncheckedIOException(
          "Failed to move file " + sourceFile + " to " + targetFile, exception);
    }
  }

  private void moveOrCopyFile(Path sourceFile, Path targetFile) throws IOException {
    try {
      Files.move(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException moveException) {
      log.debug(
          "Could not move file directly from {} to {}; falling back to copy and delete",
          sourceFile,
          targetFile,
          moveException);

      Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);

      Files.delete(sourceFile);
    }
  }

  /** Filter for copying directory content without Git metadata. */
  public static class IgnoreDotGitFolderFilter implements FileFilter {

    @Override
    public boolean accept(File file) {
      return !containsGitDirectory(file.toPath());
    }

    private static boolean containsGitDirectory(Path path) {
      for (Path part : path) {
        if (".git".equals(part.toString())) {
          return true;
        }
      }

      return false;
    }
  }
}
