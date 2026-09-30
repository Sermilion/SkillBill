package skillbill.engine.featuretask.lifecycle.core

import skillbill.engine.featuretask.slot.audit.AcceptanceAuditPromptSections
import skillbill.engine.featuretask.slot.implementation.ImplementationPromptSections
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class FeatureTaskRuntimeAuditTestExclusionTest {
  @Test
  fun `audit inspects without compiling and excludes test execution`() {
    val repairAndAudit =
      listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
      ).zip(listOf(ImplementationPromptSections.IMPLEMENT_DIRECTIVE, AcceptanceAuditPromptSections.DIRECTIVE))

    repairAndAudit.forEach { (phaseId, directive) ->
      BUILD_AND_TEST_COMMANDS.forEach { command ->
        assertTrue(
          !directive.contains(command),
          "the $phaseId directive must not instruct a $command invocation as repair or audit evidence",
        )
      }
    }

    assertContains(
      ImplementationPromptSections.IMPLEMENT_DIRECTIVE,
      "do not run builds or tests here",
    )
    val auditDirective = AcceptanceAuditPromptSections.DIRECTIVE
    assertContains(auditDirective, "without editing it")
    assertTrue(!auditDirective.contains("validation_gate.build_command"), "audit must not grant a compile exception")
    assertTrue(!auditDirective.contains("application compiles"), "audit must not grant a compile exception")
    assertContains(auditDirective, "Run no compile, build, test, format, lint, or full-check command")
    assertContains(auditDirective, "validate owns tests and failures")
    assertContains(auditDirective, "gaps")
  }

  private companion object {
    val BUILD_AND_TEST_COMMANDS = listOf("./gradlew", "gradlew check", "npm ", "npx ", "pytest", "cargo test")
  }
}
