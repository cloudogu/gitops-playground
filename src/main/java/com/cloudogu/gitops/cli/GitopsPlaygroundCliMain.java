package com.cloudogu.gitops.cli;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class GitopsPlaygroundCliMain {

public static void main(String[] args) {
	System.exit(new GitopsPlaygroundCliMain().exec(args, GitopsPlaygroundCli.class).ordinal());
}

public ReturnCode exec(String[] args, Class<? extends GitopsPlaygroundCli> commandClass) {
	try {
	GitopsPlaygroundCli app = commandClass.getDeclaredConstructor().newInstance();
	return app.run(args);
	} catch (RuntimeException e) {
	if (log.isDebugEnabled()) {
		log.error("", e);
	} else {
		log.error(e.getMessage());
	}
	return ReturnCode.GENERIC_ERROR;
	} catch (Exception e) {
	log.error("Fatal error starting CLI", e);
	return ReturnCode.GENERIC_ERROR;
	}
}
}
