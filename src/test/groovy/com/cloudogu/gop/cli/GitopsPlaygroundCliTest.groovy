//package com.cloudogu.gop.cli
//
//import io.micronaut.configuration.picocli.PicocliRunner
//import io.micronaut.context.ApplicationContext
//import io.micronaut.context.env.Environment
//import org.junit.jupiter.api.Test
//
//import static org.junit.jupiter.api.Assertions.assertTrue
//
//class GitopsPlaygroundCliTest {
//
//    @Test
//    void testWithCommandLineOption() throws Exception {
//        ByteArrayOutputStream baos = new ByteArrayOutputStream()
//        System.out = new PrintStream(baos)
//        ApplicationContext ctx = ApplicationContext.run(Environment.CLI, Environment.TEST)
//        String[] args = ["--argocd"] as String[]
//        PicocliRunner.run(GitopsPlaygroundCli, ctx, args)
//
//        // groovy-cli-graal-nativeimage-micronaut-example
//        assertTrue(baos.toString().contains("argocd=true"))
//
//        ctx.close()
//    }
//}
