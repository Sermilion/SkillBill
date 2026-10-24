package skillbill.engine.featuretask.persist.acceptance

import skillbill.agent.model.PhaseOutput
import skillbill.application.testProjectStore
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.lifecycle.execution.ArchivedAuditExecutionPlanFixture
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairAdoptionResult
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairLegacyProof
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairMutation
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairWriteResult
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecordFor
import skillbill.infrastructure.contracts.workflow.featuretask.AcceptanceCriterionRepairAuxiliarySchemaValidator
import skillbill.infrastructure.contracts.workflow.featuretask.ContractFeatureTaskRuntimePhaseOutputMigration
import skillbill.ports.workflow.gitops.NoopWorkflowGitOperations
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.review.context.model.execution.CodeReviewExecutionMode
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.ValidationDepth
import skillbill.workflow.taskruntime.artifact.asCheckpointIdentitiesArtifactEntry
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanSeed
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanSpecOrigin
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeCheckpointIdentity
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalContinuationArtifact
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalPlanningImport
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

class AcceptanceCriterionRepairLegacyAdoptionTest {
  @Test
  fun `SQLite adoption preserves original evidence and retries before prospective structured discovery`() {
    for (revision in 1..3) {
      SerialRepairSqliteFixture(revision, archiveMode = "goal-child-validate").use { f ->
        val descriptor =
          f.execution.validator.read(
            ArchivedAuditExecutionPlanFixture.legacyAuditPlan(f.execution, revision),
            "archive",
          )
        val original = pristine(f, descriptor, "goal-child-validate")
        f.database.transaction { it.workflowStates.save(WorkflowFamily.TASK_RUNTIME, original) }
        val persistence = f.service(gitOperations = git)
        f.owned { worker ->
          val receipt =
            assertIs<AcceptanceCriterionRepairAdoptionResult.Adopted>(
              persistence
                .adoptLegacy(worker, f.binding, "d".repeat(64), CHECKPOINT, f.root),
            )
          val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR_ADOPTION
          assertEquals(
            JsonCodec.valueToJsonString(
              original.artifacts - DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.label(),
            ),
            JsonCodec.valueToJsonString(
              f.snapshot().artifacts -
                setOf(
                  family.label(),
                  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.label(),
                ),
            ),
          )
          assertEquals(
            JsonCodec.valueToJsonString(receipt.receipt),
            JsonCodec.valueToJsonString(
              assertIs<AcceptanceCriterionRepairAdoptionResult.Adopted>(
                persistence.adoptLegacy(worker, f.binding, "d".repeat(64), CHECKPOINT, f.root),
              ).receipt,
            ),
          )
          val discovered =
            assertIs<AcceptanceCriterionRepairWriteResult.Committed>(
              f.write(
                worker,
                null,
                AcceptanceCriterionRepairMutation.Discover(f.admission),
                f.audit(),
                persistence,
              ),
            ).state
          assertEquals(emptyMap(), discovered.unsuccessfulTotals)
          assertEquals(emptyList(), discovered.outcomes)
          assertEquals(
            JsonCodec.valueToJsonString(original.artifacts),
            JsonCodec.valueToJsonString(
              receipt.receipt[Keys.ORIGINAL_ARTIFACTS],
            ),
          )
        }
      }
    }
  }

  @Test
  fun `all nine archived compositions admit only pristine discovery and retain their original artifacts`() {
    SerialRepairSqliteFixture().use { f ->
      listOf("standalone", "goal-child-build", "goal-child-validate").forEach { mode ->
        val execution =
          ExecutionPlanAdmissionFixture(
            definition =
              if (mode == "standalone") {
                SkeletonDefinition.STANDALONE_REVISION_2
              } else {
                SkeletonDefinition.GOAL_CHILD_REVISION_2
              },
            qualityGate =
              when (mode) {
                "goal-child-build" -> FeatureTaskRuntimeQualityGateSelection.BUILD
                "goal-child-validate" -> FeatureTaskRuntimeQualityGateSelection.VALIDATE
                else -> null
              },
          )
        val admission =
          AcceptanceCriterionRepairLegacyAdoption(
            execution.codec,
            execution.compatibility,
            ContractFeatureTaskRuntimePhaseOutputMigration(),
            git,
            f.source,
            testProjectStore,
          )
        for (revision in 1..3) {
          val bytes = ArchivedAuditExecutionPlanFixture.legacyAuditPlan(execution, revision)
          val descriptor = execution.validator.read(bytes, "archive")
          val original = pristine(f, descriptor, mode)
          val receipt =
            assertNotNull(
              admission.admit(
                legacyRequest(
                  original,
                  execution,
                  f,
                  AcceptanceCriterionRepairLegacyProof("d".repeat(64), CHECKPOINT, 0),
                ),
              ),
              "$mode/$revision",
            )
          AcceptanceCriterionRepairAuxiliarySchemaValidator.validateRepairAdoption(
            receipt,
            "receipt",
            execution.validator,
            ContractFeatureTaskRuntimePhaseOutputMigration(),
          )
          assertEquals(
            JsonCodec.valueToJsonString(original.artifacts),
            JsonCodec.valueToJsonString(
              receipt[Keys.ORIGINAL_ARTIFACTS],
            ),
          )
          assertEquals(revision, receipt[Keys.SEMANTIC_REVISION])
          assertEquals(true, receipt[Keys.STRUCTURED_DISCOVERY_REQUIRED])
          assertEquals(true, receipt[Keys.PROSPECTIVE_BUDGET])
          assertArchivedRefusals(f, execution, admission, original)
        }
      }
    }
  }

  private fun assertArchivedRefusals(
    f: SerialRepairSqliteFixture,
    execution: ExecutionPlanAdmissionFixture,
    admission: AcceptanceCriterionRepairLegacyAdoption,
    original: WorkflowStateSnapshot,
  ) {
    assertNull(
      admission.admit(
        legacyRequest(original, execution, f, AcceptanceCriterionRepairLegacyProof("e".repeat(64), CHECKPOINT, 0)),
      ),
    )
    assertNull(
      admission.admit(
        legacyRequest(
          original,
          execution,
          f,
          AcceptanceCriterionRepairLegacyProof("d".repeat(64), "missing-checkpoint", 0),
        ),
      ),
    )
    val repaired =
      original.copy(
        artifacts =
          DurableWorkflowArtifacts.fromMap(
            original.artifacts +
              mapOf(
                DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
                  mapOf(
                    "audit" to phase(f).asWorkflowArtifactEntry(),
                    "audit_implement_fix" to
                      phase(
                        f,
                      ).copy(phaseId = "audit_implement_fix").asWorkflowArtifactEntry(),
                  ),
                ),
              ),
          ),
      )
    assertNull(
      admission.admit(
        legacyRequest(repaired, execution, f, AcceptanceCriterionRepairLegacyProof("d".repeat(64), CHECKPOINT, 0)),
      ),
    )
  }

  private fun pristine(
    f: SerialRepairSqliteFixture,
    descriptor: Map<String, Any?>,
    composition: String = "standalone",
  ): WorkflowStateSnapshot {
    val identity = f.execution.identity(f.workflowId)
    val baseArtifacts =
      mapOf(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.entry(descriptor),
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
          mapOf(
            "audit" to
              phase(
                f,
              ).asWorkflowArtifactEntry(),
          ),
        ),
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_RUN_INVARIANTS.entry(
          FeatureTaskRuntimeRunInvariants(
            ".feature-specs/SKILL-384/spec.md",
            acceptanceCriteria = f.binding.authoredCriteria,
            mandatesAndOverrides = emptyList(),
          ).asWorkflowArtifactEntry(),
        ),
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES.entry(
          listOf(
            FeatureTaskRuntimeCheckpointIdentity(
              0, "SKILL-384", "standalone", CHECKPOINT, "feature/SKILL-384", "audit", 0,
              "a".repeat(
                64,
              ),
              1, COMMIT, f.clock.instant().toString(),
            ),
          ).asCheckpointIdentitiesArtifactEntry(),
        ),
      )
    val sourceArtifacts =
      if (composition == "standalone") {
        mapOf(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_SEED.entry(
            FeatureTaskRuntimePlanSeed(null, FeatureTaskRuntimePlanSpecOrigin.OPERATOR, f.binding.specDigest)
              .asWorkflowArtifactEntry(),
          ),
        )
      } else {
        goalChildArtifacts(f, identity, composition)
      }
    return f.snapshot().copy(artifacts = DurableWorkflowArtifacts.fromMap(baseArtifacts + sourceArtifacts))
  }

  private fun goalChildArtifacts(
    f: SerialRepairSqliteFixture,
    identity: FeatureTaskExecutionIdentity,
    composition: String,
  ): Map<String, Any?> {
    val gate =
      if (composition == "goal-child-build") {
        FeatureTaskRuntimeQualityGateSelection.BUILD
      } else {
        FeatureTaskRuntimeQualityGateSelection.VALIDATE
      }
    return mapOf(
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT.entry(
        FeatureTaskRuntimeGoalPlanningImport(
          "parent-workflow",
          identity.normalizedIssueKey,
          identity.repositoryIdentity,
          "b".repeat(64),
          "c".repeat(64),
          "goal-planning",
          "0.1",
          "phase-output",
          "0.1",
          1,
          1,
          identity.governedSpecPath,
          f.binding.specDigest,
          "d".repeat(64),
          "e".repeat(64),
        ).asWorkflowArtifactEntry(),
      ),
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.entry(
        FeatureTaskRuntimeGoalContinuationArtifact(
          identity.normalizedIssueKey,
          1,
          true,
          "feature/SKILL-384",
          "parent-workflow",
          CodeReviewExecutionMode.INLINE,
          ValidationDepth.FULL,
          gate,
        ).asWorkflowArtifactEntry(),
      ),
    )
  }

  private fun phase(f: SerialRepairSqliteFixture) =
    featureTaskRuntimePhaseRecordFor(
      FeatureTaskRuntimePhaseStateRequest(
        f.workflowId,
        "audit",
        "completed",
        1,
        "codex",
        true,
        outputArtifact =
          NormalizedFeatureTaskRuntimePhaseOutput(
            "audit",
            "completed",
            "initial discovery",
            PhaseOutput("AC-001 remains unresolved; AC-002 is source-closed"),
            verdict = "incomplete",
            runtimeRecord =
              mapOf(
                SharedPayloadKeys.PRODUCED_OUTPUTS to
                  mapOf(Keys.TREE_DIGEST to "d".repeat(64), Keys.CHECKPOINT_ID to CHECKPOINT),
              ),
          ).canonicalJson,
      ),
      null,
      f.clock.instant(),
    )

  private fun legacyRequest(
    original: WorkflowStateSnapshot,
    execution: ExecutionPlanAdmissionFixture,
    fixture: SerialRepairSqliteFixture,
    proof: AcceptanceCriterionRepairLegacyProof,
  ) = AcceptanceCriterionRepairLegacyAdoption.LegacyAdoptionRequest(
    original,
    execution.identity(original.workflowId),
    execution.inputs,
    fixture.binding,
    proof,
    fixture.root,
  )

  private companion object {
    const val CHECKPOINT = "refs/skill-bill/checkpoints/SKILL-384/standalone/0"
    val COMMIT = "a".repeat(40)
    val git =
      object : WorkflowGitOperations by NoopWorkflowGitOperations {
        override fun repositoryFingerprint(repoRoot: Path) = WorkflowGitOperationResult.Ok("d".repeat(64))

        override fun resolveCheckpointRef(
          repoRoot: Path,
          namespacePrefix: String,
          refName: String,
        ) = WorkflowGitOperationResult.Ok(COMMIT)
      }
  }
}
