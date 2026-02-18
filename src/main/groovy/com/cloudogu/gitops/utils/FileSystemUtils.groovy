//file:noinspection GrMethodMayBeStatic - it's not static to be able to hook in for testing
package com.cloudogu.gitops.utils

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.regex.Pattern
import jakarta.inject.Singleton

import groovy.io.FileType
import groovy.util.logging.Slf4j
import groovy.yaml.YamlBuilder
import groovy.yaml.YamlSlurper

import org.apache.commons.io.FileUtils

@Slf4j
@Singleton
class FileSystemUtils {

    /**
     * Replaces text in files. If you want to change a YAML field, better use
     * {@link #readYaml(java.nio.file.Path)} and
     * {@link #writeYaml(java.util.Map, java.io.File)}
     */
    File replaceFileContent(String folder, String fileToChange, String from, String to) {
        File file = new File(folder + "/" + fileToChange)
        String newConfig = file.text.replace(from, to)
        file.setText(newConfig)
        return file
    }

    String replaceFileContent(String fileToChange, String from, String to) {
        File file = new File(fileToChange)
        String newConfig = file.text.replaceAll(from, to)
        file.setText(newConfig)
        return file
    }

    String getSubstringOfFile(String fileLocation, CharSequence pattern, int from, int to) {
        File file = new File(fileLocation)
        String found = ""
        file.readLines().forEach(line -> {
            if (line.contains(pattern)) {
                found = line.substring(from, to)
            }
        })
        return found
    }

    String getSubstringOfFile(String fileLocation, CharSequence pattern, int from) {
        File file = new File(fileLocation)
        String found = ""
        file.readLines().forEach(line -> {
            if (line.contains(pattern)) {
                found = line.substring(from)
            }
        })
        return found
    }

    String getLineFromFile(String fileLocation, CharSequence pattern) {
        File file = new File(fileLocation)
        String found = ""
        String fileText = file.getText()
        String[] lines = fileText.split("\n")
        for (int i = 0; i < lines.size(); i++) {
            if (lines[i].contains(pattern)) {
                found = lines[i]
            }
        }
        return found
    }

    List<String> getAllLinesFromFile(String fileLocation, CharSequence pattern) {
        File file = new File(fileLocation)
        List<String> foundLines = new ArrayList<>()
        file.readLines().forEach(line -> {
            if (line.contains(pattern)) {
                foundLines.add(line)
            }
        })
        return foundLines
    }

    static void deleteFile(String path) {
        boolean successfullyDeleted = new File(path).delete()
        if (!successfullyDeleted) {
            log.warn("Faild to delete file ${path}")
        }
    }

    static void deleteDir(String path) {
        boolean successfullyDeleted = new File(path).deleteDir()
        if (!successfullyDeleted) {
            log.warn("Faild to delete dir ${path}")
        }
    }

    String goBackToDir(String filePath, String directory) {
        return filePath.substring(0, filePath.indexOf(directory) + directory.length())
    }

    String getRootDir() {
        return System.getProperty("user.dir")
    }

    List<File> getAllFilesFromDirectoryWithEnding(String directory, String ending) {
        List<File> foundFiles = new ArrayList<>()
        new File(directory).eachFileRecurse(FileType.FILES) {
            if (it.name.endsWith(ending)) {
                foundFiles.add(it)
            }
        }
        return foundFiles
    }

    void listDirectories(String parentDir) {
        List<File> list = []

        File dir = new File(parentDir)
        dir.eachFileRecurse(FileType.FILES) { file ->
            list << file
        }
        list.each {
            println it.path
        }
    }

    static void makeWritable(File directory) {
        if (!directory.exists()) {
            return
        }
        directory.eachFileRecurse { file ->
            if (!file.canWrite()) {
                file.setWritable(true)
            }
        }
    }

    void copyDirectory(String source, String destination) {
        copyDirectory(source, destination, null)
    }

    void copyDirectory(String source, String destination, FileFilter fileFilter) {

        log.debug("Copying directory " + source + " to " + destination)
        File sourceDir = new File(source)
        File destinationDir = new File(destination)

        try {
            FileUtils.copyDirectory(sourceDir, destinationDir, fileFilter)
        } catch (IOException e) {
            log.error("An error occured while copying directories: ", e)
        }
    }

    void copyFile(String sourcePath, String destinationPath) {
        File sourceFile = new File(sourcePath)
        File destinationFile = new File(destinationPath)

        log.debug("Copying file from ${sourcePath} to ${destinationPath}")

        try {
            File parentDir = destinationFile.getParentFile()
            if (!parentDir.exists()) {
                log.debug("Creating missing destination directories: ${parentDir}")
                parentDir.mkdirs()
            }

            FileUtils.copyFile(sourceFile, destinationFile)
            log.debug("File copy completed successfully.")
        } catch (IOException e) {
            log.error("An error occurred while copying the file: ", e)
        }
    }


    void createDirectory(String directory) {
        log.trace("Creating folder: " + directory)
        new File(directory).mkdirs()
    }

    Path copyToTempDir(String filePath) {
        def sourcePath = Path.of(filePath)
        def destDir = File.createTempDir("gitops-playground-").toPath()
        def destPath = destDir.resolve(sourcePath.fileName)
        return Files.copy(sourcePath, destPath)
    }

    void deleteEmptyFiles(Path path, Pattern pathPattern) {
        Files.walk(path).filter { it.size() == 0 && it.toString() =~ pathPattern }.each { Path it ->
            log.trace("Deleting empty file $it")
            it.toFile().delete()
        }
    }

    Path createTempDir() {
        File.createTempDir("gitops-playground-").toPath()
    }


    Path createTempFile() {
        def file = File.createTempFile("gitops-playground-", '')
        file.deleteOnExit()

        return file.toPath()
    }

    Map readYaml(Path path) {
        def ys = new YamlSlurper()
        return (ys.parse path) as Map
    }

    Path writeTempFile(Map mapValues) {
        def tmpHelmValues = createTempFile()
        writeYaml(mapValues, tmpHelmValues.toFile())
        return tmpHelmValues
    }

    // Note that YAML builder seems to use double quotes to escape strings. So for example:
    // This:     log-format-upstream: '..."$request"...'
    // Becomes:  log-format-upstream: "...\"$request\"..."
    // Harder to read but same payload. Not sure if we can do something about it.
    void writeYaml(Map yaml, File file) {
        def builder = new YamlBuilder()
        builder yaml
        file.setText(builder.toString())
    }

    void deleteFilesExcept(File parentPath, String... fileOrFolderNamesToKeep) {
        for (File file : parentPath.listFiles()) {
            if (file.name in fileOrFolderNamesToKeep) {
                continue
            }
            if (!file.isDirectory()) {
                file.delete()
            } else {
                file.deleteDir()
            }
        }
    }

    /**
     * Moves all direct children of sourceDir into an existing targetDir.
     * Conflicts are overwritten.
     * Directories are merged recursively.
     */
    void moveDirectoryMergeOverwrite(Path sourceDir, Path targetDir) {
        if (!Files.exists(targetDir)) {
            Files.createDirectories(targetDir.parent)
            // fast path: try moving the whole directory
            try {
                Files.move(sourceDir, targetDir)
                return
            } catch (IOException ignored) {
                // fallback to merge logic
                Files.createDirectories(targetDir)
            }
        } else if (!Files.isDirectory(targetDir)) {
            // target exists as file -> overwrite it with directory
            Files.delete(targetDir)
            Files.createDirectories(targetDir)
        }

        Files.list(sourceDir).forEach { Path child ->
            Path dest = targetDir.resolve(child.fileName.toString())
            if (Files.isDirectory(child)) {
                moveDirectoryMergeOverwrite(child, dest)
            } else {
                moveFileOverwrite(child, dest)
            }
        }

        // remove empty source dir
        try {
            Files.deleteIfExists(sourceDir)
        } catch (IOException ignored) {}
    }

    private void moveFileOverwrite(Path sourceFile, Path targetFile) {
        Files.createDirectories(targetFile.parent)

        try {
            Files.move(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
        } catch (IOException moveFailed) {
            // cross-device fallback
            Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING)
            Files.delete(sourceFile)
        }
    }


    /**
     * This filter can be used to copy whole directories without .git folder.
     */
    static class IgnoreDotGitFolderFilter implements FileFilter {
        @Override
        boolean accept(File file) {
            return !file.absolutePath.contains(File.separator + ".git")
        }
    }
}
