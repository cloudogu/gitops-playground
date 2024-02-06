<!-- START doctoc generated TOC please keep comment here to allow auto update -->
<!-- DON'T EDIT THIS SECTION, INSTEAD RE-RUN doctoc TO UPDATE -->
**Table of Contents**  *generated with [DocToc](https://github.com/thlorenz/doctoc)*

- [Exercise: Resource validation using `gitops-build-lib`](#exercise-resource-validation-using-gitops-build-lib)

<!-- END doctoc generated TOC please keep comment here to allow auto update -->

# Exercise: Resource validation using `gitops-build-lib`

This repository contains an exercise on the validation utils provided by our `gitops-build-lib`. We've prepared some
broken yaml-resources for this nginx-pipeline. Your task is to eliminate all the bugs and let the `jenkins` deploy it.
Jenkins will provide you guidance using its logs. The validators will spot all the bugs for you, all you have to do is check the
logs and fix the bugs. 

The first step you have to take is to copy this repository under the namespace `argocd` in order for `jenkins` to pick it up.
You can use the export and import functions in SCM-Manager. You can export in "Settings" and import by clicking "Add Repository"
