//file:noinspection GrMethodMayBeStatic - it's not static to be able to hook in for testing
package com.cloudogu.gitops.utils

import groovy.io.FileType
import groovy.util.logging.Slf4j
import groovy.yaml.YamlBuilder
import groovy.yaml.YamlSlurper
import jakarta.inject.Singleton
import org.apache.commons.io.FileUtils

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

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

    void copyDirectory(String source, String destination) {
        log.debug("Copying directory " + source + " to " + destination)
        File sourceDir = new File(source)
        File destinationDir = new File(destination)

        try {
            FileUtils.copyDirectory(sourceDir, destinationDir)
        } catch (IOException e) {
            log.error("An error occured while copying directories: ", e)
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

    void writeYaml(Map yaml, File file) {
        def builder = new YamlBuilder()
        builder yaml
        file.setText(builder.toString())
    }

    void deleteFilesExcept(File parentPath, String ... fileOrFolderNamesToKeep) {
        for(File file: parentPath.listFiles()) {
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
}
