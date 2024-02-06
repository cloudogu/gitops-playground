<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->
**Table of Contents**  *generated with [DocToc](https://github.com/thlorenz/doctoc)*

- [Exercise: Deployment from helm application](#exercise-deployment-from-helm-application)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

# Exercise: Deployment from helm application

This repository contains an exercise on how to use our `gitops-build-lib` to create k8s resources and deploy an application.
The `Jenkinsfile` contains stages on build, tests and building an image but is missing the deploy stage.
Your task is to add the deploy-stage creating, verifying and deploying resources to the cluster using our `gitops-build-lib`.
You can use our documentation on the `gitops-build-lib` to solve it - you can find hints (or the whole solution) in the `argocd/petclinic-helm` repository.

The first step you have to take is to copy this repository under the namespace `argocd` in order for `jenkins` to pick it up.
You can use the export and import functions in SCM-Manager. You can export in "Settings" and import by clicking "Add Repository"
