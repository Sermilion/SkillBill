package skillbill.infrastructure.workflow.validation

import skillbill.contracts.time.JvmSystemClock
import skillbill.infrastructure.host.jvm.testGateJvmResolver
import skillbill.infrastructure.host.scratch.testAgentRunScratchRoot
import skillbill.ports.validation.model.ValidationGateFindingParseMode
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsFormat
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsLocator
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.scaffold.model.ValidationGateExecutedWorkFormat
import skillbill.scaffold.model.ValidationGateExecutedWorkSignal
import skillbill.scaffold.model.ValidationGateFindingsFormat
import skillbill.scaffold.model.ValidationGateFindingsLocator
import skillbill.workflow.taskruntime.model.validation.ValidationGateCacheMode
import skillbill.workflow.taskruntime.model.validation.ValidationGateRunOutcome
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileSystemValidationGateRunnerExecutedChecksTest {
  @Test
  fun `gradle compile and test outputs with zero work retain stable check identities`() {
    val repo = Files.createTempDirectory("gate-executed-checks")
    try {
      val script = repo.resolve("gate.sh")
      Files.writeString(
        script,
        """
        #!/bin/sh
        mkdir -p runtime-engine/build/classes/kotlin/main runtime-engine/build/test-results/test
        printf '%s' 'compiled' > runtime-engine/build/classes/kotlin/main/Runtime.class
        printf '%s' '<testsuite tests="1" failures="0"></testsuite>' > runtime-engine/build/test-results/test/TEST-runtime.xml
        printf '%s\n' '> Task :runtime-engine:compileKotlin UP-TO-DATE'
        printf '%s\n' '> Task :runtime-engine:compileTestKotlin UP-TO-DATE'
        printf '%s\n' '> Task :runtime-engine:test UP-TO-DATE'
        printf '%s\n' '9 actionable tasks: 9 up-to-date'
        exit 0
        """.trimIndent(),
      )
      val result =
        FileSystemValidationGateRunner(JvmSystemClock, testGateJvmResolver(), testAgentRunScratchRoot()).run(
          ValidationGateRunRequest(
            repoRoot = repo,
            argv = listOf("sh", script.toString()),
            cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
            declaration = declaration(withExecutedWorkSignal = true),
            terminalVerifying = true,
            findingParseMode = ValidationGateFindingParseMode.COLLECT_ALL,
          ),
        )
      assertEquals(ValidationGateRunOutcome.PASSED, result.outcome)
      assertEquals(0, result.executedWorkUnits)
      assertTrue(Files.isRegularFile(repo.resolve("runtime-engine/build/classes/kotlin/main/Runtime.class")))
      assertTrue(Files.isRegularFile(repo.resolve("runtime-engine/build/test-results/test/TEST-runtime.xml")))
      assertEquals(
        listOf(
          "runtime-engine|compileKotlin",
          "runtime-engine|compileTestKotlin",
          "runtime-engine|test",
        ),
        result.executedCheckIdentities,
      )
    } finally {
      repo.toFile().deleteRecursively()
    }
  }

  @Test
  fun `gradle executed summary records work units with stable compile and test identities`() {
    val repo = Files.createTempDirectory("gate-executed-work")
    try {
      val script = repo.resolve("gate.sh")
      Files.writeString(
        script,
        """
        #!/bin/sh
        mkdir -p runtime-engine/build/classes/kotlin/main runtime-engine/build/test-results/test
        printf '%s' 'compiled' > runtime-engine/build/classes/kotlin/main/Runtime.class
        printf '%s' '<testsuite tests="1" failures="0"></testsuite>' > runtime-engine/build/test-results/test/TEST-runtime.xml
        printf '%s\n' '> Task :runtime-engine:compileKotlin'
        printf '%s\n' '> Task :runtime-engine:compileTestKotlin'
        printf '%s\n' '> Task :runtime-engine:test'
        printf '%s\n' '3 actionable tasks: 3 executed'
        exit 0
        """.trimIndent(),
      )
      val result =
        FileSystemValidationGateRunner(JvmSystemClock, testGateJvmResolver(), testAgentRunScratchRoot()).run(
          ValidationGateRunRequest(
            repoRoot = repo,
            argv = listOf("sh", script.toString()),
            cacheMode = ValidationGateCacheMode.CACHE_ELIGIBLE,
            declaration = declaration(withExecutedWorkSignal = true),
            terminalVerifying = true,
            findingParseMode = ValidationGateFindingParseMode.COLLECT_ALL,
          ),
        )
      assertEquals(ValidationGateRunOutcome.PASSED, result.outcome)
      assertEquals(3, result.executedWorkUnits)
      assertEquals(
        listOf(
          "runtime-engine|compileKotlin",
          "runtime-engine|compileTestKotlin",
          "runtime-engine|test",
        ),
        result.executedCheckIdentities,
      )
    } finally {
      repo.toFile().deleteRecursively()
    }
  }

  private fun declaration(withExecutedWorkSignal: Boolean): ValidationGateDeclaration =
    ValidationGateDeclaration(
      fullGateCommand = listOf("sh", "gate.sh"),
      cacheBypassingFullGateCommand = listOf("sh", "gate.sh", "--rerun-tasks"),
      collectAllFullGateCommand = listOf("sh", "gate.sh", "--continue"),
      cacheBypassingCollectAllFullGateCommand = listOf("sh", "gate.sh", "--continue", "--rerun-tasks"),
      findings =
        ValidationGateFindingsLocator(
          format = ValidationGateFindingsFormat.JUNIT_XML,
          artifactGlobs = listOf("**/build/test-results/**/*.xml"),
          compilerDiagnostics =
            ValidationGateCompilerDiagnosticsLocator(
              ValidationGateCompilerDiagnosticsFormat.GRADLE_KOTLIN_COMPILER_STDOUT,
            ),
          executedWork =
            if (withExecutedWorkSignal) {
              ValidationGateExecutedWorkSignal(ValidationGateExecutedWorkFormat.GRADLE_ACTIONABLE_SUMMARY)
            } else {
              null
            },
        ),
    )
}
