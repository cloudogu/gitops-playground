package com.cloudogu.gitops.destroy;

import jakarta.inject.Singleton;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Singleton
@RequiredArgsConstructor
@Slf4j
public class Destroyer {

@Getter private final List<DestructionHandler> destructionHandlers;

public void destroy() {
	log.info("Start destroying");
	for (DestructionHandler handler : destructionHandlers) {
	log.info("Running handler {}", handler.getClass().getSimpleName());
	handler.destroy();
	}
	log.info("Finished destroying");
}
}
