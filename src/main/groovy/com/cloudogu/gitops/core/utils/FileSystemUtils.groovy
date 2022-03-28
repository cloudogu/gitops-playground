package com.cloudogu.gitops.core.utils

import groovy.io.FileType
import groovy.util.logging.Slf4j
import org.apache.commons.io.FileUtils

@Slf4j
class FileSystemUtils {

    @Deprecated
    // TODO replace this by proper YAML editing?
    File replaceFileContent(String folder, String fileToChange, String from, String to) {
        File file = new File(folder + "/" + fileToChange)
        String newConfig = file.text.replace(from, to)
        file.setText(newConfig)
        return file
    }

    String replaceFileContent(String fileToChange, String from, String to) {
        File file = new File(fileToChange)
        String newConfig = file.text.replace(from, to)
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
        log.debug("Creating folder: " + directory)
        new File(directory).mkdirs()
    }
}
