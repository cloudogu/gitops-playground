package com.cloudogu.gitops.utils;

import groovy.yaml.YamlSlurper;
import jakarta.inject.Singleton;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.regex.Pattern;

@Singleton
@SuppressWarnings({"rawtypes", "unchecked"})
public class FileSystemUtils {

    private static final Logger log = LoggerFactory.getLogger(FileSystemUtils.class);

    /**
     * Replaces text in files. If you want to change a YAML field, better use
     * {@link #readYaml(java.nio.file.Path)} and
     * {@link #writeYaml(java.util.Map, java.io.File)}
     */
    public File replaceFileContent(String folder, String fileToChange, String from, String to) {
        File file = new File(folder + "/" + fileToChange);
        try {
            String newConfig = Files.readString(file.toPath()).replace(from, to);
            Files.writeString(file.toPath(), newConfig);
        } catch (IOException e) {
            throw new RuntimeException("Failed to replace file content: " + file, e);
        }
        return file;
    }

    public String replaceFileContent(String fileToChange, String from, String to) {
        File file = new File(fileToChange);
        try {
            String newConfig = Files.readString(file.toPath()).replaceAll(from, to);
            Files.writeString(file.toPath(), newConfig);
            return newConfig;
        } catch (IOException e) {
            throw new RuntimeException("Failed to replace file content: " + file, e);
        }
    }

    public String getSubstringOfFile(String fileLocation, CharSequence pattern, int from, int to) {
        File file = new File(fileLocation);
        final String[] found = {""};
        try {
            Files.readAllLines(file.toPath()).forEach(line -> {
                if (line.contains(pattern)) {
                    found[0] = line.substring(from, to);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file: " + fileLocation, e);
        }
        return found[0];
    }

    public String getSubstringOfFile(String fileLocation, CharSequence pattern, int from) {
        File file = new File(fileLocation);
        final String[] found = {""};
        try {
            Files.readAllLines(file.toPath()).forEach(line -> {
                if (line.contains(pattern)) {
                    found[0] = line.substring(from);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file: " + fileLocation, e);
        }
        return found[0];
    }

    public String getLineFromFile(String fileLocation, CharSequence pattern) {
        File file = new File(fileLocation);
        String found = "";
        try {
            String fileText = Files.readString(file.toPath());
            String[] lines = fileText.split("\n");
            for (String line : lines) {
                if (line.contains(pattern)) {
                    found = line;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file: " + fileLocation, e);
        }
        return found;
    }

    public List<String> getAllLinesFromFile(String fileLocation, CharSequence pattern) {
        File file = new File(fileLocation);
        List<String> foundLines = new ArrayList<>();
        try {
            Files.readAllLines(file.toPath()).forEach(line -> {
                if (line.contains(pattern)) {
                    foundLines.add(line);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file: " + fileLocation, e);
        }
        return foundLines;
    }

    public static void deleteFile(String path) {
        boolean successfullyDeleted = new File(path).delete();
        if (!successfullyDeleted) {
            log.warn("Failed to delete file {}", path);
        }
    }

    public static void deleteDir(String path) {
        try {
            FileUtils.deleteDirectory(new File(path));
        } catch (IOException e) {
            log.warn("Failed to delete dir {}", path, e);
        }
    }

    public String goBackToDir(String filePath, String directory) {
        return filePath.substring(0, filePath.indexOf(directory) + directory.length());
    }

    public String getRootDir() {
        return System.getProperty("user.dir");
    }

    public List<File> getAllFilesFromDirectoryWithEnding(String directory, String ending) {
        List<File> foundFiles = new ArrayList<>();
        File dir = new File(directory);
        if (dir.exists()) {
            try (var stream = Files.walk(dir.toPath())) {
                stream.filter(Files::isRegularFile)
                      .map(Path::toFile)
                      .filter(f -> f.getName().endsWith(ending))
                      .forEach(foundFiles::add);
            } catch (IOException e) {
                throw new RuntimeException("Failed to walk directory: " + directory, e);
            }
        }
        return foundFiles;
    }

    public void listDirectories(String parentDir) {
        List<File> list = new ArrayList<>();
        File dir = new File(parentDir);
        if (dir.exists()) {
            try (var stream = Files.walk(dir.toPath())) {
                stream.filter(Files::isRegularFile)
                      .map(Path::toFile)
                      .forEach(list::add);
            } catch (IOException e) {
                throw new RuntimeException("Failed to walk directory: " + parentDir, e);
            }
        }
        list.forEach(file -> System.out.println(file.getPath()));
    }

    public static void makeWritable(File directory) {
        if (!directory.exists()) {
            return;
        }
        try (var stream = Files.walk(directory.toPath())) {
            stream.map(Path::toFile).forEach(file -> {
                if (!file.canWrite()) {
                    file.setWritable(true);
                }
            });
        } catch (IOException e) {
            log.warn("Failed to walk directory for making it writable: {}", directory, e);
        }
    }

    public void copyDirectory(String source, String destination) {
        copyDirectory(source, destination, null);
    }

    public void copyDirectory(String source, String destination, FileFilter fileFilter) {
        log.debug("Copying directory {} to {}", source, destination);
        File sourceDir = new File(source);
        File destinationDir = new File(destination);

        try {
            FileUtils.copyDirectory(sourceDir, destinationDir, fileFilter);
        } catch (IOException e) {
            log.error("An error occurred while copying directories: ", e);
        }
    }

    public void copyFile(String sourcePath, String destinationPath) {
        File sourceFile = new File(sourcePath);
        File destinationFile = new File(destinationPath);

        log.debug("Copying file from {} to {}", sourcePath, destinationPath);

        try {
            File parentDir = destinationFile.getParentFile();
            if (!parentDir.exists()) {
                log.debug("Creating missing destination directories: {}", parentDir);
                parentDir.mkdirs();
            }

            FileUtils.copyFile(sourceFile, destinationFile);
            log.debug("File copy completed successfully.");
        } catch (IOException e) {
            log.error("An error occurred while copying the file: ", e);
        }
    }

    public void createDirectory(String directory) {
        log.trace("Creating folder: {}", directory);
        new File(directory).mkdirs();
    }

    public Path copyToTempDir(String filePath) {
        try {
            Path sourcePath = Path.of(filePath);
            Path destDir = Files.createTempDirectory("gitops-playground-");
            Path destPath = destDir.resolve(sourcePath.getFileName());
            return Files.copy(sourcePath, destPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy " + filePath + " to temp dir", e);
        }
    }

    public void deleteEmptyFiles(Path path, Pattern pathPattern) {
        try (var stream = Files.walk(path)) {
            stream.filter(p -> {
                try {
                    return Files.isRegularFile(p) && Files.size(p) == 0 && pathPattern.matcher(p.toString()).find();
                } catch (IOException e) {
                    return false;
                }
            }).forEach(p -> {
                log.trace("Deleting empty file {}", p);
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    log.warn("Failed to delete empty file {}", p, e);
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to walk path for deleting empty files: " + path, e);
        }
    }

    public Path createTempDir() {
        try {
            return Files.createTempDirectory("gitops-playground-");
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp directory", e);
        }
    }

    public Path createTempFile() {
        try {
            File file = File.createTempFile("gitops-playground-", "");
            file.deleteOnExit();
            return file.toPath();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp file", e);
        }
    }

    public Map readYaml(Path path) {
        YamlSlurper ys = new YamlSlurper();
        if (Files.exists(path)) {
            try {
                return (Map) ys.parse(path.toFile());
            } catch (IOException e) {
                throw new RuntimeException("Failed to parse YAML file: " + path, e);
            }
        }

        // Fallback to classpath
        String resourceName = path.toString();
        // Ensure it starts with / for getResourceAsStream from root
        if (!resourceName.startsWith("/")) {
            resourceName = "/" + resourceName;
        }

        // Remove src/main/resources if present, as it's not part of the classpath in the JAR
        resourceName = resourceName.replace("/src/main/resources", "");

        log.debug("Path {} not found on filesystem, trying classpath: {}", path, resourceName);
        try (var inputStream = FileSystemUtils.class.getResourceAsStream(resourceName)) {
            if (inputStream != null) {
                String text = new String(inputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                return (Map) ys.parseText(text);
            }
        } catch (IOException e) {
            log.debug("Failed to read resource from classpath: {}", resourceName, e);
        }

        log.warn("Could not find YAML at {} or on classpath {}", path, resourceName);
        return Collections.emptyMap();
    }

    public Path writeTempFile(Map mapValues) {
        Path tmpHelmValues = createTempFile();
        writeYaml(mapValues, tmpHelmValues.toFile());
        return tmpHelmValues;
    }

    // Note that YAML builder seems to use double quotes to escape strings. So for example:
    // This:     log-format-upstream: '..."$request"...'
    // Becomes:  log-format-upstream: "...\"$request\"..."
    // Harder to read but same payload. Not sure if we can do something about it.
    public void writeYaml(Map yaml, File file) {
        groovy.yaml.YamlBuilder builder = new groovy.yaml.YamlBuilder();
        builder.call(yaml);
        try {
            Files.writeString(file.toPath(), builder.toString());
        } catch (IOException e) {
            throw new RuntimeException("Failed to write YAML to file: " + file, e);
        }
    }

    public void deleteFilesExcept(File parentPath, String... fileOrFolderNamesToKeep) {
        File[] files = parentPath.listFiles();
        if (files == null) {
            return;
        }
        Set<String> keepSet = Set.of(fileOrFolderNamesToKeep);
        for (File file : files) {
            if (keepSet.contains(file.getName())) {
                continue;
            }
            if (!file.isDirectory()) {
                file.delete();
            } else {
                try {
                    FileUtils.deleteDirectory(file);
                } catch (IOException e) {
                    log.warn("Failed to delete directory: {}", file, e);
                }
            }
        }
    }

    /**
     * Moves all direct children of sourceDir into an existing targetDir.
     * Conflicts are overwritten.
     * Directories are merged recursively.
     */
    public void moveDirectoryMergeOverwrite(Path sourceDir, Path targetDir) {
        try {
            if (!Files.exists(targetDir)) {
                Files.createDirectories(targetDir.getParent());
                // fast path: try moving the whole directory
                try {
                    Files.move(sourceDir, targetDir);
                    return;
                } catch (IOException ignored) {
                    // fallback to merge logic
                    Files.createDirectories(targetDir);
                }
            } else if (!Files.isDirectory(targetDir)) {
                // target exists as file -> overwrite it with directory
                Files.delete(targetDir);
                Files.createDirectories(targetDir);
            }

            try (var stream = Files.list(sourceDir)) {
                stream.forEach(child -> {
                    Path dest = targetDir.resolve(child.getFileName().toString());
                    if (Files.isDirectory(child)) {
                        moveDirectoryMergeOverwrite(child, dest);
                    } else {
                        moveFileOverwrite(child, dest);
                    }
                });
            }

            // remove empty source dir
            Files.deleteIfExists(sourceDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to move directory merge overwrite", e);
        }
    }

    private void moveFileOverwrite(Path sourceFile, Path targetFile) {
        try {
            Files.createDirectories(targetFile.getParent());
            try {
                Files.move(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailed) {
                // cross-device fallback
                Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
                Files.delete(sourceFile);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to move file overwrite", e);
        }
    }

    /**
     * This filter can be used to copy whole directories without .git folder.
     */
    public static class IgnoreDotGitFolderFilter implements FileFilter {
        @Override
        public boolean accept(File file) {
            return !file.getAbsolutePath().contains(File.separator + ".git");
        }
    }
}
