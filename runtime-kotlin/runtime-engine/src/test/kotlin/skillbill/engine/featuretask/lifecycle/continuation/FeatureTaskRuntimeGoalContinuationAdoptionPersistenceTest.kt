package skillbill.engine.featuretask.lifecycle.continuation

import skillbill.application.testHarnessClock
import skillbill.application.testWorkflowSnapshotValidator
import skillbill.contracts.JsonCodec
import skillbill.engine.featuretask.lifecycle.core.AcceptingFeatureTaskRuntimeWireArtifactValidator
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeGoalContinuationContext
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimePreparation
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunRequest
import skillbill.engine.featuretask.persist.FeatureTaskRuntimeWorkflowPersistence
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecorder
import skillbill.engine.featuretask.runloop.durable.FeatureTaskRuntimeRunInvariantsStore
import skillbill.engine.featuretask.runloop.durable.FeatureTaskRuntimeRunPreparation
import skillbill.engine.featuretask.runloop.observability.continuation
import skillbill.engine.featuretask.runner.InMemoryRuntimeWorkflowRepository
import skillbill.engine.featuretask.runner.RuntimeFakeDatabaseSessionFactory
import skillbill.engine.featuretask.slot.statusProjectionPhaseStrategies
import skillbill.error.shellcontent.InvalidWorkflowStateSchemaError
import skillbill.ports.diagnostics.NoopRuntimeDiagnostics
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.gitops.model.GoalSubtaskReviewBaseline
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.ports.workflow.model.toSnapshot
import skillbill.ports.workflow.toRecord
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.engine.model.WorkflowUpdateInput
import skillbill.workflow.model.FeatureTaskWorkflowMode.RUNTIME
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.model.WorkflowStatus
import skillbill.workflow.model.goalreview.GoalSubtaskReviewState
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.persistence.goalContinuationArtifact
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection.BUILD
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection.VALIDATE
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.GOAL_SUBTASK_REVIEW_STATE.label()
private val FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.label()
private val FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION_ARTIFACT_KEY =
  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION.label()

class FeatureTaskRuntimeGoalContinuationAdoptionPersistenceTest {
  private val workflowId = "wftr-skill176-adopt-1"
  private val baselineSha = "a".repeat(40)

  @Test
  fun `engine artifact patch rejects malformed payload before persistence`() {
    val harness = seedHarness(preContractContinuationMap())
    val before = requireNotNull(harness.repository.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME))
    val persistence =
      FeatureTaskRuntimeWorkflowPersistence(
        RuntimeFakeDatabaseSessionFactory(harness.repository),
        object : WorkflowSnapshotValidator {
          override fun validate(
            snapshot: WorkflowStateSnapshot,
            slug: String,
          ) {
            DurableWorkflowArtifacts.fromMap(snapshot.artifacts).goalContinuationArtifact()
          }
        },
      )

    assertFailsWith<InvalidWorkflowStateSchemaError> {
      persistence.persistArtifactsPatch(
        harness.repository,
        before.toSnapshot(),
        mapOf(
          FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to
            preContractContinuationMap().plus("subtask_id" to 2.7),
        ),
      )
    }

    assertEquals(before, harness.repository.getFeatureTaskWorkflowAsMode(workflowId, RUNTIME))
  }

  @Test
  fun `malformed durable continuation blocks engine preparation instead of becoming no child`() {
    val harness =
      seedHarness(
        continuationMap = preContractContinuationMap().plus("subtask_id" to 2.7),
      )

    val prepared = harness.prepare(resumeRequest(validationDepth = ValidationDepth.FULL))

    val blocked = assertIs<FeatureTaskRuntimePreparation.PreparationBlocked>(prepared)
    assertTrue(blocked.report.blockedReason.contains("malformed"))
    assertTrue(blocked.report.blockedReason.contains("subtask_id"))
  }

  @Test
  fun `resume from durable map missing validation_depth adopts supplied depth and records evidence`() {
    val harness =
      seedHarness(
        continuationMap = preContractContinuationMap(includeValidationDepth = null),
      )

    val prepared =
      assertIs<FeatureTaskRuntimePreparation.Prepared>(
        harness.prepare(resumeRequest(validationDepth = ValidationDepth.FULL)),
      )

    assertEquals(ValidationDepth.FULL, prepared.request.goalContinuation?.validationDepth)
    val artifacts = harness.repository.taskRuntimeArtifacts(workflowId)
    val continuation =
      requireNotNull(
        JsonCodec.anyToStringAnyMap(artifacts[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY]),
      )
    assertEquals("full", continuation["validation_depth"])
    val adoption =
      requireNotNull(
        JsonCodec.anyToStringAnyMap(artifacts[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION_ARTIFACT_KEY]),
      )
    assertEquals("validation_depth", adoption["field"])
    assertEquals("full", adoption["adopted_value"])
    assertTrue(
      (adoption["reason"] as String).contains("predated the validation_depth contract"),
      "adoption evidence must record why the heal happened",
    )
  }

  @Test
  fun `resume from durable map missing quality_gate_selection resolves validate and records evidence`() {
    val harness =
      seedHarness(
        continuationMap =
          preContractContinuationMap(
            includeValidationDepth = "full",
            includeQualityGateSelection = null,
          ),
      )

    val prepared =
      assertIs<FeatureTaskRuntimePreparation.Prepared>(
        harness.prepare(
          resumeRequest(
            validationDepth = ValidationDepth.FULL,
            qualityGateSelection = BUILD,
          ),
        ),
      )

    assertEquals(
      VALIDATE,
      prepared.request.goalContinuation?.qualityGateSelection,
    )
    val artifacts = harness.repository.taskRuntimeArtifacts(workflowId)
    val continuation =
      requireNotNull(
        JsonCodec.anyToStringAnyMap(artifacts[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY]),
      )
    assertEquals("validate", continuation["quality_gate_selection"])
    val adoption =
      requireNotNull(
        JsonCodec.anyToStringAnyMap(artifacts[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION_ARTIFACT_KEY]),
      )
    assertEquals("quality_gate_selection", adoption["field"])
    assertEquals("validate", adoption["adopted_value"])
    assertTrue(
      (adoption["reason"] as String).contains("resolved legacy selection to validate"),
    )
  }

  @Test
  fun `resume with equal recorded validation_depth proceeds without adoption evidence`() {
    val harness =
      seedHarness(
        continuationMap =
          preContractContinuationMap(
            includeValidationDepth = "full",
            includeQualityGateSelection = "validate",
          ),
      )

    val prepared =
      assertIs<FeatureTaskRuntimePreparation.Prepared>(
        harness.prepare(resumeRequest(validationDepth = ValidationDepth.FULL)),
      )

    assertEquals(ValidationDepth.FULL, prepared.request.goalContinuation?.validationDepth)
    val artifacts = harness.repository.taskRuntimeArtifacts(workflowId)
    assertNull(artifacts[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION_ARTIFACT_KEY])
    val continuation =
      requireNotNull(
        JsonCodec.anyToStringAnyMap(artifacts[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY]),
      )
    assertEquals("full", continuation["validation_depth"])
  }

  @Test
  fun `resume with recorded quality_gate_selection build preserves build on durable artifact`() {
    val harness =
      seedHarness(
        continuationMap =
          preContractContinuationMap(
            includeValidationDepth = "full",
            includeQualityGateSelection = "build",
          ),
      )

    val prepared =
      assertIs<FeatureTaskRuntimePreparation.Prepared>(
        harness.prepare(
          resumeRequest(
            validationDepth = ValidationDepth.FULL,
            qualityGateSelection = BUILD,
          ),
        ),
      )

    assertEquals(
      BUILD,
      prepared.request.goalContinuation?.qualityGateSelection,
    )
    val artifacts = harness.repository.taskRuntimeArtifacts(workflowId)
    assertNull(artifacts[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_FIELD_ADOPTION_ARTIFACT_KEY])
    val continuation =
      requireNotNull(
        JsonCodec.anyToStringAnyMap(artifacts[FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY]),
      )
    assertEquals("build", continuation["quality_gate_selection"])
  }

  private fun preContractContinuationMap(
    includeValidationDepth: String? = null,
    includeQualityGateSelection: String? = null,
  ): Map<String, Any?> =
    linkedMapOf<String, Any?>(
      "issue_key" to "SKILL-176",
      "subtask_id" to 1,
      "suppress_pr" to true,
      "goal_branch" to "feat/SKILL-176",
      "parent_workflow_id" to "wfl-parent",
      "code_review_mode" to "inline",
    ).apply {
      includeValidationDepth?.let { put("validation_depth", it) }
      includeQualityGateSelection?.let { put("quality_gate_selection", it) }
    }

  private fun resumeRequest(
    validationDepth: ValidationDepth,
    qualityGateSelection: FeatureTaskRuntimeQualityGateSelection =
      VALIDATE,
  ): FeatureTaskRuntimeRunRequest =
    FeatureTaskRuntimeRunRequest(
      issueKey = "SKILL-176",
      workflowId = workflowId,
      sessionId = "fis-176",
      runInvariants =
        FeatureTaskRuntimeRunInvariants(
          specReference = ".feature-specs/SKILL-176/spec.md",
          featureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
          acceptanceCriteria = listOf("AC-001"),
          mandatesAndOverrides = emptyList(),
          codeReviewMode = CodeReviewExecutionMode.INLINE,
        ),
      invokedAgentId = "claude",
      repoRoot = Path.of("/tmp/skillbill-skill-176"),
      goalContinuation =
        FeatureTaskRuntimeGoalContinuationContext(
          parentIssueKey = "SKILL-176",
          subtaskId = 1,
          goalBranch = "feat/SKILL-176",
          suppressPr = true,
          parentWorkflowId = "wfl-parent",
          codeReviewMode = CodeReviewExecutionMode.INLINE,
          validationDepth = validationDepth,
          qualityGateSelection = qualityGateSelection,
          reviewBaseline = GoalSubtaskReviewBaseline(baselineSha, emptyList()),
        ),
    )

  private fun seedHarness(continuationMap: Map<String, Any?>): AdoptionHarness {
    val repository = InMemoryRuntimeWorkflowRepository()
    val engine = WorkflowEngine()
    val definition = WorkflowFamily.TASK_RUNTIME.definition
    val opened = engine.openRecord(definition, workflowId, "fis-176", "preplan")
    val seeded =
      engine.updateRecord(
        definition,
        opened,
        WorkflowUpdateInput(
          terminalInstant = Instant.EPOCH,
          workflowStatus = WorkflowStatus.RUNNING,
          currentStepId = "preplan",
          stepUpdates = null,
          artifactsPatch =
            WorkflowArtifactPatch.from(
              mapOf(
                FEATURE_TASK_RUNTIME_GOAL_CONTINUATION_ARTIFACT_KEY to continuationMap,
                GOAL_SUBTASK_REVIEW_STATE_ARTIFACT_KEY to
                  GoalSubtaskReviewState.initial(
                    reviewBaseSha = baselineSha,
                    baselineUntrackedPaths = emptyList(),
                    codeReviewMode = CodeReviewExecutionMode.INLINE,
                  ).toPersistenceWire(),
              ),
            ),
          sessionId = "fis-176",
        ),
      ).toRecord()
    repository.saveFeatureTaskWorkflow(seeded, RUNTIME)
    val database = RuntimeFakeDatabaseSessionFactory(repository)
    val recorder =
      featureTaskRuntimePhaseRecorder(
        database,
        testWorkflowSnapshotValidator,
        AcceptingFeatureTaskRuntimeWireArtifactValidator,
        AcceptingFeatureTaskRuntimeWireArtifactValidator,
        testHarnessClock,
        NoopRuntimeDiagnostics,
      )
    val continuationRecorder =
      FeatureTaskRuntimeGoalContinuationRecorder(
        database,
        NoopRuntimeDiagnostics,
        testHarnessClock,
      )
    val runInvariantsStore =
      FeatureTaskRuntimeRunInvariantsStore(
        database,
        FeatureTaskRuntimeWorkflowPersistence(database, testWorkflowSnapshotValidator),
      )
    return AdoptionHarness(
      repository = repository,
      preparation =
        FeatureTaskRuntimeRunPreparation(
          recorder,
          continuationRecorder,
          runInvariantsStore,
          statusProjectionPhaseStrategies(),
        ),
    )
  }

  private data class AdoptionHarness(
    val repository: InMemoryRuntimeWorkflowRepository,
    val preparation: FeatureTaskRuntimeRunPreparation,
  ) {
    fun prepare(request: FeatureTaskRuntimeRunRequest): FeatureTaskRuntimePreparation {
      val fixture =
        ExecutionPlanAdmissionFixture(
          definition = SkeletonDefinition.GOAL_CHILD,
          repository = "repo-root-realpath-v1:${request.repoRoot}",
          specPath = request.runInvariants.specReference,
          qualityGate = request.goalContinuation?.qualityGateSelection,
        )
      fixture.seed(repository, request.workflowId, request.issueKey)
      val admitted = fixture.admission.admit(repository, request.workflowId, fixture.inputs)
      return preparation.prepare(request.copy(admittedExecution = admitted))
    }
  }
}
