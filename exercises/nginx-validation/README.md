# Exercise: Resource validation using `gitops-build-lib`

This repository contains an exercise on the validation utils provided by our `gitops-build-lib`. We've prepared some
broken yaml-resources for this nginx-pipeline. Your task is to eliminate all the bugs and let the `jenkins` deploy it.
Jenkins will provide you guidance using its logs. The validators will spot all the bugs for you, all you have to do is check the
logs and fix the bugs. 
The first step you have to take is to copy this repository under the namespace `argocd` in order for `jenkins` to pick it up..