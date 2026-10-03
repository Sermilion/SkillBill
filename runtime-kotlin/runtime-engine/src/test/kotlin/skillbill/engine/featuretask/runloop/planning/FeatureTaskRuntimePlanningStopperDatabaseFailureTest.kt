package skillbill.engine.featuretask.runloop.planning

import skillbill.application.TestDecompositionManifestStore
import skillbill.application.testDecompositionManifestValidator
import skillbill.application.testDecompositionManifestWriter
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunFacts
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.phase.planning.FeatureTaskRuntimeDecompositionPlanner
import skillbill.engine.featuretask.phaserun.InMemoryPhaseRunRecords
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationRuntime
import skillbill.engine.featuretask.prepare.FeatureSpecPreparationWriter
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.runloop.state.coupledRunTransitionOwner
import skillbill.engine.featuretask.runner.writePlanBundle
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.error.core.databaseBusy
import skillbill.infrastructure.workflow.filesystem.FileSystemFeatureSpecPathResolver
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeDecomposeTerminal
import skillbill.workflow.taskruntime.model.handoff.assembly.FeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class FeatureTaskRuntimePlanningStopperDatabaseFailureTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-planning-stop-db-failure")

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
  }

  @Test
  fun `a busy database while persisting the decompose terminal propagates instead of blocking planning`() {
    writePlanBundle(repoRoot, ISSUE_KEY)
    val busy = databaseBusy(IllegalStateException("[SQLITE_BUSY] The database file is locked (database is locked)"))
    val records =
      object : PhaseRunRecords by InMemoryPhaseRunRecords(Clock.systemUTC(), null) {
        override fun recordDecomposeTerminal(
          workflowId: String,
          terminal: FeatureTaskRuntimeDecomposeTerminal,
          planStepId: String,
        ): Boolean = throw busy
      }
    val progress =
      FeatureTaskRuntimeRunState(
        initialRecords = emptyMap(),
        transitions = FeatureTaskRuntimeTransitionDeclaration(listOf(PLAN)),
        resumeRulesFn = { PhaseResumeRules.None },
      )
    val stopper =
      FeatureTaskRuntimePlanningStopper(
        decompositionPlanner(),
        records,
        NoopRuntimeDiagnostics,
        coupledRunTransitionOwner(progress, FeatureTaskRuntimeRunLoopSession(null, null)),
      )

    val thrown =
      assertFailsWith<Throwable> {
        stopper.resolve(
          request = specBundleRequest(),
          completedOutput = FeatureTaskRuntimePhaseOutput(PLAN, 1, "{}"),
          completedPhaseIds = listOf(PLAN),
          resolvedBranch = null,
        )
      }

    assertSame(busy, thrown)
  }

  private fun decompositionPlanner(): FeatureTaskRuntimeDecompositionPlanner =
    FeatureTaskRuntimeDecompositionPlanner(
      preparationRuntime = FeatureSpecPreparationRuntime(),
      preparationWriter =
        FeatureSpecPreparationWriter(
          decompositionManifestValidator = testDecompositionManifestValidator,
          fileStore = TestDecompositionManifestStore,
          decompositionManifestWriter = testDecompositionManifestWriter,
        ),
      specPathResolver = FileSystemFeatureSpecPathResolver(),
    )

  private fun specBundleRequest(): FeatureTaskRuntimeRunFacts {
    val request =
      FeatureTaskRuntimeRunRequest(
        issueKey = ISSUE_KEY,
        workflowId = "wfl-planning-stop",
        sessionId = "ftr-planning-stop",
        runInvariants =
          FeatureTaskRuntimeRunInvariants(
            specReference = "$ISSUE_KEY split the runtime work into ordered subtasks",
            featureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
            acceptanceCriteria = listOf("Plan authors a governed spec bundle."),
            mandatesAndOverrides = emptyList(),
            codeReviewMode = CodeReviewExecutionMode.INLINE,
          ),
        invokedAgentId = "claude",
        repoRoot = repoRoot,
      )
    return object : FeatureTaskRuntimeRunFacts by request {
      override val specBundleRequired: Boolean = true
    }
  }

  private companion object {
    const val ISSUE_KEY = "SKILL-901"
    const val PLAN = "plan"
  }
}
