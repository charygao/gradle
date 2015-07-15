/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.testkit.runner

import org.gradle.testkit.runner.daemon.GradleDaemon
import org.gradle.testkit.runner.daemon.GradleDaemonAnalyzer
import org.gradle.testkit.runner.internal.DefaultGradleRunner
import org.gradle.util.GFileUtils
import org.junit.Rule
import org.junit.rules.TemporaryFolder

import static org.gradle.testkit.runner.TaskResult.*

class GradleRunnerIsolatedDaemonIntegrationTest extends AbstractGradleRunnerIntegrationTest {
    @Rule TemporaryFolder testUserHomeDir = new TemporaryFolder()

    def "configuration in default Gradle user home directory is ignored for test execution with daemon"() {
        given:
        File defaultGradleUserHomeDir = new File(testUserHomeDir.root, '.gradle')

        and:
        String gradlePropertiesContent = 'myProp1=propertiesFile'
        writeGradlePropertiesFile(defaultGradleUserHomeDir, gradlePropertiesContent)

        and:
        String initScriptContent = "allprojects { ext.myProp2 = 'initScript' }"
        writeInitScriptFile(defaultGradleUserHomeDir, initScriptContent)

        and:
        buildFile << """
            task verifyProjectProperties {
                doLast {
                    assert !project.ext.has('myProp1')
                    assert !project.ext.has('myProp2')
                }
            }
        """

        when:
        DefaultGradleRunner gradleRunner = (DefaultGradleRunner)runner('verifyProjectProperties')
        gradleRunner.withJvmArguments("-Duser.home=$testUserHomeDir.root.canonicalPath")
        BuildResult result = gradleRunner.build()

        then:
        noExceptionThrown()
        result.tasks.collect { it.path } == [':verifyProjectProperties']
        result.taskPaths(SUCCESS) == [':verifyProjectProperties']
        result.taskPaths(SKIPPED).empty
        result.taskPaths(UP_TO_DATE).empty
        result.taskPaths(FAILED).empty
    }

    def "configuration in custom Gradle user home directory is used for test execution with daemon"() {
        setup:
        String gradlePropertiesContent = 'myProp1=propertiesFile'
        File gradlePropertiesFile = writeGradlePropertiesFile(buildContext.gradleUserHomeDir, gradlePropertiesContent)

        and:
        String initScriptContent = "allprojects { ext.myProp2 = 'initScript' }"
        File initScriptFile = writeInitScriptFile(buildContext.gradleUserHomeDir, initScriptContent)

        and:
        buildFile << """
            task verifyProjectProperties {
                doLast {
                    assert project.ext.has('myProp1')
                    assert project.ext.has('myProp2')
                }
            }
        """

        when:
        DefaultGradleRunner gradleRunner = runner('verifyProjectProperties')
        BuildResult result = gradleRunner.build()

        then:
        noExceptionThrown()
        result.tasks.collect { it.path } == [':verifyProjectProperties']
        result.taskPaths(SUCCESS) == [':verifyProjectProperties']
        result.taskPaths(SKIPPED).empty
        result.taskPaths(UP_TO_DATE).empty
        result.taskPaths(FAILED).empty

        cleanup:
        GFileUtils.forceDelete(gradlePropertiesFile)
        GFileUtils.forceDelete(initScriptFile)
    }

    def "daemon process is reused for test execution if one already exists"() {
        given:
        File customGradleUserHomeDir = new File(testUserHomeDir.root, UUID.randomUUID().toString())
        GradleDaemonAnalyzer gradleDaemonAnalyzer = new GradleDaemonAnalyzer(new File(customGradleUserHomeDir, 'daemon'), buildContext.version.version)
        gradleDaemonAnalyzer.daemons.empty
        buildFile << helloWorldTask()

        when:
        DefaultGradleRunner gradleRunner = runner('helloWorld')
        gradleRunner.withGradleUserHomeDir(customGradleUserHomeDir)
        gradleRunner.build()

        then:
        noExceptionThrown()
        List<GradleDaemon> initialDaemons = gradleDaemonAnalyzer.daemons
        initialDaemons.size() == 1
        String daemonPidInUse = initialDaemons[0].pid
        daemonPidInUse

        when:
        gradleRunner.build()

        then:
        noExceptionThrown()
        List<GradleDaemon> laterDaemons = gradleDaemonAnalyzer.daemons
        laterDaemons.size() == 1
        daemonPidInUse == laterDaemons[0].pid

        cleanup:
        GFileUtils.forceDelete(customGradleUserHomeDir)
    }

    private File writeGradlePropertiesFile(File gradleUserHomeDir, String content) {
        File gradlePropertiesFile = new File(gradleUserHomeDir, 'gradle.properties')
        GFileUtils.writeFile(content, gradlePropertiesFile)
        gradlePropertiesFile
    }

    private File writeInitScriptFile(File gradleUserHomeDir, String content) {
        File initScriptFile = new File(gradleUserHomeDir, 'init.gradle')
        GFileUtils.writeFile(content, initScriptFile)
        initScriptFile
    }
}
