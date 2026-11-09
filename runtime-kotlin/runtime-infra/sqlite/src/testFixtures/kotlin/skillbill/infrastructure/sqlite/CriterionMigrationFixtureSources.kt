package skillbill.infrastructure.sqlite

import skillbill.contracts.JsonCodec
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.artifact.AcceptanceCriterionEvidenceWire
import skillbill.workflow.taskruntime.artifact.historical.asEngineProgressWireMap
import skillbill.workflow.taskruntime.artifact.historical.asEngineRepairWireMap
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionCompletedMutation
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionMutationDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdmission
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairCriterion
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcome
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairOutcomeKind
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionSourceDisposition
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionValidationObligation
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationProgress
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionProgressPayloadKeys as ProgressKeys
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

object CriterionMigrationFixtureSources {
  val digest: String = "a".repeat(64)

  fun artifactsJson(
    oldest: Boolean = true,
    retainedEvidence: Boolean = false,
    pendingCheck: Boolean = false,
  ): String {
    val admission =
      AcceptanceCriterionRepairAdmission(
        "catalog",
        "execution",
        digest,
        listOf("accepted", "remaining"),
        listOf(
          criterion("AC-001", "accepted", 1, AcceptanceCriterionSourceDisposition.SOURCE_CLOSED),
          criterion("AC-002", "remaining", 2, AcceptanceCriterionSourceDisposition.SOURCE_UNRESOLVED),
        ),
        emptyList(),
      )
    var state = checkNotNull(AcceptanceCriterionRepairState.admitPreAdoption(admission))
    repeat(if (pendingCheck) 0 else 3) { index ->
      val id = "reservation-$index"
      state = checkNotNull(state.reserve(id))
      state =
        checkNotNull(
          state.completeRepairAwaitingTargetedVerification(
            id,
            "output-$index",
            digest,
            "checkpoint-$index",
          ),
        )
      state =
        checkNotNull(
          state.record(
            AcceptanceCriterionRepairOutcome(
              "outcome-$index",
              id,
              "AC-002",
              digest,
              AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE,
              "remaining-gap-$index",
            ),
          ),
        )
    }
    if (pendingCheck) {
      state = checkNotNull(state.reserve("pending-reservation"))
      state =
        checkNotNull(
          state.completeRepairAwaitingTargetedVerification(
            "pending-reservation",
            "pending-output",
            digest,
            "pending-checkpoint",
          ),
        )
    }
    var repair: Map<String, Any?> = state.asEngineRepairWireMap()
    if (oldest) repair = repair - Keys.INTERACTION_TRACE + (Keys.CONTRACT_VERSION to "0.1")
    var progress: Map<String, Any?> =
      FeatureTaskRuntimeImplementationProgress.preAdoption(
        listOf(
          "AC-001",
          "AC-002",
        ),
      )
        .copy(
          currentIndex = 1,
          workerCount = if (pendingCheck) 1 else 3,
          launchCount = 7,
        ).asEngineProgressWireMap()
    if (retainedEvidence) {
      check(!oldest)
      val mutation =
        AcceptanceCriterionEvidenceWire.mutation(
          AcceptanceCriterionCompletedMutation(
            "catalog",
            digest,
            "execution",
            "AC-002",
            "old-worker",
            "worker-execution",
            "worker-output",
            digest,
            digest,
            "baseline-checkpoint",
            digest,
            "terminal-checkpoint",
            emptyList(),
            AcceptanceCriterionMutationDisposition.HISTORICAL_UNKNOWN,
          ),
        ) - ProgressKeys.TERMINAL_DISPOSITION - ProgressKeys.LAUNCH_ATTRIBUTION
      repair = repair + (ProgressKeys.COMPLETED_MUTATIONS to listOf(mutation, mutation))
      progress = progress + (ProgressKeys.COMPLETED_MUTATIONS to listOf(mutation, mutation))
    }
    var audit =
      identity() +
        mapOf(
          Keys.CONTRACT_VERSION to "0.1", Keys.MODE to "initial_discovery", Keys.TREE_DIGEST to digest,
          Keys.CHECKPOINT_ID to "accepted-checkpoint",
          Keys.PRODUCING_OUTPUT_ID to "audit-output",
          Keys.PRODUCING_OUTPUT_DIGEST to digest,
          Keys.CRITERIA to
            state.criteria.map {
              mapOf(
                Keys.CANONICAL_REF to it.canonicalRef,
                Keys.SOURCE_DISPOSITION to it.sourceDisposition.wireValue,
                Keys.VALIDATION_OBLIGATION to it.validationObligation.wireValue,
                Keys.VALIDATION_OWNER_AVAILABLE to it.validationOwnerAvailable,
                Keys.EVIDENCE_REF to it.evidenceRef,
              )
            },
          Keys.DEPENDENCIES to emptyList<Any?>(),
          Keys.REGRESSED_REFS to emptyList<String>(),
          Keys.EDGE_CHANGES to emptyList<Any?>(),
        )
    if (!oldest) {
      audit = audit +
        mapOf(
          Keys.CONTRACT_VERSION to "0.2",
          Keys.INTERACTIONS to emptyList<Any?>(),
          Keys.RECHECKS to emptyList<Any?>(),
        )
    }
    val handoff =
      identity() +
        mapOf(
          Keys.CONTRACT_VERSION to "0.1",
          Keys.REVISION to 2,
          Keys.CRITERION_REF to "AC-002",
          Keys.STAGE to "discovery_admitted",
          Keys.RESERVATION_ID to null, Keys.UNSUCCESSFUL_USED to 2, Keys.UNSUCCESSFUL_REMAINING to 1,
          Keys.QUEUE_POSITION to 1,
          Keys.QUEUE_SIZE to 1,
          Keys.ACTIONABLE_GAP to "remaining production obligation",
          Keys.EVIDENCE_REF to "audit-output",
          Keys.TREE_DIGEST to digest,
          Keys.CHECKPOINT_ID to "accepted-checkpoint",
          Keys.DEPENDENCIES to emptyList<Any?>(), Keys.PREREQUISITE_FACTS to emptyList<Any?>(),
        )
    return "  " +
      JsonCodec.valueToJsonString(
        mapOf(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR.entry(repair),
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_AUDIT_RESULT.entry(audit),
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR_HANDOFF.entry(handoff),
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS.entry(progress),
          RETAINED_HISTORY to
            listOf(
              "completed-commit",
              "accepted-checkpoint",
              "operator-retry-7",
              "execution-plan-identity",
            ),
        ),
      ) + "\n"
  }

  private fun identity(): Map<String, Any?> =
    mapOf(
      Keys.CATALOG_ID to "catalog",
      Keys.EXECUTION_ID to "execution",
      Keys.SPEC_DIGEST to digest,
    )

  private fun criterion(
    ref: String,
    text: String,
    ordinal: Int,
    source: AcceptanceCriterionSourceDisposition,
  ) = AcceptanceCriterionRepairCriterion(
    ref,
    null,
    text,
    ordinal,
    source,
    AcceptanceCriterionValidationObligation.DEFERRED,
    true,
    "audit",
  )

  const val RETAINED_HISTORY = "retained_history"
}
