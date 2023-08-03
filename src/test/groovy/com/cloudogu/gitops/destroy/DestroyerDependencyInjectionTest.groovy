package com.cloudogu.gitops.destroy

import com.cloudogu.gitops.config.Configuration
import io.micronaut.context.ApplicationContext
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test

class DestroyerDependencyInjectionTest {
    @Test
    void 'can create bean'() {
        def destroyer = ApplicationContext.run()
                .registerSingleton(new Configuration([
                        scmm: [
                                url: 'http://localhost:9091/scm',
                                username: 'admin',
                                password: 'admin',
                        ],
                        jenkins: [
                                url: 'http://localhost:9090',
                                username: 'admin',
                                password: 'admin',
                        ],
                        application: [
                                insecure: true
                        ]
                ]))
                .getBean(Destroyer)

        Assertions.assertThat(destroyer.destructionHandlers).hasSize(3)
    }
}
