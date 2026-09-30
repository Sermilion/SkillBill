package skillbill.engine.featuretask.validation

import skillbill.engine.featuretask.model.execution.ValidationGateCyclePhase
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsFormat.GRADLE_KOTLIN_COMPILER_STDOUT
import skillbill.scaffold.model.ValidationGateCompilerDiagnosticsLocator
import skillbill.scaffold.model.ValidationGateDeclaration
import skillbill.scaffold.model.ValidationGateExecutedWorkFormat
import skillbill.scaffold.model.ValidationGateExecutedWorkSignal
import skillbill.scaffold.model.ValidationGateFindingsFormat
import skillbill.scaffold.model.ValidationGateFindingsLocator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureTaskRuntimeBuildGateSelectionTest {
  private val declaration =
    ValidationGateDeclaration(
      fullGateCommand = listOf("echo", "check"),
      cacheBypassingFullGateCommand = listOf("echo", "check-full"),
      collectAllFullGateCommand = listOf("echo", "collect-all"),
      cacheBypassingCollectAllFullGateCommand = listOf("echo", "collect-all-full"),
      buildCommand = listOf("echo", "build"),
      cacheBypassingBuildCommand = listOf("echo", "build-full"),
      findings =
        ValidationGateFindingsLocator(
          format = ValidationGateFindingsFormat.JUNIT_XML,
          artifactGlobs = listOf("**/*.xml"),
          compilerDiagnostics =
            ValidationGateCompilerDiagnosticsLocator(
              GRADLE_KOTLIN_COMPILER_STDOUT,
            ),
          executedWork = ValidationGateExecutedWorkSignal(ValidationGateExecutedWorkFormat.GRADLE_ACTIONABLE_SUMMARY),
        ),
    )

  @Test
  fun `build gate argv never equals collect-all or full gate commands`() {
    assertEquals(
      listOf("echo", "build"),
      buildGateArgv(declaration, ValidationGateCyclePhase.INITIAL_DISCOVERY),
    )
    assertEquals(
      listOf("echo", "build-full"),
      buildGateArgv(declaration, ValidationGateCyclePhase.POST_REPAIR_VERIFY),
    )
    for (phase in ValidationGateCyclePhase.entries) {
      val argv = buildGateArgv(declaration, phase)
      assertTrue(argv != declaration.collectAllFullGateCommand)
      assertTrue(argv != declaration.cacheBypassingCollectAllFullGateCommand)
      assertTrue(argv != declaration.fullGateCommand)
      assertTrue(argv != declaration.cacheBypassingFullGateCommand)
    }
  }
}
