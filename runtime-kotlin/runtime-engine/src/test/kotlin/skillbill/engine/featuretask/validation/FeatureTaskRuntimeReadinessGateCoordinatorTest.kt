package skillbill.engine.featuretask.validation

import skillbill.application.decomposition.baseBranch
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.lifecycle.branch.Blocked
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeGoalContinuationContext
import skillbill.engine.featuretask.phase.record.FeatureTaskRuntimeReadinessEvidencePort
import skillbill.engine.featuretask.runloop.core.strategySelectionFacts
import skillbill.engine.featuretask.slot.statusProjectionPhaseStrategies
import skillbill.infrastructure.workflow.github.GitHubPullRequestCheckDiscovery
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.validation.PrCheckDiscovery
import skillbill.ports.validation.PrCheckProcessRunner
import skillbill.ports.validation.model.PrCheckDiscoveryResult
import skillbill.ports.validation.model.PrCheckRunResult
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.gitops.model.ReadinessTreeIdentity
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeReadinessEvidence
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FeatureTaskRuntimeReadinessGateCoordinatorTest {
  @Test
  fun `Detekt edit after validate does not select a pack collect-all check`() {
    val selection =
      ReadinessCheckSelection(
        GitHubPullRequestCheckDiscovery(),
      )
    val selected =
      assertIs<ReadinessCheckSelectionResult.Selected>(
        selection.select(validationGateTestRepoRoot, listOf("runtime-kotlin/runtime-engine/Foo.kt")),
      ).checks
    val invalidated =
      selection.invalidatedCheckIds(
        selected,
        listOf("runtime-kotlin/runtime-engine/DetektEdit.kt"),
      )
    assertTrue(selected.none { it.checkId == READINESS_PACK_COLLECT_ALL_CHECK_ID })
    assertTrue(READINESS_PACK_COLLECT_ALL_CHECK_ID !in invalidated)
  }

  @Test
  fun `goal child commit_push omits pr phase but still lists commit_push`() {
    val request =
      minimalRequest().copy(
        goalContinuation =
          FeatureTaskRuntimeGoalContinuationContext(
            parentIssueKey = "SKILL-364",
            subtaskId = 1,
            subtaskName = "readiness",
            goalBranch = "feat/skill-364",
            parentWorkflowId = "parent-wf",
            suppressPr = true,
            reviewBaseline = GoalSubtaskReviewBaseline("0".repeat(40), emptyList()),
          ),
      )
    val phases =
      statusProjectionPhaseStrategies().executionPlan(
        strategySelectionFacts(request),
      ).traversal.forwardPhaseIds
    assertFalse(phases.contains(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PR))
    assertTrue(phases.contains(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_COMMIT_PUSH))
  }

  @Test
  fun `commit_push stays ready when the validate source tree fingerprint drifted`() {
    val captured = ReadinessTreeIdentity("1".repeat(40), "b".repeat(40), "c".repeat(40))
    val current = captured.copy(sourceTreeSha = "2".repeat(40))
    val store =
      MemoryReadinessEvidence().apply {
        persistReadinessEvidence(
          WORKFLOW_ID,
          FeatureTaskRuntimeReadinessEvidence(
            sourceTreeSha = captured.sourceTreeSha,
            baseRefSha = captured.baseRefSha,
            headSha = captured.headSha,
            selectedChecks = emptyList(),
            checkResults = emptyList(),
          ),
        )
      }
    val result = coordinator(store).settleBeforeCommitPush(settleRequest(current))

    assertIs<ReadinessCommitPushSettleResult.Ready>(result)
    assertEquals(current.sourceTreeSha, store.stored?.sourceTreeSha)
  }

  @Test
  fun `commit_push stays ready when HEAD identity drifted after a local commit`() {
    val captured = ReadinessTreeIdentity("1".repeat(40), "b".repeat(40), "c".repeat(40))
    val current = captured.copy(sourceTreeSha = "2".repeat(40), headSha = "d".repeat(40))
    val store =
      MemoryReadinessEvidence().apply {
        persistReadinessEvidence(
          WORKFLOW_ID,
          FeatureTaskRuntimeReadinessEvidence(
            sourceTreeSha = captured.sourceTreeSha,
            baseRefSha = captured.baseRefSha,
            headSha = captured.headSha,
            selectedChecks = emptyList(),
            checkResults = emptyList(),
          ),
        )
      }
    val result = coordinator(store).settleBeforeCommitPush(settleRequest(current))

    assertIs<ReadinessCommitPushSettleResult.Ready>(result)
    assertEquals(current.headSha, store.stored?.headSha)
    assertEquals(current.sourceTreeSha, store.stored?.sourceTreeSha)
  }

  @Test
  fun `commit_push still blocks when the base ref identity drifted`() {
    val captured = ReadinessTreeIdentity("1".repeat(40), "b".repeat(40), "c".repeat(40))
    val current = captured.copy(baseRefSha = "d".repeat(40))
    val store =
      MemoryReadinessEvidence().apply {
        persistReadinessEvidence(
          WORKFLOW_ID,
          FeatureTaskRuntimeReadinessEvidence(
            sourceTreeSha = captured.sourceTreeSha,
            baseRefSha = captured.baseRefSha,
            headSha = captured.headSha,
            selectedChecks = emptyList(),
            checkResults = emptyList(),
          ),
        )
      }
    val result = coordinator(store).settleBeforeCommitPush(settleRequest(current))

    val blocked = assertIs<ReadinessCommitPushSettleResult.Blocked>(result)
    assertTrue(blocked.reason.contains("Readiness identity is stale"))
  }

  @Test
  fun `bind after commit adopts a drifted source tree fingerprint`() {
    val captured = ReadinessTreeIdentity("1".repeat(40), "b".repeat(40), "c".repeat(40))
    val committed = captured.copy(sourceTreeSha = "2".repeat(40), headSha = "e".repeat(40))
    val store =
      MemoryReadinessEvidence().apply {
        persistReadinessEvidence(
          WORKFLOW_ID,
          FeatureTaskRuntimeReadinessEvidence(
            sourceTreeSha = captured.sourceTreeSha,
            baseRefSha = captured.baseRefSha,
            headSha = captured.headSha,
            selectedChecks = emptyList(),
            checkResults = emptyList(),
          ),
        )
      }
    val result =
      coordinator(store).bindCommittedHead(
        ReadinessCommittedHeadBindRequest(
          workflowId = WORKFLOW_ID,
          stepId = "commit_push",
          repoRoot = validationGateTestRepoRoot,
          baseBranch = "main",
          gitOperations = RecordingWorkflowGitOperations().apply { readinessTreeIdentity = committed },
          commitSha = committed.headSha,
        ),
      )

    assertIs<ReadinessCommitPushSettleResult.Ready>(result)
    assertEquals(committed.sourceTreeSha, store.stored?.sourceTreeSha)
    assertEquals(committed.headSha, store.stored?.headSha)
  }
}

private const val WORKFLOW_ID = "wf-readiness-fingerprint"

internal fun coordinator(store: FeatureTaskRuntimeReadinessEvidencePort): FeatureTaskRuntimeReadinessGateCoordinator =
  FeatureTaskRuntimeReadinessGateCoordinator(
    ReadinessCheckSelection(
      object : PrCheckDiscovery {
        override fun discoverPullRequestChecks(repoRoot: Path): PrCheckDiscoveryResult =
          PrCheckDiscoveryResult.Discovered(emptyList())
      },
    ),
    object : PrCheckProcessRunner {
      override fun run(
        command: String,
        repoRoot: Path,
      ): PrCheckRunResult = error("readiness must not execute checks when none are selected")
    },
    store,
    NoopRuntimeDiagnostics,
  )

private fun settleRequest(identity: ReadinessTreeIdentity): ReadinessCommitPushSettleRequest =
  ReadinessCommitPushSettleRequest(
    workflowId = WORKFLOW_ID,
    stepId = "commit_push",
    repoRoot = validationGateTestRepoRoot,
    baseBranch = "main",
    changedPaths = listOf("core/application/src/main/kotlin/news/readian/core/application/api/ApiValidationError.kt"),
    gitOperations = RecordingWorkflowGitOperations().apply { readinessTreeIdentity = identity },
  )

private class MemoryReadinessEvidence : FeatureTaskRuntimeReadinessEvidencePort {
  var stored: FeatureTaskRuntimeReadinessEvidence? = null

  override fun loadReadinessEvidence(workflowId: String): FeatureTaskRuntimeReadinessEvidence? = stored

  override fun persistReadinessEvidence(
    workflowId: String,
    evidence: FeatureTaskRuntimeReadinessEvidence,
  ) {
    stored = evidence
  }
}
