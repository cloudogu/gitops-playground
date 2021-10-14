# Exercise: Deployment from helm application

This repository contains an exercise on how to use our `gitops-build-lib` to create k8s resources and deploy an application.
The `Jenkinsfile` contains stages on build, tests and building an image but is missing the deploy stage.
Your task is to add the deploy-stage creating, verifying and deploying resources to the cluster using our `gitops-build-lib`.
You can use our documentation on the `gitops-build-lib` to solve it - you can find hints (or the whole solution) in the `argocd/petclinic-helm` repository. 

---
Bei dieser Übung geht es darum, die `gitops-build-lib` auf dem CI-Server anzuwenden.
Dafür steht das Repository `exercises/petclinic-helm` bereit, welches Sie in ein neues Repository im
`argocd` Namespace überführen, damit der Folderjob im Jenkins dieses Projekt baut. Sie werden feststellen,
dass das Projekt erfolgreich gebaut wird, allerdings kein Deployment in das Cluster erfolgt.

Ihre Aufgabe ist es nun, das Jenkinsfile um eine `deploy`-Stage zu erweitern, welche unter Verwendung der `gitops-build-lib` dafür sorgt,
das Cluster-Resourcen erzeugt und letztlich von `argocd` auf dem Cluster angewendet werden.


1. Repository aus `exercises/petclinic-helm` in `argocd/exercise-petclinic-helm` überführen
   1. Die Berechtigungen des neu erstellten Repositories müssen noch angepasst werden, damit Jenkins darauf zugreifen kann (`gitops`).
   2. Stellen Sie sicher, dass die Übungsaufgabe im Jenkins gebaut wird und finden Sie heraus, warum die Anwendung nicht deployt wird.
2. Binden Sie die `gitops-build-lib` im Jenkinsfile ein
   1. Sie benötigen die Version sowie die Repository-URL
   2. Sorgen Sie dafür, dass diese global im Skript verwendet werden kann
3. Erweitern Sie das Jenkinsfile um eine `deploy`-Stage und verwenden Sie die `gitops-build-lib`
   1. Hinweise zur Verwendung finden Sie im Repository der [`gitops-build-lib`](http://localhost:9091/scm/repo/common/gitops-build-lib/readme#more-options)
   2. Es sollten alle notwendigen Helm-YAMLs erzeugt werden
   3. Ein Commit in das `gitops`-Repository, mit den erzeugten Ressourcen im `staging`-Verzeichnis, gepusht werden
   4. Ein neuer Branch plus PR für die `production`-Umgebung auf dem `gitops`-Repository erzeugt werden
4. Stellen Sie sicher, dass die Anwendung im Cluster im `staging` Namespace deployt wurde.
5. Bringen Sie die Anwendung in den `production` Namespace!
6. Passen Sie den Build nun so an, dass auch `production` automatisch deployt wird.

## Einleitung
Bei dieser Übung geht es darum, die `gitops-build-lib` auf dem CI-Server anzuwenden. 
Dafür steht das Repository `exercises/petclinic-helm` bereit, welches Sie in ein neues Repository im 
`argocd` Namespace überführen, damit der Folderjob im Jenkins dieses Projekt baut. Sie werden feststellen, 
dass das Projekt erfolgreich gebaut wird, allerdings kein Deployment in das Cluster erfolgt. 

# Aufgabenstellung

Ihre Aufgabe ist es nun, das Jenkinsfile um eine `deploy`-Stage zu erweitern, welche unter Verwendung der `gitops-build-lib` dafür sorgt,
das Clusterressourcen erzeugt und letztlich von `argocd` auf dem Cluster angewendet werden.



# Tipps

<details>

<summary>Hier können Sie hilfreiche Tipps für die Aufgabe bekommen!</summary>

* Der SCM-Manager bietet einen Import für Repositories
* Die Verwendung der CES-Build-Lib dient als Beispiel
* Die Dokumentation der [GitOps-Build-Lib]()

</details>

# Lösung

<details>

<summary>Sie können Ihre Ergebnisse gerne mit unserer Lösung vergleichen!</summary>

1. Verwenden Sie die Import-Funktion des SCM-Managers um ein Repository von einem Namespace in den anderen zu clonen. 
   1. Alternativ erzeugen Sie einen Dump und importieren diesen 


2. Einbinden der `gitops-build-lib`:
```groovy
String getGitOpsBuildLibRepo() { "${env.SCMM_URL}/repo/common/gitops-build-lib" }
String getGitOpsBuildLibVersion() { '0.1.3'}

gitOpsBuildLib = library(identifier: "gitops-build-lib@${gitOpsBuildLibVersion}",
    retriever: modernSCM([$class: 'GitSCMSource', remote: gitOpsBuildLibRepo, credentialsId: scmManagerCredentials])
).com.cloudogu.gitops.gitopsbuildlib

// define the variable outside of any scope to ensure its globally usable within the script
def gitOpsBuildLib
```

3. Erstellen Sie die notwendige Konfiguration für die Build-Lib als Map
   1. Die Build-Lib nutzt als Input eine Konfigurationsmap die sämtliche Inhalte und Abhängigkeiten spezifiziert.
      ```groovy
        def gitopsConfig = [
            # Inhalt der Map wird nachfolgend detailliert besprochen
         ]
      ```
   2. Beginnend mit dem SCM-Provider
      ```groovy
          scm: [
            provider     : 'SCMManager',
            credentialsId: 'scmm-user',
            baseUrl      : "${env.SCMM_URL}",
            repositoryUrl   : 'argocd/gitops',
          ],
      ```
   3. Anschließend werden generelle Konfigurationsparameter übergeben, z.B. Anwendungsname
      ```groovy
      cesBuildLibRepo: "${env.SCMM_URL}/repo/common/ces-build-lib/",
                        cesBuildLibVersion: '1.46.1',
                        cesBuildLibCredentialsId: 'scmm-user',
                        application: 'exercise-spring-petclinic-helm',
                        mainBranch: 'main',
                        gitopsTool: 'ARGO',
      ```
   4. Deployment Konfiguration:
      ```groovy
      deployments: [
                            sourcePath: 'k8s',
                            helm : [
                                repoType : 'GIT',
                                credentialsId : 'scmm-user',
                                repoUrl  : "${env.SCMM_URL}/repo/common/spring-boot-helm-chart-with-dependency",
                                updateValues  : [[fieldPath: "image.name", newValue: imageName]]
                            ]
                        ],
      ```
   5. Konfiguration der verschiedenen Stages:
      ```groovy
      stages: [
                                staging: [
                                        namespace: 'argocd-staging',
                                        deployDirectly: true ],
                                production: [
                                        namespace: 'argocd-production',
                                        deployDirectly: false ]
                        ]
      ```
4. Stellen Sie sicher, dass die Anwendung im Cluster deployt wurde
   `kubectl get pods -n argocd-staging`

5. Rufen Sie nun das `gitops`-Repository für ArgoCD auf und führen ein Review des PRs durch.
6. 

</details>



---------------------------------

Übung:
Voraussetzung: GOP erweitern um Exercise PetClinic, die Helm YAMLS und einfaches Jenkinsfile ohne GitOps Deploy Stage enthält.
Aufgabe: Dieses Repo importieren und Jenkinsfile erweitern um ein Deployment per GitOps Config und Helm.

