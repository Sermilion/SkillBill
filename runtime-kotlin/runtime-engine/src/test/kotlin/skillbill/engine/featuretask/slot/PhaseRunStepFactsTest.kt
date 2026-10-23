package skillbill.engine.featuretask.slot

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeResolvedPhaseAgent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.model.execution.QaExecutionContext
import skillbill.engine.featuretask.model.execution.QaExecutionTarget
import skillbill.engine.featuretask.model.execution.QaSkipReason
import skillbill.engine.featuretask.phaserun.qa.InMemoryQaWorkspaces
import skillbill.engine.featuretask.qa.report.qaTestExpected
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runner.phaseDeclaration
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PhaseRunStepFactsTest {
  private val base: Path = Files.createTempDirectory("skillbill-step-facts")
  private val checkoutRoot: Path = base.resolve("checkout")
  private val storeRoot: Path = base.resolve("project-store")

  init {
    Files.createDirectories(checkoutRoot)
    Files.createDirectories(storeRoot)
  }

  @AfterTest
  fun cleanUp() {
    base.toFile().deleteRecursively()
  }

  @Test
  fun `a run grants the project store root it carries rather than a checkout path`() {
    val facts = run(checkoutRoot).stepFacts("SKILL-441", attempt = 1)

    assertEquals(listOf(storeRoot), facts.additionalWritableDirectories)
    assertEquals(checkoutRoot, facts.repoRoot)
    assertFalse(facts.additionalWritableDirectories.any { it.startsWith(checkoutRoot) })
  }

  @Test
  fun `a QA execution adds its workspace after the project store root`() {
    val workspace = InMemoryQaWorkspaces(base.resolve("qa")).open(checkoutRoot, "qa-invocation")
    val qa =
      QaExecutionContext(
        workspace,
        qaTestExpected("qa-invocation"),
        QaExecutionTarget.Skipped(QaSkipReason.UNSUPPORTED_TARGET, "unused target guidance"),
      )

    val facts = run(checkoutRoot, qa).stepFacts("SKILL-441", attempt = 1)

    assertEquals(listOf(storeRoot, workspace.directory), facts.additionalWritableDirectories)
  }

  @Test
  fun `a QA workspace that is the project store root is listed once`() {
    val workspace = InMemoryQaWorkspaces(storeRoot.parent).open(checkoutRoot, storeRoot.fileName.toString())
    val qa =
      QaExecutionContext(
        workspace,
        qaTestExpected("qa-invocation"),
        QaExecutionTarget.Skipped(QaSkipReason.UNSUPPORTED_TARGET, "unused target guidance"),
      )

    val facts = run(checkoutRoot, qa).stepFacts("SKILL-441", attempt = 1)

    assertEquals(listOf(storeRoot), facts.additionalWritableDirectories)
  }

  private fun run(
    repoRoot: Path,
    qa: QaExecutionContext? = null,
  ): PhaseRun =
    PhaseRun(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      declaration =
        phaseDeclaration(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
          FeatureTaskRuntimeFeatureSize.MEDIUM,
          emptySet(),
        ),
      resolvedAgent =
        FeatureTaskRuntimeResolvedPhaseAgent(
          phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
          invokedAgentId = "claude",
          configuredAgentOverrideId = null,
        ),
      modelDirective = null,
      compaction = null,
      request =
        FeatureTaskRuntimeRunRequest(
          issueKey = "SKILL-441",
          workflowId = "wftr-step-facts",
          sessionId = "sess-step-facts",
          runInvariants =
            FeatureTaskRuntimeRunInvariants(
              specReference = "SKILL-441 project store",
              featureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
              acceptanceCriteria = listOf("The agent may write the project store."),
              mandatesAndOverrides = emptyList(),
              codeReviewMode = CodeReviewExecutionMode.INLINE,
            ),
          invokedAgentId = "claude",
          repoRoot = repoRoot,
        ),
      specSource = SpecSource.LOCAL,
      policy =
        PhaseStepPolicy(
          mutating = true,
          singleAgentSession = false,
          readOnlyIdle = false,
          fileMutating = true,
          generationScoped = false,
        ),
      projectStoreRoot = storeRoot,
      qaExecution = qa,
    )
}
