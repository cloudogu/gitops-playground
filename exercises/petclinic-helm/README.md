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
   2. Die Deploy-Stage sollte alle notwendigen Helm-YAMLs erzeugen
   3. Einen Commit in das `gitops`-Repository, mit den erzeugten Ressourcen im `staging`-Verzeichnis, pushen. 
   4. Einen neuen Branch plus PR für die `production`-Umgebung auf dem `gitops`-Repository erzeugen
4. Stellen Sie sicher, dass die Anwendung im Cluster im `staging` Namespace deployt wurde.
5. Bringen Sie die Anwendung in `production`!
6. Passen Sie den Build nun so an, dass auch für die `staging` Umgebung ein manuelles Review erfolgen muss.

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

gitOpsBuildLib = library(identifier: "gitops-build-lib@${gitOpsBuildLibVersion}",
    retriever: modernSCM([$class: 'GitSCMSource', remote: gitOpsBuildLibRepo, credentialsId: scmManagerCredentials])
).com.cloudogu.gitops.gitopsbuildlib

...
// define the variable outside of any scope to ensure its globally usable within the script
def gitOpsBuildLib
```

3. Erstellen Sie die notwendige Konfiguration für die Build-Lib als Map
   1. Spezifizieren Sie die Repository-Inhalte
   ```groovy
        [ 
            ...
            scmProvider: ..
            ...
        ]
   ```
   2. Spezifieieren Sie ...

4. Stellen Sie sicher, dass die Anwendung im Cluster deployt wurde
   `kubectl get pods -n argocd-staging`

5. Rufen Sie nun das `gitops`-Repository für ArgoCD auf und führen ein Review des PRs durch.
6. 

</details>



---------------------------------

Übung:
Voraussetzung: GOP erweitern um Exercise PetClinic, die Helm YAMLS und einfaches Jenkinsfile ohne GitOps Deploy Stage enthält.
Aufgabe: Dieses Repo importieren und Jenkinsfile erweitern um ein Deployment per GitOps Config und Helm.

