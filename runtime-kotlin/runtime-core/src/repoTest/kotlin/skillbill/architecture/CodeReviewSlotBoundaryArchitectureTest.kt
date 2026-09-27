package skillbill.architecture

import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeReviewSlotBoundaryArchitectureTest {
  @Test
  fun `code_review slot reaches run state only through PhaseRunState and launches only through PhaseRunner`() {
    val root = ArchitectureScanSupport.runtimeRoot.resolve(CODE_REVIEW_SLOT_ROOT)
    val files = kotlinFilesUnderWithArchitectureAsserts(root)
    assertTrue(files.any { it.name == "InlineReviewStrategy.kt" }, "The scan did not read InlineReviewStrategy.kt.")

    val violations =
      files.flatMap { path -> codeReviewSlotViolations(root.relativize(path).toString(), path.readText()) }

    assertEquals(emptyList(), violations, violations.joinToString("\n"))
  }

  @Test
  fun `code_review slot guard catches plain, aliased, star and inline qualified references`() {
    val source =
      """
      package skillbill.engine.featuretask.slot.codereview

      import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
      import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState as RunState
      import skillbill.engine.featuretask.phase.record.*
      import skillbill.ports.goalrunner.runner.*
      import skillbill.engine.featuretask.runloop.core.PhaseRun
      import skillbill.engine.featuretask.slot.PhaseRunState

      internal class Synthetic(
        private val writer: skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence,
        private val git: skillbill.ports.workflow.gitops.CheckpointHistoryGitOperations,
      )
      """.trimIndent()

    assertEquals(
      listOf(
        "Synthetic.kt imports skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher",
        "Synthetic.kt imports skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState",
        "Synthetic.kt imports skillbill.engine.featuretask.phase.record.*",
        "Synthetic.kt imports skillbill.ports.goalrunner.runner.*",
        "Synthetic.kt references skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence",
        "Synthetic.kt references skillbill.ports.workflow.gitops.CheckpointHistoryGitOperations",
      ),
      codeReviewSlotViolations("Synthetic.kt", source),
    )
  }

  private fun codeReviewSlotViolations(
    relativePath: String,
    source: String,
  ): List<String> {
    val code = CommentStripper(source, blankStringLiterals = true).strip()
    val imports =
      IMPORT_PATTERN.findAll(code).mapNotNull { match ->
        val target = match.groupValues[1]
        val forbidden =
          if (target.endsWith(".*")) forbiddenPackage(target.removeSuffix(".*")) else forbiddenType(target)
        "$relativePath imports $target".takeIf { forbidden }
      }
    val body = code.lineSequence().filterNot { IMPORT_OR_PACKAGE_LINE.matches(it) }.joinToString("\n")
    val qualified =
      QUALIFIED_REFERENCE.findAll(body).map { it.value }.filter(::forbiddenType).map {
        "$relativePath references $it"
      }
    return (imports + qualified).toList()
  }

  private fun forbiddenType(fqn: String): Boolean =
    FORBIDDEN_TYPES.any { fqn == it || fqn.startsWith("$it.") } ||
      FORBIDDEN_PACKAGE_PREFIXES.any { fqn.startsWith(it) } ||
      fqn.startsWith(RUN_LOOP_PREFIX) && ALLOWED_RUN_LOOP_TYPES.none { fqn == it || fqn.startsWith("$it.") }

  private fun forbiddenPackage(packageName: String): Boolean =
    FORBIDDEN_TYPES.any { it.substringBeforeLast('.') == packageName } ||
      FORBIDDEN_PACKAGE_PREFIXES.any { "$packageName.".startsWith(it) } ||
      "$packageName.".startsWith(RUN_LOOP_PREFIX)

  private companion object {
    const val CODE_REVIEW_SLOT_ROOT =
      "runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/slot/codereview"
    const val RUN_LOOP_PREFIX = "skillbill.engine.featuretask.runloop."

    val IMPORT_PATTERN = Regex("""^\s*import\s+(\w+(?:\.\w+)*(?:\.\*)?)""", RegexOption.MULTILINE)
    val IMPORT_OR_PACKAGE_LINE = Regex("""^\s*(?:import|package)\s+.*""")
    val QUALIFIED_REFERENCE = Regex("""\bskillbill(?:\.\w+)+""")

    val FORBIDDEN_TYPES =
      setOf(
        "skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher",
        "skillbill.engine.featuretask.lifecycle.continuation.FeatureTaskRuntimeGoalContinuationRecorder",
        "skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence",
        "skillbill.ports.workflow.gitops.CheckpointHistoryGitOperations",
      )
    val FORBIDDEN_PACKAGE_PREFIXES =
      setOf(
        "skillbill.engine.featuretask.phase.record.",
        "skillbill.engine.featuretask.lifecycle.checkpoint.",
      )
    val ALLOWED_RUN_LOOP_TYPES =
      setOf(
        "skillbill.engine.featuretask.runloop.core.PhaseRun",
        "skillbill.engine.featuretask.runloop.core.PhaseOutcome",
      )
  }
}
