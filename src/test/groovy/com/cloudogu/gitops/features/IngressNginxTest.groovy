package com.cloudogu.gitops.features

import com.cloudogu.gitops.config.Configuration
import com.cloudogu.gitops.features.deployment.HelmStrategy
import com.cloudogu.gitops.utils.CommandExecutorForTest
import com.cloudogu.gitops.utils.FileSystemUtils
import com.cloudogu.gitops.utils.HelmClient
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.assertj.core.api.Assertions.assertThat

class IngressNginxTest {

    Map config = [
            application: [
                    username: 'abc',
                    password: '123',
                    remote  : false,
                    namePrefix: "foo-",
            ],
            features:[
                    ingress: [
                            active: false,
                            helm  : [
                                    chart: 'ingress-nginx',
                                    repoURL: 'https://kubernetes.github.io/ingress-nginx',
                                    version: '4.8.2'
                            ],
                    ],
            ],
    ]

    CommandExecutorForTest commandExecutor = new CommandExecutorForTest()
    HelmClient helmClient = new HelmClient(commandExecutor)
    Path temporaryYamlFile

    @Test
    void 'When Ingress-Nginx is enabled, ingressClassResource is set to true'() {
        config.features['ingress']['active'] = true

        createIngressNginx().install()

        assertThat(temporaryYamlFile.toFile().getText()).isEqualTo("""---
controller:
  annotations:
    ingressclass.kubernetes.io/is-default-class: "true"
  watchIngressWithoutClass: true
  admissionWebhooks:
    enabled: false
  kind: Deployment
  service:
    # Preserve client ip address
    # https://kubernetes.io/docs/tasks/access-application-cluster/create-external-load-balancer/#preserving-the-client-source-ip
    externalTrafficPolicy: Local
  replicaCount: 2
  resources:
    # Leave limits open for now, because our ingress controller is Single Point of failure
    ##  limits:
    ##    cpu: 100m
    ##    memory: 90Mi
  ingressClassResource:
    enabled: true
    default: true
    #extraArgs:
    #default-ssl-certificate: "ingress-nginx/wildcard-cert"
  config:
    # settings for compression
    use-gzip: "true"
    enable-brotli: "true"
    # permanent redirect from http to https
    #force-ssl-redirect: "true"
    # customize access log format to include requested hostname (\$host)
    # https://github.com/kubernetes/ingress-nginx/blob/controller-v1.2.1/docs/user-guide/nginx-configuration/log-format.md
    log-format-upstream: '\$remote_addr - \$remote_user [\$time_local] \"\$request\" \$status \$body_bytes_sent \"\$http_referer\" \"\$http_user_agent\" \"\$host\" \$request_length \$request_time [\$proxy_upstream_name] [\$proxy_alternative_upstream_name] \$upstream_addr \$upstream_response_length \$upstream_response_time \$upstream_status \$req_id'
""")
    }

    @Test
    void 'When Ingress-Nginx is not enabled, ingress-nginx-helm-values yaml has no content'() {
        config.features['ingress']['active'] = false

        createIngressNginx().install()

        assertThat(temporaryYamlFile).isNull()
    }

    private IngressNginx createIngressNginx() {
        // We use the real FileSystemUtils and not a mock to make sure file editing works as expected

        new IngressNginx(new Configuration(config), new FileSystemUtils() {
            @Override
            Path copyToTempDir(String filePath) {
                def ret = super.copyToTempDir(filePath)
                temporaryYamlFile = Path.of(ret.toString().replace(".ftl", "")) // Path after template invocation

                return ret
            }
        }, new HelmStrategy(new Configuration(config), helmClient))
    }

    private parseActualYaml() {
        def ys = new YamlSlurper()
        return ys.parse(temporaryYamlFile)
    }

}
