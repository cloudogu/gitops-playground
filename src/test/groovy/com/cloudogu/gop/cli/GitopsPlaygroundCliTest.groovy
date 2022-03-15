package com.cloudogu.gop.cli

import com.cloudogu.gitops.cli.GitopsPlaygroundCli
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import io.micronaut.configuration.picocli.PicocliRunner
import io.micronaut.context.ApplicationContext
import io.micronaut.context.env.Environment
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertTrue

class GitopsPlaygroundCliTest {



    @BeforeEach
    void mockScmmEndpoint() {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(9091), 0); // or use InetSocketAddress(0) for ephemeral port
        httpServer.createContext("/scm/api/v2/config/git/argocd/control-app", new HttpHandler() {
            void handle(HttpExchange exchange) throws IOException {
                byte[] response = "{\"success\": true}".getBytes();
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            }
        });
        httpServer.start();
    }

    @Test
    void testWithCommandLineOption() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream()
        System.out = new PrintStream(baos)
        ApplicationContext ctx = ApplicationContext.run(Environment.CLI, Environment.TEST)
        String[] args = ["--argocd", "--metrics"] as String[]
        PicocliRunner.run(GitopsPlaygroundCli, ctx, args)

        // groovy-cli-graal-nativeimage-micronaut-example
        assertTrue(baos.toString().contains("Deploying prometheus stack"))

        ctx.close()
    }
}
