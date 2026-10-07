package skillbill.engine.featuretask.runloop.output

import skillbill.config.model.RepoLocalConfig
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.lifecycle.core.AcceptingFeatureTaskRuntimeWireArtifactValidator
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeResolvedPhaseAgent
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.phaserun.InMemoryPhaseRunGoal
import skillbill.engine.featuretask.phaserun.InMemoryPhaseRunRecords
import skillbill.engine.featuretask.runloop.core.FeatureTaskRuntimeRunLoopSession
import skillbill.engine.featuretask.runloop.core.PhaseRun
import skillbill.engine.featuretask.runloop.core.RepositoryCheckpointResolutionArgs
import skillbill.engine.featuretask.runloop.qualitygate.RuntimeQualityGateCycles
import skillbill.engine.featuretask.runloop.state.FeatureTaskRuntimeRunState
import skillbill.engine.featuretask.runloop.state.coupledRunTransitionOwner
import skillbill.engine.featuretask.runner.kotlinPackWithValidationGate
import skillbill.engine.featuretask.runner.phaseDeclaration
import skillbill.engine.featuretask.slot.state.PhaseResumeRules
import skillbill.engine.featuretask.slot.state.PhaseRunRecords
import skillbill.engine.featuretask.validation.FeatureTaskRuntimeBuildGateCoordinator
import skillbill.engine.featuretask.validation.FeatureTaskRuntimeValidationGateCoordinator
import skillbill.engine.featuretask.validation.ValidationGateResolver
import skillbill.ports.config.RepoLocalConfigPort
import skillbill.ports.config.model.ReadRepoLocalConfigRequest
import skillbill.ports.config.model.ReadRepoLocalConfigResult
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.validation.ValidationGateRunner
import skillbill.ports.validation.model.ValidationGateRunRequest
import skillbill.ports.workflow.gitops.model.WorkflowGitNameListResult
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.decomposition.model.SpecSource
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeResolvedBranch
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeTransitionDeclaration
import skillbill.workflow.taskruntime.model.skeleton.PhaseStepPolicy
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeRepositoryCheckpointResolutionTest {
  private val repoRoot: Path = Files.createTempDirectory("skillbill-checkpoint-resolution")

  @AfterTest
  fun cleanUp() {
    repoRoot.toFile().deleteRecursively()
  }

  @Test
  fun `a persist miss still launches simplify with the discovered owned paths`() {
    val diagnostics = CapturingDiagnostics()
    val git =
      RecordingWorkflowGitOperations().apply {
        ownedPathsValue = listOf("src/Foo.kt", ".skill-bill/private.bin")
        headCommitShaValue = HEAD_SHA
      }
    val records =
      object : PhaseRunRecords by memoryRecords(resolvedBranch()) {
        override fun recordWorkflowOwnedPaths(
          workflowId: String,
          ownedPaths: List<String>,
        ): Boolean = false
      }

    val checkpoint = requireNotNull(resolve(git, records, diagnostics))

    assertEquals(listOf("src/Foo.kt"), checkpoint.workingTreeOwnedPaths)
    assertTrue(diagnostics.warnings.any { it.contains("could not persist workflow-owned paths") })
  }

  @Test
  fun `an unresolvable review base still yields a HEAD checkpoint`() {
    val diagnostics = CapturingDiagnostics()
    val git =
      RecordingWorkflowGitOperations().apply {
        ownedPathsValue = listOf("src/Foo.kt")
        headCommitShaValue = HEAD_SHA
        onResolveCommit = { revision ->
          if (revision == BASE_SHA) {
            WorkflowGitOperationResult.Failed(error = "unknown revision")
          } else {
            null
          }
        }
      }

    val checkpoint =
      requireNotNull(
        resolve(
          git,
          memoryRecords(resolvedBranch(reviewBaseSha = BASE_SHA)),
          diagnostics,
        ),
      )

    assertNull(checkpoint.baseRef)
    assertNotNull(checkpoint.headRef)
    assertEquals(listOf("src/Foo.kt"), checkpoint.workingTreeOwnedPaths)
    assertTrue(diagnostics.warnings.any { it.contains("could not resolve review base") })
  }

  @Test
  fun `a working-tree listing miss falls back to implement claimed paths`() {
    val diagnostics = CapturingDiagnostics()
    val git =
      RecordingWorkflowGitOperations().apply {
        ownedPathsResult = WorkflowGitNameListResult.Failed("status unreadable")
        headCommitShaValue = HEAD_SHA
      }
    val records =
      object : PhaseRunRecords by memoryRecords(resolvedBranch()) {
        override fun loadPhaseRecords(workflowId: String): Map<String, FeatureTaskRuntimePhaseRecord> =
          mapOf(
            FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT to
              FeatureTaskRuntimePhaseRecord(
                phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
                status = WorkflowStepStatus.COMPLETED,
                attemptCount = 1,
                startedAt = Instant.parse("2026-10-07T19:00:00Z"),
                resolvedAgentId = "cursor",
                fileManifestAfter = listOf("src/Main.kt"),
                fileManifestIntroduced = listOf("src/Main.kt"),
              ),
          )
      }

    val checkpoint = requireNotNull(resolve(git, records, diagnostics))

    assertEquals(listOf("src/Main.kt"), checkpoint.workingTreeOwnedPaths)
    assertTrue(diagnostics.warnings.any { it.contains("could not read the working-tree owned-path inventory") })
  }

  @Test
  fun `a committed-range listing miss still keeps working-tree owned paths`() {
    val diagnostics = CapturingDiagnostics()
    val git =
      RecordingWorkflowGitOperations().apply {
        ownedPathsValue = listOf("src/Foo.kt")
        headCommitShaValue = HEAD_SHA
        changedPathsBetweenCommitsResult = WorkflowGitNameListResult.Failed("range unreadable")
      }

    val checkpoint =
      requireNotNull(
        resolve(
          git,
          memoryRecords(resolvedBranch(reviewBaseSha = BASE_SHA)),
          diagnostics,
        ),
      )

    assertEquals(listOf("src/Foo.kt"), checkpoint.workingTreeOwnedPaths)
    assertEquals(BASE_SHA, checkpoint.baseRef)
    assertTrue(diagnostics.warnings.any { it.contains("could not list committed paths") })
  }

  @Test
  fun `a scoped fingerprint miss falls back to the whole-tree fingerprint`() {
    val diagnostics = CapturingDiagnostics()
    val git =
      RecordingWorkflowGitOperations().apply {
        ownedPathsValue = listOf("src/Foo.kt")
        headCommitShaValue = HEAD_SHA
        repositoryCheckpointFingerprintResult =
          WorkflowGitOperationResult.Failed(error = "checkpoint path escaped")
        repositoryFingerprintValue = "whole-tree-fingerprint"
      }

    val checkpoint = requireNotNull(resolve(git, memoryRecords(resolvedBranch()), diagnostics))

    assertEquals("whole-tree-fingerprint", checkpoint.fingerprint)
    assertEquals(listOf("src/Foo.kt"), checkpoint.workingTreeOwnedPaths)
    assertTrue(diagnostics.warnings.any { it.contains("falling back to the whole-tree fingerprint") })
  }

  private fun resolve(
    git: RecordingWorkflowGitOperations,
    records: PhaseRunRecords,
    diagnostics: CapturingDiagnostics,
  ) = with(FeatureTaskRuntimeRunLoopOutputVerification) {
    resolveRepositoryCheckpoint(
      RepositoryCheckpointResolutionArgs(
        recorder = records,
        goalContinuationRecorder = InMemoryPhaseRunGoal,
        gitOperations = git,
        qualityGateCycles = unusedQualityGateCycles(),
        coupledRunTransitions =
          coupledRunTransitionOwner(
            FeatureTaskRuntimeRunState(
              initialRecords = emptyMap(),
              transitions =
                FeatureTaskRuntimeTransitionDeclaration(
                  listOf(
                    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
                    FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY,
                  ),
                ),
              resumeRulesFn = { PhaseResumeRules.None },
            ),
            FeatureTaskRuntimeRunLoopSession(null, null),
          ),
        session = FeatureTaskRuntimeRunLoopSession(null, null),
        run = simplifyRun(),
        diagnostics = diagnostics,
        extendsOwnedInventory = { stepId ->
          stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT ||
            stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX ||
            stepId == FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT_IMPLEMENT_FIX
        },
      ),
    )
  }

  private fun simplifyRun(): PhaseRun =
    PhaseRun(
      phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY,
      declaration =
        phaseDeclaration(
          FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY,
          FeatureTaskRuntimeFeatureSize.MEDIUM,
          emptySet(),
        ),
      resolvedAgent =
        FeatureTaskRuntimeResolvedPhaseAgent(
          phaseId = FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_SIMPLIFY,
          invokedAgentId = "cursor",
          configuredAgentOverrideId = null,
        ),
      modelDirective = null,
      compaction = null,
      request =
        FeatureTaskRuntimeRunRequest(
          issueKey = "RMCP-1",
          workflowId = WORKFLOW_ID,
          sessionId = "sess-checkpoint",
          runInvariants =
            FeatureTaskRuntimeRunInvariants(
              specReference = "RMCP-1 mcp port",
              featureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
              acceptanceCriteria = listOf("Simplify stays inside owned paths."),
              mandatesAndOverrides = emptyList(),
              codeReviewMode = CodeReviewExecutionMode.INLINE,
            ),
          invokedAgentId = "cursor",
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
    )

  private fun memoryRecords(resolved: FeatureTaskRuntimeResolvedBranch) =
    InMemoryPhaseRunRecords(Clock.systemUTC(), resolved)

  private fun resolvedBranch(reviewBaseSha: String? = HEAD_SHA) =
    FeatureTaskRuntimeResolvedBranch(
      branch = "feat/RMCP-1-mcp-port",
      reviewBaseSha = reviewBaseSha,
    )

  private fun unusedQualityGateCycles(): RuntimeQualityGateCycles {
    val resolver = ValidationGateResolver { listOf(kotlinPackWithValidationGate()) }
    return RuntimeQualityGateCycles(
      FeatureTaskRuntimeBuildGateCoordinator(
        resolver,
        object : ValidationGateRunner {
          override fun run(request: ValidationGateRunRequest) = error("unused quality gate")
        },
        object : RepoLocalConfigPort {
          override fun readRepoLocalConfig(request: ReadRepoLocalConfigRequest) =
            ReadRepoLocalConfigResult(RepoLocalConfig.defaults())
        },
        object : RuntimeDiagnostics {
          override fun warning(
            message: String,
            error: Throwable?,
          ) = Unit

          override fun error(
            message: String,
            error: Throwable?,
          ) = Unit
        },
      ),
      AcceptingFeatureTaskRuntimeWireArtifactValidator,
      FeatureTaskRuntimeValidationGateCoordinator(),
      resolver,
    )
  }

  private class CapturingDiagnostics : RuntimeDiagnostics {
    val warnings = mutableListOf<String>()

    override fun warning(
      message: String,
      error: Throwable?,
    ) {
      warnings += message
    }

    override fun error(
      message: String,
      error: Throwable?,
    ) = Unit
  }

  private companion object {
    const val WORKFLOW_ID = "wftr-checkpoint"
    val HEAD_SHA: String = "a".repeat(40)
    val BASE_SHA: String = "b".repeat(40)
  }
}
