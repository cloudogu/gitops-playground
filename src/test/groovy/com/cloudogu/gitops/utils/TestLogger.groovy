package com.cloudogu.gitops.utils

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

import java.util.stream.Collectors

class TestLogger {

    private Class loggerInClass
    private MemoryAppender memoryAppender

    TestLogger(Class clazz, Level loglevel = Level.DEBUG) {
        this.loggerInClass = clazz
        Logger logger = (Logger) LoggerFactory.getLogger(loggerInClass)
        memoryAppender = new MemoryAppender()
        memoryAppender.setContext((LoggerContext) LoggerFactory.getILoggerFactory())
        logger.setLevel(loglevel)
        logger.addAppender(memoryAppender)
        memoryAppender.start()
    }

    void changeLogLevel(Level loglevel) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerInClass)
        logger.setLevel(loglevel)
    }

    MemoryAppender getLogs() {
        return memoryAppender
    }
}

class MemoryAppender extends ListAppender<ILoggingEvent> {

    void reset() {
        list.clear();
    }

    boolean contains(String string, Level level) {
        return list.stream()
                .anyMatch(event -> event.toString().contains(string)
                        && event.getLevel().equals(level));
    }

    int countEventsForLogger(String loggerName) {
        return (int) list.stream()
                .filter(event -> event.getLoggerName().contains(loggerName))
                .count();
    }

    List<ILoggingEvent> search(String string) {
        return list.stream()
                .filter(event -> event.toString().contains(string))
                .collect(Collectors.toList()) as List<ILoggingEvent>;
    }

    List<ILoggingEvent> search(String string, Level level) {
        return list.stream()
                .filter(event -> event.toString().contains(string)
                        && event.getLevel().equals(level))
                .collect(Collectors.toList()) as List<ILoggingEvent>;
    }

    int getSize() {
        return list.size();
    }

    List<ILoggingEvent> getLoggedEvents() {
        return Collections.unmodifiableList(list);
    }
}
