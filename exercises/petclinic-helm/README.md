# Übung Helm

## Einleitung
Bei dieser Übung geht es darum, die `gitops-build-lib` auf dem CI-Server anzuwenden. 
Dafür steht das Repository `exercises/petclinic-helm` bereit, welches Sie in ein neues Repository im 
`argocd` Namespace überführen, damit der Folderjob im Jenkins dieses Projekt baut. Sie werden feststellen, 
dass das Projekt erfolgreich gebaut wird, allerdings kein Deployment in das Cluster erfolgt. 

# Aufgabenstellung

Ihre Aufgabe ist es nun, das Jenkinsfile um eine `deploy`-Stage zu erweitern, welche unter Verwendung der `gitops-build-lib` dafür sorgt,
das Clusterressourcen erzeugt und letztlich von `argocd` auf dem Cluster angewendet werden.

1. Repository aus `exercises/petclinic-helm` in `argocd/exercise-petclinic-helm` überführen
   1. Stellen Sie sicher, dass die Übungsaufgabe gebaut wird aber nicht auf dem Cluster deployt wird.
2. Binden Sie die `gitops-build-lib` im Jenkinsfile ein
   1. Sie benötigen die Version sowie die Repository-URL
   2. Sorgen Sie dafür, dass diese global im Skript verwendet werden kann
3. Erweitern Sie das Jenkinsfile um eine `deploy`-Stage und verwenden die `gitops-build-lib`
   1. Hinweise zur Verwendung finden Sie im Repository der `gitops-build-lib`
4. Stellen Sie sicher, dass die Anwendung im Cluster deployt wurde.

# Tipps

* Der SCM-Manager bietet einen Import für Repositories
* Die Verwendung der CES-Build-Lib dient als Beispiel
* 

# Lösung
* Verwenden Sie die Import-Funktion des SCM-Managers um ein Repository von einem Namespace in den anderen zu clonen.
    * Alternativ erzeugen Sie einen Dump und importieren diesen

* Einbinden der `gitops-build-lib`: 

```groovy

gitOpsBuildLib = library(identifier: "gitops-build-lib@${gitOpsBuildLibVersion}",
    retriever: modernSCM([$class: 'GitSCMSource', remote: gitOpsBuildLibRepo, credentialsId: scmManagerCredentials])
).com.cloudogu.gitops.gitopsbuildlib

...
// define the variable outside of any scope to ensure its globally usable within the script
def gitOpsBuildLib
```


Übung:
Voraussetzung: GOP erweitern um Exercise PetClinic, die Helm YAMLS und einfaches Jenkinsfile ohne GitOps Deploy Stage enthält.
Aufgabe: Dieses Repo importieren und Jenkinsfile erweitern um ein Deployment per GitOps Config und Helm.

