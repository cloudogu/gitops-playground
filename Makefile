SHELL := /bin/bash
.ONESHELL:
.SHELLFLAGS = -ec
RUN_ARGS := $(wordlist 2,$(words $(MAKECMDGOALS)),$(MAKECMDGOALS))


help:
	@grep -E '^[a-zA-Z_-]+:.*##' $(MAKEFILE_LIST) | \
	awk -F '##' '{printf "  %-15s %s\n", $$1, $$2}'

.PHONY:
prepare-airgapped-cluster: ## for airgapped-tests
	./scripts/dev/prepare_airgapped_cluster.sh

.PHONY:
cluster: ## creates a k3d cluster suitable for GOP
	./scripts/init-cluster.sh $(RUN_ARGS)

.PHONY:
prepare-two-registries: ## for testing with multiple registries
	./scripts/dev/prepare_two_registries.sh
.PHONY: install-operator
install-operator: ## installs argocd operator via kubectl and kustomize
	kubectl apply -k github.com/argoproj-labs/argocd-operator/config/default?ref=release-0.17 --server-side --force-conflicts
	kubectl apply -k github.com/argoproj-labs/argocd-operator/config/crd?ref=release-0.17 --server-side --force-conflicts

%:
	@:
