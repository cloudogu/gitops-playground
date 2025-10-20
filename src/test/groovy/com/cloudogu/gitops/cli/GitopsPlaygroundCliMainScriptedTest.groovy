package com.cloudogu.gitops.cli

import com.cloudogu.gitops.Application
import com.cloudogu.gitops.Feature
import com.cloudogu.gitops.config.Config
import com.cloudogu.gitops.destroy.Destroyer
import com.cloudogu.gitops.destroy.DestructionHandler
import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfo
import io.micronaut.context.ApplicationContext
import io.micronaut.core.annotation.Order
import org.junit.jupiter.api.Test

import static org.assertj.core.api.Assertions.assertThat
import static org.assertj.core.api.Fail.fail
import static com.cloudogu.gitops.config.Config.*

/**
 * It is difficult to test if *all* classes are instantiated.
 * Except for edge cases like outputConfigFile or delete,
 * the core logic of the application is to install all {@link Feature}s in the proper {@link Order}.
 * At least this we can test!
 */
class GitopsPlaygroundCliMainScriptedTest {

    ApplicationContext applicationContext
    GitopsPlaygroundCliScriptedForTest gitopsPlaygroundCliScripted = new GitopsPlaygroundCliScriptedForTest()
    Config config = new Config(
            jenkins: new JenkinsSchema(url: 'http://jenkins'),
            scmm: new ScmmSchema(url: 'http://scmm')
    )

    /**
     * This test makes sure that we don't forget to add new {@link Feature} classes to 
     * {@link GitopsPlaygroundCliMainScripted.GitopsPlaygroundCliScripted#register(Config, io.micronaut.context.ApplicationContext)}
     * so they also work in the dev image.
     */
    @Test
    void 'all Feature classes are instantiated in the correct order'() {
        gitopsPlaygroundCliScripted.createApplicationContext()
        gitopsPlaygroundCliScripted.register(config, applicationContext)

        List<String> actualClasses = applicationContext.getBean(Application).features
                .collect { it.class.simpleName }

        def expectedClasses = findAllChildClasses(Feature)

        assertThat(actualClasses).containsExactlyElementsOf(expectedClasses)
    }

    @Test
    void 'all DestructionHandlers are instantiated in the correct order'() {
        config = new Config(config.properties + [application: new ApplicationSchema(destroy: true)])

        gitopsPlaygroundCliScripted.createApplicationContext()
        gitopsPlaygroundCliScripted.register(config, applicationContext)

        List<String> actualClasses = applicationContext.getBean(Destroyer).destructionHandlers
                .collect { it.class.simpleName }

        def expectedClasses = findAllChildClasses(DestructionHandler)

        assertThat(actualClasses).containsExactlyElementsOf(expectedClasses)
    }

    protected List<String> findAllChildClasses(Class<?> parentClass) {
        boolean parentIsInterface = parentClass.isInterface()

        def featureClasses = []

        new ClassGraph()
                .acceptPackages("com.cloudogu")
                .enableClassInfo()
                .enableAnnotationInfo()
                .scan().withCloseable { scanResult ->
            scanResult.getAllClasses().each { ClassInfo classInfo ->
                if (classInfo.name.endsWith("Test") || classInfo.isAbstract() || !classInfo.hasAnnotation(jakarta.inject.Singleton)) {
                    return
                }

                if (classInfo.extendsSuperclass(parentClass) ||
                        (parentIsInterface && classInfo.implementsInterface(parentClass))) {
                    def orderAnnotation = classInfo.getAnnotationInfo(Order)
                    if (orderAnnotation) {
                        def orderValue = orderAnnotation.getParameterValues().getValue('value') as int
                        def clazz = classInfo.loadClass()
                        featureClasses << [clazz: clazz, orderValue: orderValue]
                    } else {
                        fail("Class ${classInfo.name} does not have @Order annotation")
                    }
                }
            }
        }

        return featureClasses.sort { a, b ->
            Integer.compare(a['orderValue'] as Integer, b['orderValue'] as Integer)
        }.collect { it['clazz']['simpleName'] } as List<String>
    }

    class GitopsPlaygroundCliScriptedForTest extends GitopsPlaygroundCliMainScripted.GitopsPlaygroundCliScripted {
        @Override
        protected ApplicationContext createApplicationContext() {
            applicationContext = super.createApplicationContext()
        }
    }
}
