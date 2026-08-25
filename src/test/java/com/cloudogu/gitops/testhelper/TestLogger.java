package com.cloudogu.gitops.testhelper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import lombok.Getter;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class TestLogger {

	private final Class<?> loggerInClass;

	@Getter
	private final MemoryAppender logs;

	public TestLogger(Class<?> clazz) {
		this(clazz, Level.DEBUG);
	}

	public TestLogger(Class<?> clazz, Level logLevel) {
		this.loggerInClass = clazz;
		this.logs = new MemoryAppender();

		Logger logger = (Logger) LoggerFactory.getLogger(loggerInClass);
		logs.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
		logger.setLevel(logLevel);
		logger.addAppender(logs);
		logs.start();
	}

	public void changeLogLevel(Level logLevel) {
		Logger logger = (Logger) LoggerFactory.getLogger(loggerInClass);
		logger.setLevel(logLevel);
	}

	public static class MemoryAppender extends ListAppender<ILoggingEvent> {

		public void reset() {
			list.clear();
		}

		public boolean contains(String string, Level level) {
			return list.stream()
					   .anyMatch(event -> event.toString().contains(string) && event.getLevel().equals(level));
		}

		public int countEventsForLogger(String loggerName) {
			return (int) list.stream()
							 .filter(event -> event.getLoggerName().contains(loggerName))
							 .count();
		}

		public List<ILoggingEvent> search(String string) {
			return list.stream()
					   .filter(event -> event.toString().contains(string))
					   .collect(Collectors.toList());
		}

		public List<ILoggingEvent> search(String string, Level level) {
			return list.stream()
					   .filter(event -> event.toString().contains(string) && event.getLevel().equals(level))
					   .collect(Collectors.toList());
		}

		public int getSize() {
			return list.size();
		}

		public List<ILoggingEvent> getLoggedEvents() {
			return Collections.unmodifiableList(list);
		}
	}
}
