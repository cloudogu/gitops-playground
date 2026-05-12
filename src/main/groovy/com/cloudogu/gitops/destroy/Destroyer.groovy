package com.cloudogu.gitops.destroy

import jakarta.inject.Singleton
import groovy.util.logging.Slf4j

@Singleton
@Slf4j
class Destroyer {

	final List<DestructionHandler> destructionHandlers

	Destroyer(List<DestructionHandler> destructionHandlers) {
		this.destructionHandlers = destructionHandlers
	}

	void destroy() {
		log.info("Start destroying")
		for (def handler in destructionHandlers) {
			log.info("Running handler $handler.class.simpleName")
			handler.destroy()
		}
		log.info("Finished destroying")
	}

	List<DestructionHandler> getDestructionHandlers() {
		return destructionHandlers
	}
}