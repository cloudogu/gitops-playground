package com.cloudogu.gitops.kubernetes.rbac

import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.git.GitRepo
import com.cloudogu.gitops.kubernetes.argocd.ArgoApplication
import com.cloudogu.gitops.utils.FileSystemUtils
import groovy.yaml.YamlSlurper
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat

class ArgocdApplicationTest {


    Config config = Config.fromMap([
            scm        : [
                    scmManager: [username: 'user',
                                 password: 'pass',
                                 host    : 'localhost',
                                 rootPath: 'scm'
                    ],
                    gitlab    : [username: 'user',
                                 password: 'pass',

                    ]
            ],
            application: [
                    namePrefix: '',
                    insecure  : false,
                    gitName   : 'Test User',
                    gitEmail  : 'test@example.com'
            ]
    ])

    @Test
    void 'simple ArgoCD Application with common values'() {

        GitRepo repo = new GitRepo(config, null, "my-repo", new FileSystemUtils())

        new ArgoApplication(
                'example-apps',
                'testurl.com/argocd/example-apps',
                'testprefix-argocd',
                'testnamespace',
                'argocd/')
                .generate(repo, 'testsubfolder/test')


        File file = new File(repo.getAbsoluteLocalRepoTmpDir(), "testsubfolder/test/argocd-application-example-apps-testprefix-argocd.yaml")
        assertThat(file).exists()
        Map yaml = new YamlSlurper().parse(file) as Map

        assertThat(yaml["metadata"]["name"]).isEqualTo('example-apps')
        assertThat(yaml["metadata"]["namespace"]).isEqualTo('testprefix-argocd')
        assertThat(yaml["spec"]["destination"]["namespace"]).isEqualTo('testnamespace')

        assertThat(yaml["spec"]["source"]["path"]).isEqualTo('argocd/')
        assertThat(yaml["spec"]["source"]["repoURL"]).isEqualTo('testurl.com/argocd/example-apps')
    }
}