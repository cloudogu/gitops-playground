# Ressourcenanforderungen und -limits für den ArgoCD Operator

Die folgenden Ressourcenanforderungen und -limits gelten für verschiedene Komponenten des ArgoCD Operators. Die Tabelle beschreibt, welche Ressourcen jeder Teil des Systems benötigt.

| **Komponente**          | **Ressource** | **Anforderung (requests)** | **Limit (limits)** | **Beschreibung** |
|-------------------------|---------------|----------------------------|--------------------|------------------|
| **Notifications**        | CPU           | 100m (0.1 CPU)             | 100m (0.1 CPU)     | Benachrichtigungs-Subsystem für ArgoCD |
|                         | Memory        | 128Mi                      | 128Mi              | Speicheranforderung für Benachrichtigungen |
| **Controller**           | CPU           | 250m (0.25 CPU)            | 2000m (2 CPUs)     | Hauptcontroller des ArgoCD Operators |
|                         | Memory        | 1024Mi                     | 2048Mi             | Speicheranforderung für den Controller |
| **HA (High Availability)** | CPU         | 250m (0.25 CPU)            | 500m (0.5 CPU)     | Ressourcen für Hochverfügbarkeits-Modus |
|                         | Memory        | 128Mi                      | 256Mi              | Speicheranforderung für HA |
| **SSO (Single Sign-On)** | CPU           | 250m (0.25 CPU)            | 500m (0.5 CPU)     | Ressourcen für Dex (OAuth-Provider) |
|                         | Memory        | 128Mi                      | 256Mi              | Speicheranforderung für Dex |
| **Redis**                | CPU           | 250m (0.25 CPU)            | 500m (0.5 CPU)     | Ressourcen für Redis (Datenbank-Caching) |
|                         | Memory        | 128Mi                      | 256Mi              | Speicheranforderung für Redis |
| **Repo (Repository)**    | CPU           | 250m (0.25 CPU)            | 1000m (1 CPU)      | Ressourcen für Repositories (Speicherung und Verwaltung) |
|                         | Memory        | 256Mi                      | 1024Mi             | Speicheranforderung für Repositories |
| **Server**               | CPU           | 125m (0.125 CPU)           | 500m (0.5 CPU)     | Ressourcen für den ArgoCD-Server |
|                         | Memory        | 128Mi                      | 256Mi              | Speicheranforderung für den Server |


Die folgenden Ressourcenanforderungen und -limits gelten für die Komponenten des Prometheus Monitoring Stacks (aktiviert mit `--pod-resources`).

| **Komponente**                      | **Ressource** | **Anforderung (requests)** | **Limit (limits)** | **Beschreibung** |
|-------------------------------------|---------------|----------------------------|--------------------|------------------|
| **Prometheus Operator**              | CPU           | 20m (0.02 CPU)             | 300m (0.3 CPU)     | Operator für Prometheus Custom Resources |
|                                     | Memory        | 40Mi                       | 80Mi               | Speicheranforderung für den Operator |
| **Prometheus Config Reloader**       | CPU           | 200m (0.2 CPU)             | 200m (0.2 CPU)     | Reloader für Prometheus-Konfigurationen |
|                                     | Memory        | 50Mi                       | 50Mi               | Speicheranforderung für Config Reloader |
| **Grafana**                          | CPU           | 350m (0.35 CPU)            | 1000m (1 CPU)      | Visualisierungs- und Dashboard-Tool |
|                                     | Memory        | 70Mi                       | 140Mi              | Speicheranforderung für Grafana |
| **Grafana Sidecar**                  | CPU           | 35m (0.035 CPU)            | 100m (0.1 CPU)     | Sidecar für automatisches Dashboard-Laden |
|                                     | Memory        | 65Mi                       | 200Mi              | Speicheranforderung für Sidecar |
| **Prometheus**                       | CPU           | 50m (0.05 CPU)             | 500m (0.5 CPU)     | Metriken-Datenbank und -Server |
|                         