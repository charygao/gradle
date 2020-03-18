/*
 * Copyright 2019 the original author or authors.
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

package org.gradle.instantexecution

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.integtests.fixtures.BuildOperationsFixture
import org.gradle.integtests.fixtures.DefaultTestExecutionResult
import org.gradle.integtests.fixtures.executer.ExecutionFailure
import org.gradle.integtests.fixtures.instantexecution.InstantExecutionBuildOperationsFixture
import org.gradle.internal.logging.ConsoleRenderer
import org.gradle.test.fixtures.file.TestFile
import org.intellij.lang.annotations.Language

import javax.annotation.Nullable
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager
import java.nio.file.Paths
import java.util.regex.Pattern

import static org.gradle.util.Matchers.matchesRegexp
import static org.hamcrest.CoreMatchers.containsString
import static org.hamcrest.CoreMatchers.equalTo
import static org.hamcrest.CoreMatchers.not
import static org.hamcrest.CoreMatchers.notNullValue
import static org.hamcrest.CoreMatchers.nullValue
import static org.hamcrest.CoreMatchers.startsWith
import static org.junit.Assert.assertThat
import static org.junit.Assert.assertTrue

class AbstractInstantExecutionIntegrationTest extends AbstractIntegrationSpec {

    void buildKotlinFile(@Language("kotlin") String script) {
        buildKotlinFile << script
    }

    void instantRun(String... args) {
        run(INSTANT_EXECUTION_PROPERTY, *args)
    }

    void instantFails(String... args) {
        fails(INSTANT_EXECUTION_PROPERTY, *args)
    }

    public static final String INSTANT_EXECUTION_PROPERTY = "-D${SystemProperties.isEnabled}=true"
    public static final String PROBLEMS_REPORT_HTML_FILE_NAME = "instant-execution-report.html"

    protected InstantExecutionBuildOperationsFixture newInstantExecutionFixture() {
        return new InstantExecutionBuildOperationsFixture(new BuildOperationsFixture(executer, temporaryFolder))
    }

    protected void withFailOnProblems() {
        executer.withArgument("-D${SystemProperties.failOnProblems}=true")
    }

    protected void withDoNotFailOnProblems() {
        executer.withArgument("-D${SystemProperties.failOnProblems}=false")
    }

    // TODO move to InstantExecutionFixture

    protected void expectInstantExecutionWarnings(
        int totalProblemsCount = uniqueProblems.length,
        String... uniqueProblems
    ) {
        validateExpectedProblems(totalProblemsCount, uniqueProblems)

        assert !(result instanceof ExecutionFailure)

        assertProblemsConsoleSummary(totalProblemsCount, uniqueProblems as List)
        assertProblemsHtmlReport(totalProblemsCount, uniqueProblems.size())
    }

    protected void expectInstantExecutionFailure(
        Class<? extends InstantExecutionException> exceptionType,
        int totalProblemsCount = uniqueProblems.length,
        String... uniqueProblems
    ) {
        validateExpectedProblems(totalProblemsCount, uniqueProblems)

        assert result instanceof ExecutionFailure

        def exceptionMessagePrefix = exceptionType.getField("MESSAGE").get(null).toString()
        def summaryHeader = problemsSummaryHeaderFor(totalProblemsCount, uniqueProblems.length)

        // Assert build failure
        failure.assertThatDescription(startsWith(exceptionMessagePrefix))
        failure.assertThatDescription(containsString(summaryHeader))
        failure.assertThatDescription(matchesRegexp(".*See the complete report at file:.*${PROBLEMS_REPORT_HTML_FILE_NAME}"))
        uniqueProblems.each { problem ->
            failure.assertHasCause(problem)
        }

        // Assert stacktrace
        assertThat(failure.error, containsNormalizedString(
            "* Exception is:\n${exceptionType.name}: $exceptionMessagePrefix\n$summaryHeader\nSee the complete report at"
        ))
        def subExceptionType = exceptionType == InstantExecutionErrorsException
            ? InstantExecutionErrorException
            : InstantExecutionProblemException
        if (totalProblemsCount == 1) {
            assertThat(failure.error, containsString("Caused by: ${subExceptionType.name}: ${uniqueProblems.first()}"))
        } else {
            (1..totalProblemsCount).each { problemNumber ->
                assertThat(failure.error, containsString("Cause $problemNumber: ${subExceptionType.name}:"))
            }
        }

        // Assert HTML report
        assertProblemsHtmlReport(totalProblemsCount, uniqueProblems.size())
    }

    private static void validateExpectedProblems(int totalProblemsCount, String... uniqueProblems) {
        if (uniqueProblems.length == 0) {
            throw new IllegalArgumentException("Use TODO() when no instant execution failure is to be expected")
        }
        if (totalProblemsCount < uniqueProblems.length) {
            throw new IllegalArgumentException("`totalProblemsCount` can't be lesser than `uniqueProblems.length`")
        }
    }

    private void assertProblemsConsoleSummary(int totalProblemsCount, List<String> uniqueProblems) {
        assertProblemsSummaryHeaderInOutput(totalProblemsCount, uniqueProblems.size())
        assertUniqueProblemsInOutput(uniqueProblems)
    }

    private void assertProblemsSummaryHeaderInOutput(int totalProblems, int uniqueProblems) {
        if (totalProblems > 0 || uniqueProblems > 0) {
            def header = problemsSummaryHeaderFor(totalProblems, uniqueProblems)
            assertThat(relevantOutput(), containsNormalizedString(header))
        } else {
            assertThat(relevantOutput(), not(containsNormalizedString("instant execution problem")))
        }
    }

    private static String problemsSummaryHeaderFor(int totalProblems, int uniqueProblems) {
        return "${totalProblems} instant execution problem${totalProblems >= 2 ? 's were' : ' was'} found, " +
            "${uniqueProblems} of which seem${uniqueProblems >= 2 ? '' : 's'} unique."
    }

    private void assertUniqueProblemsInOutput(List<String> uniqueProblems) {
        def uniqueProblemsCount = uniqueProblems.size()
        def problems = uniqueProblems.collect { "> $it".toString() }
        def found = 0
        def output = relevantOutput()
        output.readLines().eachWithIndex { String line, int idx ->
            if (problems.remove(line.trim())) {
                found++
                return
            }
        }
        assert problems.empty, "Expected ${uniqueProblemsCount} unique problems, found ${found} unique problems, remaining:\n${problems.collect { " - $it" }.join("\n")}"
    }

    private void assertProblemsHtmlReport(int totalProblemCount, int uniqueProblemCount) {
        def expectReport = totalProblemCount > 0 || uniqueProblemCount > 0
        def reportDir = resolveInstantExecutionReportDirectory()
        if (expectReport) {
            assertThat("HTML report URI not found", reportDir, notNullValue())
            assertTrue("HTML report directory not found '$reportDir'", reportDir.isDirectory())
            def htmlFile = reportDir.file(PROBLEMS_REPORT_HTML_FILE_NAME)
            def jsFile = reportDir.file('instant-execution-report-data.js')
            assertTrue("HTML report HTML file not found in '$reportDir'", htmlFile.isFile())
            assertTrue("HTML report JS model not found in '$reportDir'", jsFile.isFile())
            assertThat(
                "HTML report JS model has wrong number of total problem(s)",
                numberOfProblemsIn(jsFile),
                equalTo(totalProblemCount)
            )
        } else {
            assertThat("Unexpected HTML report URI found", reportDir, nullValue())
        }
    }

    @Nullable
    protected TestFile resolveInstantExecutionReportDirectory() {
        def baseDirUri = clickableUrlFor(new File(executer.workingDir, "build/reports/instant-execution"))
        def pattern = Pattern.compile("See the complete report at (${baseDirUri}.*)$PROBLEMS_REPORT_HTML_FILE_NAME")
        def reportDirUri = relevantOutput().readLines().findResult { line ->
            def matcher = pattern.matcher(line)
            matcher.matches() ? matcher.group(1) : null
        }
        return reportDirUri ? new TestFile(Paths.get(URI.create(reportDirUri)).toFile().absoluteFile) : null
    }

    private String relevantOutput() {
        return result instanceof ExecutionFailure ? errorOutput : output
    }

    private static int numberOfProblemsIn(File jsFile) {
        newJavaScriptEngine().with {
            eval(jsFile.text)
            eval("instantExecutionProblems().length") as int
        }
    }

    protected static int numberOfProblemsWithStacktraceIn(File jsFile) {
        newJavaScriptEngine().with {
            eval(jsFile.text)
            eval("instantExecutionProblems().filter(function(problem) { return problem['error'] != null; }).length") as int
        }
    }

    private static ScriptEngine newJavaScriptEngine() {
        new ScriptEngineManager().getEngineByName("JavaScript")
    }

    private static String clickableUrlFor(File file) {
        new ConsoleRenderer().asClickableFileUrl(file)
    }

    protected void assertTestsExecuted(String testClass, String... testNames) {
        new DefaultTestExecutionResult(testDirectory)
            .testClass(testClass)
            .assertTestsExecuted(testNames)
    }
}
