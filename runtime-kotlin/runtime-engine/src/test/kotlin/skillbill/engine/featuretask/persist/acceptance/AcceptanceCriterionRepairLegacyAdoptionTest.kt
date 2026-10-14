package skillbill.engine.featuretask.persist.acceptance

import skillbill.agent.model.PhaseOutput
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.engine.featuretask.lifecycle.execution.ArchivedAuditExecutionPlanFixture
import skillbill.engine.featuretask.lifecycle.execution.ExecutionPlanAdmissionFixture
import skillbill.engine.featuretask.lifecycle.execution.executionPolicyDigest
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairAdoptionResult
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairLegacyProof
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairMutation
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRefusal
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairWriteResult
import skillbill.engine.featuretask.model.phase.FeatureTaskRuntimePhaseStateRequest
import skillbill.engine.featuretask.phase.record.featureTaskRuntimePhaseRecordFor
import skillbill.infrastructure.contracts.workflow.featuretask.AcceptanceCriterionRepairAuxiliarySchemaValidator
import skillbill.infrastructure.contracts.workflow.featuretask.ContractFeatureTaskRuntimePhaseOutputMigration
import skillbill.ports.featuretask.model.FeatureTaskRuntimeWorkerOwnership
import skillbill.ports.taskruntime.FeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.NoopFeatureTaskRuntimeWorkerSupervisor
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeProcessInspection
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
import kotlin.test.assertTrue
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys
import skillbill.contracts.workflow.identity.task.FeatureTaskRuntimeExecutionPlanKeys as ExecutionKeys

class AcceptanceCriterionRepairLegacyAdoptionTest {
  @Test
  fun `SQLite adoption preserves original evidence and retries before prospective structured discovery`() {
    for (revision in 1..3) {
      SerialRepairSqliteFixture(revision, archiveMode = "goal-child-validate").use { f ->
        val descriptor =
          f.execution.validator.read(
            ArchivedAuditExecutionPlanFixture.historicalAdoptionArchive("goal-child-validate", revision),
            "archive",
          )
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
  fun `all nine historical compositions preserve both output versions and refuse policy drift or prior repair`() {
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
            ContractFeatureTaskRuntimePhaseOutputMigration(),
            git,
            f.source,
          )
        for (revision in 1..3) {
          val bytes = ArchivedAuditExecutionPlanFixture.historicalAdoptionArchive(mode, revision)
          val descriptor = execution.validator.read(bytes, "archive")
          val original = pristine(f, descriptor)
          val receipt =
            assertNotNull(
              admission.admit(
                original,
                execution.inputs,
                f.binding,
                AcceptanceCriterionRepairLegacyProof(
                  "d"
                    .repeat(64),
                  CHECKPOINT,
                  0,
                ),
                f.root,
              ),
              "$mode/$revision",
            )
          AcceptanceCriterionRepairAuxiliarySchemaValidator.validateRepairAdoption(receipt, "receipt")
          assertEquals(
            JsonCodec.valueToJsonString(original.artifacts),
            JsonCodec.valueToJsonString(
              receipt[Keys.ORIGINAL_ARTIFACTS],
            ),
          )
          assertEquals(revision, receipt[Keys.SEMANTIC_REVISION])
          assertEquals(true, receipt[Keys.STRUCTURED_DISCOVERY_REQUIRED])
          assertEquals(true, receipt[Keys.PROSPECTIVE_BUDGET])
          assertLegacyOutputAdoption(f, execution, admission, original)
          assertArchivedRefusals(f, execution, admission, original)
        }
      }
    }
  }

  @Test
  fun `expired ownership reclaimed from an unknown process cannot prove stopped pristine adoption`() {
    val supervisor =
      object : FeatureTaskRuntimeWorkerSupervisor by NoopFeatureTaskRuntimeWorkerSupervisor {
        override fun inspect(ownership: FeatureTaskRuntimeWorkerOwnership) =
          FeatureTaskRuntimeProcessInspection.OwnershipMismatch("The prior process cannot be identified.")
      }
    SerialRepairSqliteFixture(3, supervisor).use { f ->
      val descriptor =
        f.execution.validator.read(
          ArchivedAuditExecutionPlanFixture.historicalAdoptionArchive("standalone", 3),
          "archive",
        )
      val original = pristine(f, descriptor)
      f.database.transaction { it.workflowStates.save(WorkflowFamily.TASK_RUNTIME, original) }
      var previous: FeatureTaskRuntimeWorkerOwnership? = null
      f.owned {
        previous = f.database.read { work -> work.workflowStates.getFeatureTaskRuntimeWorkerOwnership(f.workflowId) }
      }
      val expired =
        assertNotNull(previous).copy(
          heartbeatAt = f.clock.instant().minusSeconds(60).toString(),
          expiresAt = f.clock.instant().minusSeconds(1).toString(),
        )
      val expectedUpdatedAt =
        f.database.read { work ->
          assertNotNull(work.workflowStates.getFeatureTaskWorkflow(f.workflowId)).updatedAt
        }
      f.database.transaction { work ->
        assertTrue(work.workflowStates.acquireFeatureTaskRuntimeWorker(expired, expectedUpdatedAt))
      }
      val persistence = f.service(gitOperations = git)
      f.owned { worker ->
        val refused =
          assertIs<AcceptanceCriterionRepairAdoptionResult.Refused>(
            persistence.adoptLegacy(worker, f.binding, "d".repeat(64), CHECKPOINT, f.root),
          )
        assertEquals(AcceptanceCriterionRepairRefusal.PRIOR_OWNER_STOP_NOT_PROVEN, refused.reason)
        assertEquals(
          JsonCodec.valueToJsonString(original.artifacts),
          JsonCodec.valueToJsonString(f.snapshot().artifacts),
        )
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
        original,
        execution.inputs,
        f.binding,
        AcceptanceCriterionRepairLegacyProof("e".repeat(64), CHECKPOINT, 0),
        f.root,
      ),
    )
    assertNull(
      admission.admit(
        original,
        execution.inputs,
        f.binding,
        AcceptanceCriterionRepairLegacyProof("d".repeat(64), "missing-checkpoint", 0),
        f.root,
      ),
    )
    assertHistoricalPolicyRefusals(f, execution, admission, original)
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
        repaired,
        execution.inputs,
        f.binding,
        AcceptanceCriterionRepairLegacyProof("d".repeat(64), CHECKPOINT, 0),
        f.root,
      ),
    )
  }

  private fun assertLegacyOutputAdoption(
    f: SerialRepairSqliteFixture,
    execution: ExecutionPlanAdmissionFixture,
    admission: AcceptanceCriterionRepairLegacyAdoption,
    original: WorkflowStateSnapshot,
  ) {
    val legacy =
      phase(f).copy(
        outputArtifact =
          JsonCodec.valueToJsonString(
            mapOf(
              SharedPayloadKeys.CONTRACT_VERSION to "0.6",
              SharedPayloadKeys.PHASE_ID to "audit",
              SharedPayloadKeys.STATUS to "completed",
              SharedPayloadKeys.SUMMARY to "initial discovery",
              SharedPayloadKeys.PRODUCED_OUTPUTS to mapOf(SharedPayloadKeys.VALUE to "AC-001 remains unresolved"),
            ),
          ),
      )
    val retained =
      original.copy(
        artifacts =
          DurableWorkflowArtifacts.fromMap(
            original.artifacts +
              DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.entry(
                mapOf("audit" to legacy.asWorkflowArtifactEntry()),
              ),
          ),
      )
    val receipt =
      assertNotNull(
        admission.admit(
          retained,
          execution.inputs,
          f.binding,
          AcceptanceCriterionRepairLegacyProof("d".repeat(64), CHECKPOINT, 0),
          f.root,
        ),
      )
    val versions = assertNotNull(JsonCodec.anyToStringAnyMap(receipt[Keys.DECLARED_CONTRACT_VERSIONS]))
    assertEquals("0.6", versions[Keys.PHASE_OUTPUT_CONTRACT_VERSION])
    assertEquals(
      JsonCodec.valueToJsonString(retained.artifacts),
      JsonCodec.valueToJsonString(receipt[Keys.ORIGINAL_ARTIFACTS]),
    )
  }

  private fun assertHistoricalPolicyRefusals(
    f: SerialRepairSqliteFixture,
    execution: ExecutionPlanAdmissionFixture,
    admission: AcceptanceCriterionRepairLegacyAdoption,
    original: WorkflowStateSnapshot,
  ) {
    val family = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN
    val descriptor = requireNotNull(JsonCodec.anyToStringAnyMap(family.value(original.artifacts)))
    val conflicts =
      listOf(ExecutionKeys.STEP_POLICIES, ExecutionKeys.EFFECTIVE_POLICIES).map { policyFamily ->
        val policies = requireNotNull(JsonCodec.anyToStringAnyMapList(descriptor[policyFamily]))
        descriptor + (
          policyFamily to
            policies.mapIndexed { index, policy ->
              if (index != 0) {
                policy
              } else if (policyFamily == ExecutionKeys.STEP_POLICIES) {
                val identity = "step-policy-v2:${"f".repeat(64)}"
                policy +
                  mapOf(
                    ExecutionKeys.IDENTITY to identity,
                    ExecutionKeys.SEMANTIC_DIGEST to executionPolicyDigest(identity),
                  )
              } else {
                policy + (ExecutionKeys.SEMANTIC_DIGEST to "f".repeat(64))
              }
            }
        )
      } +
        listOf(
          descriptor + (
            ExecutionKeys.EFFECTIVE_POLICY_SETTINGS to
              (
                requireNotNull(JsonCodec.anyToStringAnyMap(descriptor[ExecutionKeys.EFFECTIVE_POLICY_SETTINGS])) +
                  (ExecutionKeys.PHASE_TIMEOUT_MILLIS to 1L)
              )
          ),
        )
    for (conflicting in conflicts) {
      val validated = execution.validator.read(JsonCodec.mapToJsonString(conflicting).encodeToByteArray(), "conflict")
      val changed =
        original.copy(
          artifacts =
            DurableWorkflowArtifacts.fromMap(
              original.artifacts + mapOf(family.entry(validated)),
            ),
        )
      assertNull(
        admission.admit(
          changed,
          execution.inputs,
          f.binding,
          AcceptanceCriterionRepairLegacyProof("d".repeat(64), CHECKPOINT, 0),
          f.root,
        ),
      )
    }
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
