package skillbill.engine.featuretask.persist.acceptance

import skillbill.agent.model.PhaseOutput
import skillbill.contracts.JsonCodec
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
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.taskruntime.artifact.asCheckpointIdentitiesArtifactEntry
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairMode
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import skillbill.workflow.taskruntime.model.handoff.task.NormalizedFeatureTaskRuntimePhaseOutput
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeCheckpointIdentity
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
      SerialRepairSqliteFixture(revision).use { f ->
        val descriptor =
          if (revision == 3) {
            f.execution.descriptor()
          } else {
            f.execution.validator.read(
              ArchivedAuditExecutionPlanFixture.archive(f.execution, "standalone", revision),
              "archive",
            )
          }
        val original = pristine(f, descriptor)
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
                f.audit(
                  AcceptanceCriterionRepairMode.INITIAL_DISCOVERY,
                ),
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
            definition = if (mode == "standalone") SkeletonDefinition.STANDALONE else SkeletonDefinition.GOAL_CHILD,
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
          )
        for (revision in 1..3) {
          val bytes =
            if (revision == 3) {
              ArchivedAuditExecutionPlanFixture.currentArchive(execution, mode)
            } else {
              ArchivedAuditExecutionPlanFixture.archive(execution, mode, revision)
            }
          val descriptor = execution.validator.read(bytes, "archive")
          val original = pristine(f, descriptor)
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
  ): WorkflowStateSnapshot =
    f.snapshot().copy(
      artifacts =
        DurableWorkflowArtifacts.fromMap(
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
                  0, "SKILL-384", "standalone", CHECKPOINT, "feature/SKILL-384", "implement", 0,
                  "a".repeat(
                    64,
                  ),
                  1, COMMIT, f.clock.instant().toString(),
                ),
              ).asCheckpointIdentitiesArtifactEntry(),
            ),
          ),
        ),
    )

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
