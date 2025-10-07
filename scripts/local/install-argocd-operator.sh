git clone https://github.com/argoproj-labs/argocd-operator && \
cd argocd-operator && \
git checkout release-0.16 && \
make deploy IMG=quay.io/argoprojlabs/argocd-operator:v0.15.0

rm -Rf ../argocd-operator/