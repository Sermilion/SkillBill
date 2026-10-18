package skillbill.engine.featuretask.persist.acceptance

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_ADOPTION_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PREVIOUS_CONTRACT_VERSION
import skillbill.engine.featuretask.lifecycle.core.AcquiredFeatureTaskRuntimeWorker
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCodec
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCompatibility
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairBinding
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairLegacyProof
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.engine.featuretask.persist.sha256Hex
import skillbill.engine.featuretask.phase.core.decodePhaseLedger
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputMigration
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.model.FeatureTaskRuntimePhaseOutputMigrationResult
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.FeatureTaskExecutionIdentity
import skillbill.workflow.model.FeatureTaskRouteScope
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.decodeCheckpointIdentitiesFromArtifact
import skillbill.workflow.taskruntime.artifact.decodeGoalContinuationArtifactFromArtifact
import skillbill.workflow.taskruntime.artifact.decodeImplementationAttemptsFromArtifact
import skillbill.workflow.taskruntime.artifact.decodeRunInvariantsFromArtifact
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdoptionComposition
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanSeed
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanSpecOrigin
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalPlanningImport
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseStrategyIdentity
import java.nio.file.Path
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as RepairKeys

@Inject
class AcceptanceCriterionRepairLegacyAdoption(
  private val codec: FeatureTaskRuntimeExecutionPlanCodec,
  private val compatibility: FeatureTaskRuntimeExecutionPlanCompatibility,
  private val phaseOutputs: FeatureTaskRuntimePhaseOutputMigration,
  private val git: WorkflowGitOperations,
  private val source: FeatureTaskRuntimeRunInvariantsSource,
) {
  internal fun sourceMatches(
    worker: AcquiredFeatureTaskRuntimeWorker,
    repositoryRoot: Path,
    binding: AcceptanceCriterionRepairBinding,
  ): Boolean {
    val current = source.read(repositoryRoot.resolve(worker.governedSpecPath))
    return current is FeatureTaskRuntimeRunInvariantsRead.Read && current.sourceDigest == binding.specDigest &&
      current.invariants.acceptanceCriteria == binding.authoredCriteria && binding
        .catalogId == sha256Hex(JsonCodec.valueToJsonString(binding.authoredCriteria))
  }

  internal fun admit(request: LegacyAdoptionRequest): FeatureTaskRuntimeWorkflowArtifactMap? = candidate(request)

  private data class SupportedLegacyPlan(
    val recorded: ResolvedPhaseExecutionPlan,
    val audit: ResolvedPhaseStrategyIdentity,
    val composition: AcceptanceCriterionRepairAdoptionComposition,
  )

  private fun candidate(request: LegacyAdoptionRequest): FeatureTaskRuntimeWorkflowArtifactMap? {
    val plan = supportedPlan(request) ?: return null
    val phaseOutputVersion =
      phaseOutputSourceVersion(decodePhaseRecords(request.original.artifacts)[plan.audit.entryStep]?.outputArtifact)
        ?: return null
    val pristine =
      historyIsPristine(request.original, plan.recorded, plan.audit) &&
        sourceWasBoundAtDiscovery(request.original, request.identity, request.binding, plan.audit) &&
        checkpointMatches(request.original, plan.recorded, plan.audit, request.proof, request.repositoryRoot) &&
        catalogMatches(request.original, request.binding)
    return if (pristine) {
      receipt(
        LegacyReceiptRequest(
          request.original,
          plan.recorded,
          plan.composition,
          request.binding,
          request.proof,
          phaseOutputVersion,
        ),
      )
    } else {
      null
    }
  }

  private fun supportedPlan(request: LegacyAdoptionRequest): SupportedLegacyPlan? {
    val descriptor =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(
        request.original.artifacts,
      ) ?: return null
    val bytes = JsonCodec.valueToJsonString(descriptor).toByteArray(Charsets.UTF_8)
    val recorded = codec.decode(bytes)
    compatibility.requireSupportedExecution(bytes, request.inputs)
    val audit = recorded.selectedStrategies.singleOrNull { it.slot == PhaseSlot.AUDIT } ?: return null
    val composition = composition(recorded) ?: return null
    return SupportedLegacyPlan(recorded, audit, composition)
      .takeIf { LegacyTuple(composition, audit.semanticRevision) in SUPPORTED_TUPLES }
  }

  private fun composition(recorded: ResolvedPhaseExecutionPlan): AcceptanceCriterionRepairAdoptionComposition? =
    when {
      recorded.definitionId == "standalone" && recorded.qualityGateSelection == null ->
        AcceptanceCriterionRepairAdoptionComposition.STANDALONE
      recorded.definitionId == "goal-child" &&
        recorded.qualityGateSelection == FeatureTaskRuntimeQualityGateSelection.BUILD ->
        AcceptanceCriterionRepairAdoptionComposition.GOAL_CHILD_BUILD
      recorded.definitionId == "goal-child" &&
        recorded.qualityGateSelection == FeatureTaskRuntimeQualityGateSelection.VALIDATE ->
        AcceptanceCriterionRepairAdoptionComposition.GOAL_CHILD_VALIDATE
      else -> null
    }

  private fun historyIsPristine(
    original: WorkflowStateSnapshot,
    recorded: ResolvedPhaseExecutionPlan,
    audit: ResolvedPhaseStrategyIdentity,
  ): Boolean {
    val records = decodePhaseRecords(original.artifacts)
    val discovery = records[audit.entryStep] ?: return false
    val repairSteps = audit.steps.filter { it != audit.entryStep }.toSet()
    val known = records.keys.all { it in recorded.selectedStepIds }
    val completed =
      discovery.status == WorkflowStepStatus.COMPLETED && discovery.attemptCount == 1 &&
        discovery.finishedAt != null
    val inFlight =
      records.values.any {
        it.status == WorkflowStepStatus.RUNNING || it.loopId != null ||
          it.edgeIteration != null
      }
    val repaired =
      records.keys.any { it in repairSteps } ||
        original.steps.any {
          it.stepId in repairSteps &&
            it.attemptCount > 0
        }
    val history = !inFlight && !repaired && historicalAttemptsArePristine(original, recorded, repairSteps)
    val output = phaseOutputIsCurrent(discovery.outputArtifact)
    return known && completed && history && output && FORBIDDEN_HISTORY.none { it.contains(original.artifacts) }
  }

  private fun phaseOutputSourceVersion(output: String?): String? =
    when (val migrated = output?.let(phaseOutputs::migrate)) {
      is FeatureTaskRuntimePhaseOutputMigrationResult.Current ->
        (JsonCodec.parseValue(migrated.payload) as? Map<*, *>)?.get(SharedPayloadKeys.CONTRACT_VERSION) as? String
      is FeatureTaskRuntimePhaseOutputMigrationResult.Migrated -> migrated.sourceVersion
      is FeatureTaskRuntimePhaseOutputMigrationResult.Refused, null -> null
    }

  private fun phaseOutputIsCurrent(output: String?): Boolean =
    when (val migrated = output?.let(phaseOutputs::migrate)) {
      is FeatureTaskRuntimePhaseOutputMigrationResult.Current -> true
      is FeatureTaskRuntimePhaseOutputMigrationResult.Migrated ->
        migrated.sourceVersion == FEATURE_TASK_RUNTIME_PREVIOUS_CONTRACT_VERSION &&
          migrated.targetVersion == FEATURE_TASK_RUNTIME_CONTRACT_VERSION
      is FeatureTaskRuntimePhaseOutputMigrationResult.Refused, null -> false
    }

  private fun historicalAttemptsArePristine(
    original: WorkflowStateSnapshot,
    recorded: ResolvedPhaseExecutionPlan,
    repairSteps: Set<String>,
  ): Boolean {
    val ledger = decodePhaseLedger(original.artifacts)
    val ambiguousLedger =
      ledger.any {
        it.loopId != null || it.edgeIteration != null ||
          it.action == FeatureTaskRuntimePhaseLedgerAction.RETRY
      }
    val repairLedger = ledger.any { it.phaseId in repairSteps || it.phaseId !in recorded.selectedStepIds }
    val attempts =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS.value(
        original.artifacts,
      )?.let(::decodeImplementationAttemptsFromArtifact).orEmpty()
    val repairAttempts = attempts.any { it.phaseId in repairSteps || it.phaseId !in recorded.selectedStepIds }
    val ambiguousAttempts = attempts.any { it.loopId != null || it.edgeIteration != null }
    return !ambiguousLedger && !repairLedger && !repairAttempts && !ambiguousAttempts
  }

  internal fun checkpointMatches(
    original: WorkflowStateSnapshot,
    recorded: ResolvedPhaseExecutionPlan,
    audit: ResolvedPhaseStrategyIdentity,
    proof: AcceptanceCriterionRepairLegacyProof,
    repositoryRoot: Path,
  ): Boolean {
    val checkpoint = checkpointEvidence(original, audit, proof) ?: return false
    val resolved =
      git.resolveCheckpointRef(
        repositoryRoot,
        FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
        proof.checkpointId,
      )
    val fingerprint = git.repositoryFingerprint(repositoryRoot)
    return resolved is WorkflowGitOperationResult.Ok && resolved.value.trim() == checkpoint.commitSha &&
      fingerprint is WorkflowGitOperationResult.Ok && fingerprint.value == checkpoint.treeDigest &&
      recorded.selectedStrategies.singleOrNull { it.slot == PhaseSlot.AUDIT } == audit
  }

  private data class CheckpointEvidence(val commitSha: String, val treeDigest: String)

  private fun checkpointEvidence(
    original: WorkflowStateSnapshot,
    audit: ResolvedPhaseStrategyIdentity,
    proof: AcceptanceCriterionRepairLegacyProof,
  ): CheckpointEvidence? {
    val checkpoints =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES.value(original.artifacts)
        ?: return null
    val checkpoint =
      decodeCheckpointIdentitiesFromArtifact(checkpoints).singleOrNull {
        it.checkpointRef == proof.checkpointId && it.phaseId == audit.entryStep &&
          it.generation.toLong() == proof.stoppedGeneration
      } ?: return null
    val historical = historicalCheckpoint(original, audit, proof) ?: return null
    return if (historical.treeDigest == proof.treeDigest) {
      CheckpointEvidence(checkpoint.commitSha, historical.treeDigest)
    } else {
      null
    }
  }

  private data class HistoricalCheckpointOutput(val treeDigest: String)

  private fun historicalCheckpoint(
    original: WorkflowStateSnapshot,
    audit: ResolvedPhaseStrategyIdentity,
    proof: AcceptanceCriterionRepairLegacyProof,
  ): HistoricalCheckpointOutput? {
    val output =
      decodePhaseRecords(original.artifacts)[audit.entryStep]?.outputArtifact
        ?.let { JsonCodec.parseValue(it) as? Map<*, *> }
        ?: return null
    val produced = JsonCodec.anyToStringAnyMap(output[SharedPayloadKeys.PRODUCED_OUTPUTS]) ?: return null
    return historicalCheckpointOutput(produced, proof)
  }

  private fun historicalCheckpointOutput(
    produced: Map<String, Any?>,
    proof: AcceptanceCriterionRepairLegacyProof,
  ): HistoricalCheckpointOutput? {
    val treeDigest = produced[RepairKeys.TREE_DIGEST] as? String ?: return null
    val checkpointId = produced[RepairKeys.CHECKPOINT_ID] as? String ?: return null
    return HistoricalCheckpointOutput(treeDigest).takeIf { checkpointId == proof.checkpointId }
  }

  private fun sourceWasBoundAtDiscovery(
    original: WorkflowStateSnapshot,
    identity: FeatureTaskExecutionIdentity,
    binding: AcceptanceCriterionRepairBinding,
    audit: ResolvedPhaseStrategyIdentity,
  ): Boolean {
    return if (identity.routeScope == FeatureTaskRouteScope.STANDALONE) {
      standaloneSourceWasBoundAtDiscovery(original, binding, audit)
    } else {
      goalChildSourceWasBoundAtDiscovery(original, identity, binding, audit)
    }
  }

  private fun standaloneSourceWasBoundAtDiscovery(
    original: WorkflowStateSnapshot,
    binding: AcceptanceCriterionRepairBinding,
    audit: ResolvedPhaseStrategyIdentity,
  ): Boolean {
    val planSeed =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_SEED.value(
        original.artifacts,
      ) ?: return false
    val seed = FeatureTaskRuntimePlanSeed.fromArtifactMap(FeatureTaskRuntimeWorkflowArtifactMap.from(planSeed))
    val discovery = decodePhaseRecords(original.artifacts)[audit.entryStep] ?: return false
    return seed.specOrigin == FeatureTaskRuntimePlanSpecOrigin.OPERATOR &&
      seed.specSha256 == binding.specDigest && discovery.status == WorkflowStepStatus.COMPLETED
  }

  private fun goalChildSourceWasBoundAtDiscovery(
    original: WorkflowStateSnapshot,
    identity: FeatureTaskExecutionIdentity,
    binding: AcceptanceCriterionRepairBinding,
    audit: ResolvedPhaseStrategyIdentity,
  ): Boolean {
    val imported =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT.value(original.artifacts)
        ?: return false
    val evidence =
      FeatureTaskRuntimeGoalPlanningImport.fromArtifactMap(FeatureTaskRuntimeWorkflowArtifactMap.from(imported))
    val child =
      decodeGoalContinuationArtifactFromArtifact(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.value(original.artifacts),
      ) ?: return false
    val discovery = decodePhaseRecords(original.artifacts)[audit.entryStep] ?: return false
    return evidence.parentGoalWorkflowId == child.parentWorkflowId &&
      evidence.normalizedIssueKey == child.issueKey && evidence.normalizedIssueKey == identity.normalizedIssueKey &&
      evidence.repositoryIdentity == identity.repositoryIdentity && evidence.subtaskId == child.subtaskId &&
      evidence.governedSubSpecPath == identity.governedSpecPath && evidence.subSpecHash == binding.specDigest &&
      discovery.status == WorkflowStepStatus.COMPLETED
  }

  private fun catalogMatches(
    original: WorkflowStateSnapshot,
    binding: AcceptanceCriterionRepairBinding,
  ): Boolean {
    val invariants =
      decodeRunInvariantsFromArtifact(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_RUN_INVARIANTS.value(original.artifacts),
      )
    return invariants?.acceptanceCriteria == binding.authoredCriteria && binding.catalogId ==
      sha256Hex(
        JsonCodec.valueToJsonString(binding.authoredCriteria),
      )
  }

  private fun receipt(request: LegacyReceiptRequest): FeatureTaskRuntimeWorkflowArtifactMap {
    val audit = request.recorded.selectedStrategies.single { it.slot == PhaseSlot.AUDIT }
    val originalJson = JsonCodec.valueToJsonString(request.original.artifacts)
    return FeatureTaskRuntimeWorkflowArtifactMap.from(
      mapOf(
        Keys.CONTRACT_VERSION to ACCEPTANCE_CRITERION_REPAIR_ADOPTION_CONTRACT_VERSION,
        Keys.COMPOSITION to request.composition.wireValue,
        Keys.SEMANTIC_REVISION to audit.semanticRevision,
        Keys.DESCRIPTOR_DIGEST to
          sha256Hex(
            JsonCodec.valueToJsonString(
              DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(request.original.artifacts),
            ),
          ),
        Keys.POLICY_DIGEST to sha256Hex(JsonCodec.valueToJsonString(request.recorded.stepPolicyIdentities)),
        Keys.SKELETON_DIGEST to sha256Hex(String(codec.encode(request.recorded), Charsets.UTF_8)),
        Keys.DECLARED_CONTRACT_VERSIONS to
          mapOf(
            Keys.CONTRACT_VERSION to request.recorded.contractVersion,
            Keys.PHASE_OUTPUT_CONTRACT_VERSION to request.phaseOutputVersion,
          ),
        Keys.CATALOG_ID to request.binding.catalogId,
        Keys.SPEC_DIGEST to request.binding.specDigest,
        Keys.EXECUTION_ID to request.binding.executionId,
        Keys.TREE_DIGEST to request.proof.treeDigest,
        Keys.CHECKPOINT_ID to request.proof.checkpointId,
        Keys.STOPPED_OWNER_GENERATION to request.proof.stoppedGeneration,
        Keys.STOPPED_OWNER_EVIDENCE_REF to
          if (request.proof.stoppedGeneration == 0L) {
            "ownership_absent:${sha256Hex(
              originalJson,
            )}"
          } else {
            "stopped_owner:${request.proof.stoppedGeneration}"
          },
        Keys.PRISTINE_HISTORY_DIGEST to sha256Hex(originalJson),
        Keys.RETAINED_EVIDENCE_REFS to
          listOf(
            "original_artifacts:${sha256Hex(originalJson)}",
            request.proof.checkpointId,
          ),
        Keys.PROSPECTIVE_BUDGET to true,
        Keys.STRUCTURED_DISCOVERY_REQUIRED to true,
        Keys.ORIGINAL_ARTIFACTS to request.original.artifacts,
      ),
    )
  }

  internal fun retainedProofMatches(
    receipt: Map<String, Any?>,
    worker: AcquiredFeatureTaskRuntimeWorker,
    binding: AcceptanceCriterionRepairBinding,
    repositoryRoot: Path,
    current: WorkflowStateSnapshot,
  ): Boolean {
    val retained = retainedPlan(receipt, current) ?: return false
    val proof = retainedProof(receipt) ?: return false
    return sourceMatches(
      worker,
      repositoryRoot,
      binding,
    ) && historyIsPristine(retained.original, retained.recorded, retained.audit) &&
      sourceWasBoundAtDiscovery(retained.original, worker.identity, binding, retained.audit) &&
      checkpointMatches(retained.original, retained.recorded, retained.audit, proof, repositoryRoot) &&
      catalogMatches(retained.original, binding)
  }

  private data class RetainedPlan(
    val original: WorkflowStateSnapshot,
    val recorded: ResolvedPhaseExecutionPlan,
    val audit: ResolvedPhaseStrategyIdentity,
  )

  private fun retainedPlan(
    receipt: Map<String, Any?>,
    current: WorkflowStateSnapshot,
  ): RetainedPlan? {
    val originalArtifacts = FeatureTaskRuntimeWorkflowArtifactMap.from(receipt[RepairKeys.ORIGINAL_ARTIFACTS])
    val original = current.copy(artifacts = DurableWorkflowArtifacts.fromMap(originalArtifacts))
    val descriptor =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(original.artifacts)
        ?: return null
    val recorded = codec.decode(JsonCodec.valueToJsonString(descriptor).toByteArray(Charsets.UTF_8))
    val audit = recorded.selectedStrategies.singleOrNull { it.slot == PhaseSlot.AUDIT } ?: return null
    return RetainedPlan(original, recorded, audit)
  }

  private fun retainedProof(receipt: Map<String, Any?>): AcceptanceCriterionRepairLegacyProof? {
    val generation = (receipt[RepairKeys.STOPPED_OWNER_GENERATION] as? Number)?.toLong() ?: return null
    val tree = receipt[RepairKeys.TREE_DIGEST] as? String ?: return null
    val checkpoint = receipt[RepairKeys.CHECKPOINT_ID] as? String ?: return null
    return AcceptanceCriterionRepairLegacyProof(tree, checkpoint, generation)
  }

  internal fun historicalEvidenceMatches(
    original: WorkflowStateSnapshot,
    worker: AcquiredFeatureTaskRuntimeWorker,
    binding: AcceptanceCriterionRepairBinding,
    proof: AcceptanceCriterionRepairLegacyProof,
    repositoryRoot: Path,
  ): Boolean {
    return try {
      val descriptor =
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(original.artifacts)
          ?: return false
      val recorded = codec.decode(JsonCodec.valueToJsonString(descriptor).toByteArray(Charsets.UTF_8))
      val audit = recorded.selectedStrategies.singleOrNull { it.slot == PhaseSlot.AUDIT } ?: return false
      sourceWasBoundAtDiscovery(original, worker.identity, binding, audit) &&
        checkpointMatches(original, recorded, audit, proof, repositoryRoot)
    } catch (_: SkillBillRuntimeException) {
      false
    }
  }

  internal fun hasAuditHistory(row: WorkflowStateSnapshot): Boolean {
    val artifacts = row.artifacts
    val descriptor =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(artifacts)
        ?: return true
    val recorded = codec.decode(JsonCodec.valueToJsonString(descriptor).toByteArray(Charsets.UTF_8))
    val auditSteps =
      recorded.selectedStrategies.singleOrNull { it.slot == PhaseSlot.AUDIT }?.steps
        ?.toSet() ?: return true
    return row.steps.any { it.stepId in auditSteps && it.attemptCount > 0 } ||
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_AUDIT_RESULT.contains(artifacts) ||
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR_HANDOFF.contains(artifacts) ||
      decodePhaseRecords(artifacts).keys.any { it in auditSteps || it !in recorded.selectedStepIds } ||
      decodePhaseLedger(artifacts).any {
        (it.phaseId in auditSteps && it.action !in UNSETTLED_ATTEMPT_ACTIONS) ||
          it.phaseId !in recorded.selectedStepIds
      } ||
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS.value(artifacts)?.let {
        decodeImplementationAttemptsFromArtifact(it).any { attempt ->
          attempt
            .phaseId in auditSteps || attempt.phaseId !in recorded.selectedStepIds
        }
      } == true
  }

  private data class LegacyTuple(val composition: AcceptanceCriterionRepairAdoptionComposition, val revision: Int)

  internal data class LegacyAdoptionRequest(
    val original: WorkflowStateSnapshot,
    val identity: FeatureTaskExecutionIdentity,
    val inputs: EffectiveGatePolicyInputs,
    val binding: AcceptanceCriterionRepairBinding,
    val proof: AcceptanceCriterionRepairLegacyProof,
    val repositoryRoot: Path,
  )

  private data class LegacyReceiptRequest(
    val original: WorkflowStateSnapshot,
    val recorded: ResolvedPhaseExecutionPlan,
    val composition: AcceptanceCriterionRepairAdoptionComposition,
    val binding: AcceptanceCriterionRepairBinding,
    val proof: AcceptanceCriterionRepairLegacyProof,
    val phaseOutputVersion: String,
  )

  private companion object {
    val SUPPORTED_TUPLES =
      setOf(
        LegacyTuple(AcceptanceCriterionRepairAdoptionComposition.STANDALONE, 1),
        LegacyTuple(AcceptanceCriterionRepairAdoptionComposition.STANDALONE, 2),
        LegacyTuple(AcceptanceCriterionRepairAdoptionComposition.STANDALONE, 3),
        LegacyTuple(AcceptanceCriterionRepairAdoptionComposition.GOAL_CHILD_BUILD, 1),
        LegacyTuple(AcceptanceCriterionRepairAdoptionComposition.GOAL_CHILD_BUILD, 2),
        LegacyTuple(AcceptanceCriterionRepairAdoptionComposition.GOAL_CHILD_BUILD, 3),
        LegacyTuple(AcceptanceCriterionRepairAdoptionComposition.GOAL_CHILD_VALIDATE, 1),
        LegacyTuple(AcceptanceCriterionRepairAdoptionComposition.GOAL_CHILD_VALIDATE, 2),
        LegacyTuple(AcceptanceCriterionRepairAdoptionComposition.GOAL_CHILD_VALIDATE, 3),
      )
    val UNSETTLED_ATTEMPT_ACTIONS =
      setOf(
        FeatureTaskRuntimePhaseLedgerAction.START,
        FeatureTaskRuntimePhaseLedgerAction.RESUME,
      )
    val FORBIDDEN_HISTORY =
      listOf(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR,
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_AUDIT_RESULT,
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR_HANDOFF,
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_ACCEPTANCE_CRITERION_REPAIR_ADOPTION,
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY,
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_NO_CHANGE_PAUSE,
      )
  }
}
