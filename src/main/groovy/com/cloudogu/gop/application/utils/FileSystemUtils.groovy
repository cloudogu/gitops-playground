package com.cloudogu.gop.application.utils

import groovy.io.FileType
import groovy.util.logging.Slf4j
import org.apache.commons.io.FileUtils

@Slf4j
class FileSystemUtils {

    static File replaceFileContent(String folder, String fileToChange, String from, String to) {
        File file = new File(folder + "/" + fileToChange)
        String newConfig = file.text.replace(from, to)
        file.setText(newConfig)
        return file
    }

    static String replaceFileContent(String fileToChange, String from, String to) {
        File file = new File(fileToChange)
        String newConfig = file.text.replace(from, to)
        file.setText(newConfig)
        return file
    }

    static String getSubstringOfFile(String fileLocation, CharSequence pattern, int from, int to) {
        File file = new File(fileLocation)
        String found = ""
        file.readLines().forEach(line -> {
            if (line.contains(pattern)) {
                found = line.substring(from, to)
            }
        })
        return found
    }

    static String getSubstringOfFile(String fileLocation, CharSequence pattern, int from) {
        File file = new File(fileLocation)
        String found = ""
        file.readLines().forEach(line -> {
            if (line.contains(pattern)) {
                found = line.substring(from)
            }
        })
        return found
    }

    static String getLineFromFile(String fileLocation, CharSequence pattern) {
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

    static List<String> getAllLinesFromFile(String fileLocation, CharSequence pattern) {
        File file = new File(fileLocation)
        List<String> foundLines = new ArrayList<>()
        file.readLines().forEach(line -> {
            if (line.contains(pattern)) {
                foundLines.add(line)
            }
        })
        return foundLines
    }

    static String goBackToDir(String filePath, String directory) {
        return filePath.substring(0, filePath.indexOf(directory) + directory.length())
    }

    static String getGopRoot() {
        String userDir = System.getProperty("user.dir")
        String localGop = "k8s-gitops-playground"
        String dockerGop = "app"
        if (userDir.contains(localGop)) {
            return goBackToDir(userDir, localGop)
        } else if (userDir.contains(dockerGop)) {
            return goBackToDir(userDir, dockerGop)
        }
    }

    static List<File> getAllFilesFromDirectoryWithEnding(String directory, String ending) {
        List<File> foundFiles = new ArrayList<>()
        new File(directory).eachFileRecurse(FileType.FILES) {
            if (it.name.endsWith(ending)) {
                foundFiles.add(it)
            }
        }
        return foundFiles
    }

    static void listDirectories(String parentDir) {
        List<File> list = []

        File dir = new File(parentDir)
        dir.eachFileRecurse(FileType.FILES) { file ->
            list << file
        }
        list.each {
            println it.path
        }
    }

    static void copyDirectory(String source, String destination) {
        log.debug("Copying directory " + source + " to " + destination)
        File sourceDir = new File(source)
        File destinationDir = new File(destination)

        try {
            FileUtils.copyDirectory(sourceDir, destinationDir)
        } catch (IOException e) {
            log.error("An error occured while copying directories: ", e)
        }
    }

    static void createDirectory(String directory) {
        log.debug("Creating folder: " + directory)
        new File(directory).mkdirs()
    }
}
