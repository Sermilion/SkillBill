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
import skillbill.error.core.SkillBillRuntimeException
import skillbill.engine.featuretask.persist.sha256Hex
import skillbill.engine.featuretask.phase.core.decodePhaseLedger
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputMigration
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.model.FeatureTaskRuntimePhaseOutputMigrationResult
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.decodeCheckpointIdentitiesFromArtifact
import skillbill.workflow.taskruntime.artifact.decodeImplementationAttemptsFromArtifact
import skillbill.workflow.taskruntime.artifact.decodeRunInvariantsFromArtifact
import skillbill.workflow.taskruntime.artifact.decodeGoalContinuationArtifactFromArtifact
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdoptionComposition
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanSeed
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimePlanSpecOrigin
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalPlanningImport
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as RepairKeys
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseStrategyIdentity
import java.nio.file.Path
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

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

  internal fun admit(
    original: WorkflowStateSnapshot,
    worker: AcquiredFeatureTaskRuntimeWorker,
    inputs: EffectiveGatePolicyInputs,
    binding: AcceptanceCriterionRepairBinding,
    proof: AcceptanceCriterionRepairLegacyProof,
    repositoryRoot: Path,
  ): FeatureTaskRuntimeWorkflowArtifactMap? {
    val descriptor =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(
        original.artifacts,
      ) ?: return null
    val bytes = JsonCodec.valueToJsonString(descriptor).toByteArray(Charsets.UTF_8)
    val recorded = codec.decode(bytes)
    compatibility.requireSupportedExecution(bytes, inputs)
    val audit = recorded.selectedStrategies.singleOrNull { it.slot == PhaseSlot.AUDIT } ?: return null
    val composition = composition(recorded) ?: return null
    val supported = LegacyTuple(composition, audit.semanticRevision) in SUPPORTED_TUPLES
    val discoveryOutput = decodePhaseRecords(original.artifacts)[audit.entryStep]?.outputArtifact
    val phaseOutputVersion = phaseOutputSourceVersion(discoveryOutput) ?: return null
    val pristine =
      historyIsPristine(original, recorded, audit) &&
        sourceWasBoundAtDiscovery(original, worker, binding, audit) &&
        checkpointMatches(original, recorded, audit, proof, repositoryRoot) && catalogMatches(original, binding)
    return if (supported && pristine) {
      receipt(original, recorded, composition, binding, proof, phaseOutputVersion)
    } else {
      null
    }
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
    val checkpoints = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES.value(original.artifacts)
      ?: return false
    val checkpoint = decodeCheckpointIdentitiesFromArtifact(checkpoints).singleOrNull {
      it.checkpointRef == proof.checkpointId && it.phaseId == audit.entryStep &&
        it.generation.toLong() == proof.stoppedGeneration
    } ?: return false
    val discovery = decodePhaseRecords(original.artifacts)[audit.entryStep] ?: return false
    val output = discovery.outputArtifact?.let { JsonCodec.parseValue(it) } as? Map<*, *> ?: return false
    val historicalTree = output[RepairKeys.TREE_DIGEST] as? String ?: return false
    val historicalCheckpoint = output[RepairKeys.CHECKPOINT_ID] as? String ?: return false
    if (historicalTree != proof.treeDigest || historicalCheckpoint != proof.checkpointId) return false
    val resolved = git.resolveCheckpointRef(
      repositoryRoot,
      FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
      proof.checkpointId,
    )
    val fingerprint = git.repositoryFingerprint(repositoryRoot)
    return resolved is WorkflowGitOperationResult.Ok && resolved.value.trim() == checkpoint.commitSha &&
      fingerprintMatches(fingerprint, historicalTree) &&
      recorded.selectedStrategies.singleOrNull { it.slot == PhaseSlot.AUDIT } == audit
  }

  private fun sourceWasBoundAtDiscovery(
    original: WorkflowStateSnapshot,
    worker: AcquiredFeatureTaskRuntimeWorker,
    binding: AcceptanceCriterionRepairBinding,
    audit: ResolvedPhaseStrategyIdentity,
  ): Boolean {
    val planSeed = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PLAN_SEED.value(original.artifacts)
    if (planSeed != null && !worker.goalChild) {
      val seed = FeatureTaskRuntimePlanSeed.fromArtifactMap(FeatureTaskRuntimeWorkflowArtifactMap.from(planSeed))
      val discovery = decodePhaseRecords(original.artifacts)[audit.entryStep] ?: return false
      return seed.specOrigin == FeatureTaskRuntimePlanSpecOrigin.OPERATOR &&
        seed.specSha256 == binding.specDigest && discovery.status == WorkflowStepStatus.COMPLETED
    }
    val imported = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT.value(original.artifacts)
      ?: return false
    if (!worker.goalChild) return false
    val evidence =
      FeatureTaskRuntimeGoalPlanningImport.fromArtifactMap(FeatureTaskRuntimeWorkflowArtifactMap.from(imported))
    val child = decodeGoalContinuationArtifactFromArtifact(
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.value(original.artifacts),
    ) ?: return false
    val discovery = decodePhaseRecords(original.artifacts)[audit.entryStep] ?: return false
    return evidence.parentGoalWorkflowId == child.parentWorkflowId &&
      evidence.normalizedIssueKey == child.issueKey && evidence.normalizedIssueKey == worker.normalizedIssueKey &&
      evidence.repositoryIdentity == worker.repositoryIdentity && evidence.subtaskId == child.subtaskId &&
      evidence.governedSubSpecPath == worker.governedSpecPath && evidence.subSpecHash == binding.specDigest &&
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

  private fun receipt(
    original: WorkflowStateSnapshot,
    recorded: ResolvedPhaseExecutionPlan,
    composition: AcceptanceCriterionRepairAdoptionComposition,
    binding: AcceptanceCriterionRepairBinding,
    proof: AcceptanceCriterionRepairLegacyProof,
    phaseOutputVersion: String,
  ): FeatureTaskRuntimeWorkflowArtifactMap {
    val audit = recorded.selectedStrategies.single { it.slot == PhaseSlot.AUDIT }
    val originalJson = JsonCodec.valueToJsonString(original.artifacts)
    return FeatureTaskRuntimeWorkflowArtifactMap.from(
      mapOf(
        Keys.CONTRACT_VERSION to ACCEPTANCE_CRITERION_REPAIR_ADOPTION_CONTRACT_VERSION,
        Keys.COMPOSITION to composition.wireValue,
        Keys.SEMANTIC_REVISION to audit.semanticRevision,
        Keys.DESCRIPTOR_DIGEST to
          sha256Hex(
            JsonCodec.valueToJsonString(
              DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(original.artifacts),
            ),
          ),
        Keys.POLICY_DIGEST to sha256Hex(JsonCodec.valueToJsonString(recorded.stepPolicyIdentities)),
        Keys.SKELETON_DIGEST to sha256Hex(String(codec.encode(recorded), Charsets.UTF_8)),
        Keys.DECLARED_CONTRACT_VERSIONS to
          mapOf(
            Keys.CONTRACT_VERSION to recorded.contractVersion,
            Keys.PHASE_OUTPUT_CONTRACT_VERSION to phaseOutputVersion,
          ),
        Keys.CATALOG_ID to binding.catalogId,
        Keys.SPEC_DIGEST to binding.specDigest,
        Keys.EXECUTION_ID to binding.executionId,
        Keys.TREE_DIGEST to proof.treeDigest,
        Keys.CHECKPOINT_ID to proof.checkpointId,
        Keys.STOPPED_OWNER_GENERATION to proof.stoppedGeneration,
        Keys.STOPPED_OWNER_EVIDENCE_REF to
          if (proof.stoppedGeneration == 0L) {
            "ownership_absent:${sha256Hex(
              originalJson,
            )}"
          } else {
            "stopped_owner:${proof.stoppedGeneration}"
          },
        Keys.PRISTINE_HISTORY_DIGEST to sha256Hex(originalJson),
        Keys.RETAINED_EVIDENCE_REFS to listOf("original_artifacts:${sha256Hex(originalJson)}", proof.checkpointId),
        Keys.PROSPECTIVE_BUDGET to true,
        Keys.STRUCTURED_DISCOVERY_REQUIRED to true,
        Keys.ORIGINAL_ARTIFACTS to original.artifacts,
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
    val originalArtifacts = FeatureTaskRuntimeWorkflowArtifactMap.from(receipt[RepairKeys.ORIGINAL_ARTIFACTS])
    val original = current.copy(artifacts = DurableWorkflowArtifacts.fromMap(originalArtifacts))
    val descriptor = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(original.artifacts)
      ?: return false
    val descriptorBytes = JsonCodec.valueToJsonString(descriptor).toByteArray(Charsets.UTF_8)
    val recorded = codec.decode(descriptorBytes)
    val audit = recorded.selectedStrategies.singleOrNull { it.slot == PhaseSlot.AUDIT } ?: return false
    val generation = (receipt[RepairKeys.STOPPED_OWNER_GENERATION] as? Number)?.toLong() ?: return false
    val tree = receipt[RepairKeys.TREE_DIGEST] as? String ?: return false
    val checkpoint = receipt[RepairKeys.CHECKPOINT_ID] as? String ?: return false
    val proof = AcceptanceCriterionRepairLegacyProof(tree, checkpoint, generation)
    return sourceMatches(worker, repositoryRoot, binding) && historyIsPristine(original, recorded, audit) &&
      sourceWasBoundAtDiscovery(original, worker, binding, audit) &&
      checkpointMatches(original, recorded, audit, proof, repositoryRoot) && catalogMatches(original, binding)
  }

  internal fun historicalEvidenceMatches(
    original: WorkflowStateSnapshot,
    worker: AcquiredFeatureTaskRuntimeWorker,
    binding: AcceptanceCriterionRepairBinding,
    proof: AcceptanceCriterionRepairLegacyProof,
    repositoryRoot: Path,
  ): Boolean {
    return try {
      val descriptor = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(original.artifacts)
        ?: return false
      val recorded = codec.decode(JsonCodec.valueToJsonString(descriptor).toByteArray(Charsets.UTF_8))
      val audit = recorded.selectedStrategies.singleOrNull { it.slot == PhaseSlot.AUDIT } ?: return false
      sourceWasBoundAtDiscovery(original, worker, binding, audit) &&
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
      decodePhaseLedger(artifacts).any { it.phaseId in auditSteps || it.phaseId !in recorded.selectedStepIds } ||
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_IMPLEMENTATION_ATTEMPTS.value(artifacts)?.let {
        decodeImplementationAttemptsFromArtifact(it).any { attempt ->
          attempt
            .phaseId in auditSteps || attempt.phaseId !in recorded.selectedStepIds
        }
      } == true
  }

  private data class LegacyTuple(val composition: AcceptanceCriterionRepairAdoptionComposition, val revision: Int)

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
