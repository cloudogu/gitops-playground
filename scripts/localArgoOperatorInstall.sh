git clone https://github.com/argoproj-labs/argocd-operator && \
cd argocd-operator && \
git checkout release-0.11 && \

# Disable webhook by commenting out lines in config/default/kustomization.yaml
sed -i 's|^- ../webhook|# - ../webhook|' config/default/kustomization.yaml && \
sed -i 's|^- path: manager_webhook_patch.yaml|# - path: manager_webhook_patch.yaml|' config/default/kustomization.yaml && \

# Change the image tag from v0.11.1 to v0.11.0 in config/manager/kustomization.yaml
sed -i 's|newTag: v0.11.1|newTag: v0.11.0|' config/manager/kustomization.yaml && \

# Install Prometheus CRDs
kubectl create -f https://raw.githubusercontent.com/prometheus-community/helm-charts/main/charts/kube-prometheus-stack/charts/crds/crds/crd-alertmanagerconfigs.yaml || true && \
kubectl create -f https://raw.githubusercontent.com/prometheus-community/helm-charts/main/charts/kube-prometheus-stack/charts/crds/crds/crd-alertmanagers.yaml || true && \
kubectl create -f https://raw.githubusercontent.com/prometheus-community/helm-charts/main/charts/kube-prometheus-stack/charts/crds/crds/crd-podmonitors.yaml || true && \
kubectl create -f https://raw.githubusercontent.com/prometheus-community/helm-charts/main/charts/kube-prometheus-stack/charts/crds/crds/crd-probes.yaml || true && \
kubectl create -f https://raw.githubusercontent.com/prometheus-community/helm-charts/main/charts/kube-prometheus-stack/charts/crds/crds/crd-prometheuses.yaml || true && \
kubectl create -f https://raw.githubusercontent.com/prometheus-community/helm-charts/main/charts/kube-prometheus-stack/charts/crds/crds/crd-prometheusrules.yaml || true && \
kubectl create -f https://raw.githubusercontent.com/prometheus-community/helm-charts/main/charts/kube-prometheus-stack/charts/crds/crds/crd-servicemonitors.yaml || true && \
kubectl create -f https://raw.githubusercontent.com/prometheus-community/helm-charts/main/charts/kube-prometheus-stack/charts/crds/crds/crd-thanosrulers.yaml || true && \

# Install ArgoCD Operator CRDs and components
kubectl kustomize config/default | kubectl create -f - || true
rm -Rf ../argocd-operator/