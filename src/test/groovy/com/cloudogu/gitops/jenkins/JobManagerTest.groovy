package com.cloudogu.gitops.jenkins

import com.cloudogu.gitops.config.Config
import com.github.tomakehurst.wiremock.WireMockServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test

import static com.github.tomakehurst.wiremock.client.WireMock.*
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import static groovy.test.GroovyAssert.shouldFail
import static org.assertj.core.api.Assertions.assertThat
import static org.mockito.ArgumentMatchers.anyString
import static org.mockito.Mockito.*

class JobManagerTest {

    @Test
    void 'creates credential'() {
        def wireMockServer = new WireMockServer(options().dynamicPort())
        wireMockServer.start()

        try {
            wireMockServer.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
                    .willReturn(okJson('{"crumb":"the-crumb"}')))

            wireMockServer.stubFor(post(urlPathMatching(".*createCredentials.*"))
                    .willReturn(ok()))

            def jobManager = new JobManager(new JenkinsApiClient(
                    new Config(jenkins: new Config.JenkinsSchema(url: wireMockServer.baseUrl() + "/jenkins")),
                    new OkHttpClient()))

            jobManager.createCredential('the-jobname', 'the-id', 'the-username', 'the-password', 'some description')

            wireMockServer.verify(postRequestedFor(urlPathEqualTo("/jenkins/job/the-jobname/credentials/store/folder/domain/_/createCredentials")))

            def requests = wireMockServer.findAll(postRequestedFor(urlPathMatching(".*createCredentials.*")))
            assertThat(requests).hasSize(1)

            def requestBody = requests[0].bodyAsString
            assertThat(URLDecoder.decode(requestBody, "utf-8"))
                    .isEqualTo('json={"credentials":{"scope":"GLOBAL","id":"the-id","username":"the-username","password":"the-password","description":"some description","$class":"com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl"}}')

        } finally {
            wireMockServer.stop()
        }
    }

    @Test
    void 'throw when creating credential fails'() {
        def wireMockServer = new WireMockServer(options().dynamicPort())
        wireMockServer.start()

        try {
            wireMockServer.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
                    .willReturn(okJson('{"crumb":"the-crumb"}')))

            wireMockServer.stubFor(post(urlPathMatching(".*createCredentials.*"))
                    .willReturn(aResponse().withStatus(404)))

            def jobManager = new JobManager(new JenkinsApiClient(
                    new Config(jenkins: new Config.JenkinsSchema(url: wireMockServer.baseUrl() + "/jenkins")),
                    new OkHttpClient()))

            def exception = shouldFail(RuntimeException) {
                jobManager.createCredential('the-jobname', 'the-id', 'the-username', 'the-password', 'some description')
            }
            assertThat(exception.getMessage()).isEqualTo('Could not create credential id=the-id,job=the-jobname. StatusCode: 404')
        } finally {
            wireMockServer.stop()
        }
    }

    @Test
    void 'starts job'() {
        def wireMockServer = new WireMockServer(options().dynamicPort())
        wireMockServer.start()

        try {
            wireMockServer.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
                    .willReturn(okJson('{"crumb":"the-crumb"}')))

            wireMockServer.stubFor(post(urlPathMatching("/jenkins/job/the-jobname/build.*"))
                    .willReturn(ok()))

            def jobManager = new JobManager(new JenkinsApiClient(
                    new Config(jenkins: new Config.JenkinsSchema(url: wireMockServer.baseUrl() + "/jenkins")),
                    new OkHttpClient()))

            jobManager.startJob('the-jobname')

            wireMockServer.verify(postRequestedFor(urlPathEqualTo("/jenkins/job/the-jobname/build"))
                    .withQueryParam("delay", equalTo("0sec")))

        } finally {
            wireMockServer.stop()
        }
    }

    @Test
    void 'throw when starting job fails'() {
        def wireMockServer = new WireMockServer(options().dynamicPort())
        wireMockServer.start()

        try {
            wireMockServer.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
                    .willReturn(okJson('{"crumb":"the-crumb"}')))

            wireMockServer.stubFor(post(urlPathMatching("/jenkins/job/the-jobname/build.*"))
                    .willReturn(aResponse().withStatus(400)))

            def jobManager = new JobManager(new JenkinsApiClient(
                    new Config(jenkins: new Config.JenkinsSchema(url: wireMockServer.baseUrl() + "/jenkins")),
                    new OkHttpClient()))

            def exception = shouldFail(RuntimeException) {
                jobManager.startJob('the-jobname')
            }
            assertThat(exception.getMessage()).isEqualTo('Could not trigger build of Jenkins job: the-jobname. StatusCode: 400')
        } finally {
            wireMockServer.stop()
        }
    }

    @Test
    void 'throws when job contains invalid characters'() {
        def client = mock(JenkinsApiClient)
        def jobManager = new JobManager(client)

        def exception = shouldFail(RuntimeException) {
            jobManager.deleteJob("foo'foo")
        }
        assertThat(exception.getMessage()).isEqualTo('Job name cannot contain quotes.')
    }

    @Test
    void 'throws when job deletion fails'() {
        def client = mock(JenkinsApiClient)
        def jobManager = new JobManager(client)

        def exception = shouldFail(RuntimeException) {
            jobManager.deleteJob("foo-foo")
        }
        assertThat(exception.getMessage()).isEqualTo('Could not delete job foo-foo')
    }

    @Test
    void 'deletes job'() {
        def client = mock(JenkinsApiClient)
        def jobManager = new JobManager(client)

        when(client.runScript(anyString())).thenReturn("null")
        jobManager.deleteJob("foo")
        org.mockito.Mockito.verify(client).runScript("print(Jenkins.instance.getItem('foo')?.delete())")
    }

    @Test
    void 'checks existing Job'() {
        def wireMockServer = new WireMockServer(options().dynamicPort())
        wireMockServer.start()

        try {
            wireMockServer.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
                    .willReturn(okJson('{"crumb":"the-crumb"}')))

            wireMockServer.stubFor(post(urlPathEqualTo("/jenkins/job/the-jobname"))
                    .willReturn(ok()))

            def jobManager = new JobManager(new JenkinsApiClient(
                    new Config(jenkins: new Config.JenkinsSchema(url: wireMockServer.baseUrl() + "/jenkins")),
                    new OkHttpClient()))

            def exists = jobManager.jobExists('the-jobname')

            assertThat(exists).isEqualTo(true)
            wireMockServer.verify(postRequestedFor(urlPathEqualTo("/jenkins/job/the-jobname")))
        } finally {
            wireMockServer.stop()
        }
    }

    @Test
    void 'checks non-existing Job'() {
        def wireMockServer = new WireMockServer(options().dynamicPort())
        wireMockServer.start()

        try {
            wireMockServer.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
                    .willReturn(okJson('{"crumb":"the-crumb"}')))

            wireMockServer.stubFor(post(urlPathEqualTo("/jenkins/job/the-jobname"))
                    .willReturn(aResponse().withStatus(404)))

            def jobManager = new JobManager(new JenkinsApiClient(
                    new Config(jenkins: new Config.JenkinsSchema(url: wireMockServer.baseUrl() + "/jenkins")),
                    new OkHttpClient()))

            def exists = jobManager.jobExists('the-jobname')
            assertThat(exists).isEqualTo(false)
            wireMockServer.verify(postRequestedFor(urlPathEqualTo("/jenkins/job/the-jobname")))
        } finally {
            wireMockServer.stop()
        }
    }

    @Test
    void 'creates Job'() {
        def wireMockServer = new WireMockServer(options().dynamicPort())
        wireMockServer.start()

        try {
            wireMockServer.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
                    .willReturn(okJson('{"crumb":"the-crumb"}')))
            wireMockServer.stubFor(post(urlPathEqualTo("/jenkins/job/the-jobname"))
                    .willReturn(aResponse().withStatus(404)))
            wireMockServer.stubFor(post(urlPathMatching("/jenkins/createItem.*"))
                    .willReturn(ok()))

            def jobManager = new JobManager(new JenkinsApiClient(
                    new Config(jenkins: new Config.JenkinsSchema(url: wireMockServer.baseUrl() + "/jenkins")),
                    new OkHttpClient()))

            def created = jobManager.createJob('the-jobname', 'http://scm', 'ns', 'creds')

            assertThat(created).isEqualTo(true)

            wireMockServer.verify(postRequestedFor(urlPathEqualTo("/jenkins/job/the-jobname")))
            wireMockServer.verify(postRequestedFor(urlPathEqualTo("/jenkins/createItem"))
                    .withQueryParam("name", equalTo("the-jobname"))
                    .withRequestBody(containing('<serverUrl>http://scm</serverUrl>'))
                    .withRequestBody(containing('<namespace>ns</namespace>'))
                    .withRequestBody(containing('<credentialsId>creds</credentialsId>')))

        } finally {
            wireMockServer.stop()
        }
    }

    @Test
    void 'ignores existing Job'() {
        def wireMockServer = new WireMockServer(options().dynamicPort())
        wireMockServer.start()

        try {
            wireMockServer.stubFor(get(urlPathEqualTo("/jenkins/crumbIssuer/api/json"))
                    .willReturn(okJson('{"crumb":"the-crumb"}')))

            wireMockServer.stubFor(post(urlPathEqualTo("/jenkins/job/the-jobname"))
                    .willReturn(ok())) // 200 OK means "Job Exists"

            def jobManager = new JobManager(new JenkinsApiClient(
                    new Config(jenkins: new Config.JenkinsSchema(url: wireMockServer.baseUrl() + "/jenkins")),
                    new OkHttpClient()))

            def created = jobManager.createJob('the-jobname', 'http://scm', 'ns', 'creds')

            assertThat(created).isEqualTo(false)
            wireMockServer.verify(postRequestedFor(urlPathEqualTo("/jenkins/job/the-jobname")))
            wireMockServer.verify(0, postRequestedFor(urlPathEqualTo("/jenkins/createItem")))

        } finally {
            wireMockServer.stop()
        }
    }
}