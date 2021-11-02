# Exercise: Fixing an application deployment issue

This repository contains an exercise on how to use the argocd alerting mechanism to get notified via email if an application encounters some error.
Your task is to look in the argocd ui for this application and figure out why it fails. If you have an idea just apply your changes in this repo and see if argocd is able to deploy the application.
The first step you have to take is to copy this repository under the namespace `argocd` in order for `jenkins` to pick it up.