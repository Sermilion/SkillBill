package skillbill.engine.featuretask.persist.acceptance

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys as DecompositionKeys
import skillbill.contracts.workflow.featuretask.ACCEPTANCE_CRITERION_REPAIR_ADOPTION_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_PREVIOUS_CONTRACT_VERSION
import skillbill.contracts.workflow.goal.GoalPlanningPreparationPayloadKeys as GoalKeys
import skillbill.engine.featuretask.persist.sha256Hex
import skillbill.engine.featuretask.lifecycle.core.AcquiredFeatureTaskRuntimeWorker
import skillbill.engine.featuretask.lifecycle.execution.FeatureTaskRuntimeExecutionPlanCodec
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairBinding
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairLegacyProof
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairRefusal
import skillbill.engine.featuretask.model.audit.AcceptanceCriterionRepairStandaloneSourceProof
import skillbill.engine.featuretask.model.execution.EffectiveGatePolicyInputs
import skillbill.engine.featuretask.phase.core.decodePhaseLedger
import skillbill.engine.featuretask.phase.core.decodePhaseRecords
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.taskruntime.FeatureTaskRuntimePhaseOutputMigration
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.taskruntime.model.FeatureTaskRuntimePhaseOutputMigrationResult
import skillbill.ports.taskruntime.model.FeatureTaskRuntimeRunInvariantsRead
import skillbill.ports.workflow.gitops.WorkflowGitOperations
import skillbill.ports.workflow.gitops.model.WorkflowGitOperationResult
import skillbill.review.spec.GovernedSpecSectionParser
import skillbill.review.spec.GovernedSpecSectionParser.ACCEPTANCE_CRITERIA_PREFIX
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.artifact.decodeCheckpointIdentitiesFromArtifact
import skillbill.workflow.taskruntime.artifact.decodeGoalContinuationArtifactFromArtifact
import skillbill.workflow.taskruntime.artifact.decodeImplementationAttemptsFromArtifact
import skillbill.workflow.taskruntime.artifact.decodeRunInvariantsFromArtifact
import skillbill.workflow.taskruntime.model.audit.AcceptanceCriterionRepairAdoptionComposition
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeGoalPlanningImport
import skillbill.workflow.taskruntime.model.persistence.FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.skeleton.FeatureTaskRuntimeQualityGateSelection
import skillbill.workflow.taskruntime.model.skeleton.PhaseSlot
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseExecutionPlan
import skillbill.workflow.taskruntime.model.skeleton.ResolvedPhaseStrategyIdentity
import java.nio.file.InvalidPathException
import java.nio.file.Path

@Inject
class AcceptanceCriterionRepairLegacyAdoption(
  private val codec: FeatureTaskRuntimeExecutionPlanCodec,
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
    val audit = recorded.selectedStrategies.singleOrNull { it.slot == PhaseSlot.AUDIT }
    val composition = composition(recorded)
    if (audit == null || composition == null ||
      !AcceptanceCriterionRepairHistoricalTuples.matches(recorded, inputs, composition, codec)
    ) {
      return null
    }
    val discoveryOutput = decodePhaseRecords(original.artifacts)[audit.entryStep]?.outputArtifact
    val sourceVersion = phaseOutputVersion(discoveryOutput) ?: return null
    val sourceBinding = sourceBindingAtDiscovery(original, worker, audit, binding, proof, repositoryRoot) ?: return null
    val pristine = historyIsPristine(original, recorded, audit) &&
      checkpointMatches(original, recorded, audit, proof, repositoryRoot) && catalogMatches(original, binding)
    return if (pristine && sourceBinding.specDigest == binding.specDigest &&
      sourceBinding.sourceCatalogMatches(binding.authoredCriteria) && sourceMatches(worker, repositoryRoot, binding)
    ) {
      receipt(original, recorded, composition, binding, sourceBinding, proof, sourceVersion)
    } else {
      null
    }
  }

  internal fun hasUnprovableHistoricalTuple(
    original: WorkflowStateSnapshot,
    inputs: EffectiveGatePolicyInputs,
  ): Boolean {
    val descriptor =
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(original.artifacts) ?: return false
    val recorded = codec.decode(JsonCodec.valueToJsonString(descriptor).toByteArray(Charsets.UTF_8))
    val audit = recorded.selectedStrategies.singleOrNull { it.slot == PhaseSlot.AUDIT } ?: return false
    val composition = composition(recorded) ?: return false
    return !AcceptanceCriterionRepairHistoricalTuples.matches(recorded, inputs, composition, codec)
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
    val output = phaseOutputVersion(discovery.outputArtifact) != null
    return known && completed && history && output && FORBIDDEN_HISTORY.none { it.contains(original.artifacts) }
  }

  private fun phaseOutputVersion(output: String?): String? =
    output?.let {
      when (val result = phaseOutputs.migrate(it)) {
        is FeatureTaskRuntimePhaseOutputMigrationResult.Current -> FEATURE_TASK_RUNTIME_CONTRACT_VERSION
        is FeatureTaskRuntimePhaseOutputMigrationResult.Migrated ->
          result.sourceVersion.takeIf {
            it == FEATURE_TASK_RUNTIME_PREVIOUS_CONTRACT_VERSION &&
              result.targetVersion == FEATURE_TASK_RUNTIME_CONTRACT_VERSION
          }
        is FeatureTaskRuntimePhaseOutputMigrationResult.Refused -> null
      }
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
      it.checkpointRef == proof.checkpointId && it.phaseId == audit.entryStep
    } ?: return false
    val (historicalTree, historicalCheckpoint) = discoveryRepositoryBinding(original, audit) ?: return false
    if (historicalTree != proof.treeDigest || historicalCheckpoint != proof.checkpointId) return false
    val resolved = git.resolveCheckpointRef(
      repositoryRoot,
      FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE,
      proof.checkpointId,
    )
    val fingerprint = git.repositoryFingerprint(repositoryRoot)
    return resolved is WorkflowGitOperationResult.Ok && resolved.value.trim() == checkpoint.commitSha &&
      fingerprint is WorkflowGitOperationResult.Ok && fingerprint.value.trim() == historicalTree &&
      recorded.selectedStrategies.singleOrNull { it.slot == PhaseSlot.AUDIT } == audit
  }

  private fun sourceBindingAtDiscovery(
    original: WorkflowStateSnapshot,
    worker: AcquiredFeatureTaskRuntimeWorker,
    audit: ResolvedPhaseStrategyIdentity,
    binding: AcceptanceCriterionRepairBinding,
    proof: AcceptanceCriterionRepairLegacyProof,
    repositoryRoot: Path,
  ): DiscoverySourceBinding? {
    if (!worker.goalChild) {
      val invariants = decodeRunInvariantsFromArtifact(
        DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_RUN_INVARIANTS.value(original.artifacts),
      ) ?: return null
      val root = repositoryRoot.toAbsolutePath().normalize()
      val specFile = try {
        Path.of(worker.governedSpecPath).let { if (it.isAbsolute) it.normalize() else root.resolve(it).normalize() }
      } catch (_: InvalidPathException) {
        return null
      }
      val invariantPath = try {
        Path.of(invariants.specReference).toAbsolutePath().normalize()
      } catch (_: InvalidPathException) {
        return null
      }
      if (!specFile.startsWith(root) || invariantPath != specFile) return null
      val relativeSpec = root.relativize(specFile).toString().replace('\\', '/')
      val checkpointRecord = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_CHECKPOINT_IDENTITIES
        .value(original.artifacts)?.let(::decodeCheckpointIdentitiesFromArtifact)?.singleOrNull {
          it.checkpointRef == proof.checkpointId && it.phaseId == audit.entryStep
        } ?: return null
      val resolved = git.resolveCheckpointRef(repositoryRoot, FEATURE_TASK_RUNTIME_CHECKPOINT_REF_NAMESPACE, proof.checkpointId)
      if (resolved !is WorkflowGitOperationResult.Ok || resolved.value.trim() != checkpointRecord.commitSha) return null
      val read = git.readRegularFileAtCommit(
        repositoryRoot,
        checkpointRecord.commitSha,
        relativeSpec,
        MAX_STANDALONE_SPEC_BYTES,
      )
      if (read !is WorkflowGitOperationResult.Ok) return null
      val parsed = GovernedSpecSectionParser.parseListSection(read.value) {
        it.startsWith(ACCEPTANCE_CRITERIA_PREFIX)
      }
      val digest = sha256Hex(read.value)
      return DiscoverySourceBinding(
        digest,
        AcceptanceCriterionRepairStandaloneSourceProof(
          worker.repositoryIdentity,
          worker.normalizedIssueKey,
          relativeSpec,
          checkpointRecord.commitSha,
          read.value,
        ),
        parsed,
      )
    }
    val imported = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_PLANNING_IMPORT.value(original.artifacts)
      ?: return null
    val evidence =
      FeatureTaskRuntimeGoalPlanningImport.fromArtifactMap(FeatureTaskRuntimeWorkflowArtifactMap.from(imported))
    val child = decodeGoalContinuationArtifactFromArtifact(
      DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_GOAL_CONTINUATION.value(original.artifacts),
    ) ?: return null
    val discovery = decodePhaseRecords(original.artifacts)[audit.entryStep] ?: return null
    if (evidence.parentGoalWorkflowId != child.parentWorkflowId ||
      evidence.normalizedIssueKey != child.issueKey || evidence.normalizedIssueKey != worker.normalizedIssueKey ||
      evidence.repositoryIdentity != worker.repositoryIdentity || evidence.subtaskId != child.subtaskId ||
      evidence.governedSubSpecPath != worker.governedSpecPath || discovery.status != WorkflowStepStatus.COMPLETED
    ) return null
    return DiscoverySourceBinding(evidence.subSpecHash, null, null)
  }

  private fun discoveryRepositoryBinding(
    original: WorkflowStateSnapshot,
    audit: ResolvedPhaseStrategyIdentity,
  ): Pair<String, String>? {
    val output = decodePhaseRecords(original.artifacts)[audit.entryStep]?.outputArtifact ?: return null
    val payload = when (val result = phaseOutputs.migrate(output)) {
      is FeatureTaskRuntimePhaseOutputMigrationResult.Current -> result.payload
      is FeatureTaskRuntimePhaseOutputMigrationResult.Migrated -> result.payload
      is FeatureTaskRuntimePhaseOutputMigrationResult.Refused -> return null
    }
    val envelope = JsonCodec.parseValue(payload) as? Map<*, *> ?: return null
    if (envelope[SharedPayloadKeys.PHASE_ID] != audit.entryStep ||
      envelope[SharedPayloadKeys.STATUS] != WorkflowStepStatus.COMPLETED.wireValue
    ) return null
    val produced = envelope[SharedPayloadKeys.PRODUCED_OUTPUTS] as? Map<*, *> ?: return null
    val tree = produced[Keys.TREE_DIGEST] as? String ?: return null
    val checkpoint = produced[Keys.CHECKPOINT_ID] as? String ?: return null
    return tree to checkpoint
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
    sourceBinding: DiscoverySourceBinding,
    proof: AcceptanceCriterionRepairLegacyProof,
    phaseOutputVersion: String,
  ): FeatureTaskRuntimeWorkflowArtifactMap {
    val audit = recorded.selectedStrategies.single { it.slot == PhaseSlot.AUDIT }
    val originalJson = JsonCodec.valueToJsonString(original.artifacts)
    return FeatureTaskRuntimeWorkflowArtifactMap.from(
      linkedMapOf(
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
        Keys.SPEC_DIGEST to sourceBinding.specDigest,
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
      ).apply {
        sourceBinding.standaloneProof?.let {
          put(
            Keys.STANDALONE_SOURCE_PROOF,
            mapOf(
              GoalKeys.REPOSITORY_IDENTITY to it.repositoryIdentity,
              GoalKeys.NORMALIZED_ISSUE_KEY to it.normalizedIssueKey,
              DecompositionKeys.SPEC_PATH to it.specPath,
              Keys.CHECKPOINT_COMMIT to it.checkpointCommit,
              Keys.SOURCE_TEXT to it.sourceText,
            ),
          )
        }
      },
    )
  }

  internal fun retainedProofMatches(
    receipt: Map<String, Any?>,
    worker: AcquiredFeatureTaskRuntimeWorker,
    binding: AcceptanceCriterionRepairBinding,
    repositoryRoot: Path,
    current: WorkflowStateSnapshot,
  ): Boolean {
    val originalArtifacts = FeatureTaskRuntimeWorkflowArtifactMap.from(receipt[Keys.ORIGINAL_ARTIFACTS])
    val original = current.copy(artifacts = DurableWorkflowArtifacts.fromMap(originalArtifacts))
    val descriptor = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(original.artifacts)
      ?: return false
    val descriptorBytes = JsonCodec.valueToJsonString(descriptor).toByteArray(Charsets.UTF_8)
    val recorded = codec.decode(descriptorBytes)
    val audit = recorded.selectedStrategies.singleOrNull { it.slot == PhaseSlot.AUDIT } ?: return false
    val generation = (receipt[Keys.STOPPED_OWNER_GENERATION] as? Number)?.toLong() ?: return false
    val tree = receipt[Keys.TREE_DIGEST] as? String ?: return false
    val checkpoint = receipt[Keys.CHECKPOINT_ID] as? String ?: return false
    val proof = AcceptanceCriterionRepairLegacyProof(tree, checkpoint, generation)
    val composition = composition(recorded) ?: return false
    return sourceMatches(worker, repositoryRoot, binding) &&
      AcceptanceCriterionRepairHistoricalTuples.matches(recorded, worker.effectiveInputs, composition, codec) &&
      historyIsPristine(original, recorded, audit) &&
      sourceBindingAtDiscovery(original, worker, audit, binding, proof, repositoryRoot)?.let {
        it.specDigest == binding.specDigest && it.sourceCatalogMatches(binding.authoredCriteria) &&
          it.standaloneProof?.let { sourceProof ->
          receipt[Keys.STANDALONE_SOURCE_PROOF] == mapOf(
            GoalKeys.REPOSITORY_IDENTITY to sourceProof.repositoryIdentity,
            GoalKeys.NORMALIZED_ISSUE_KEY to sourceProof.normalizedIssueKey,
            DecompositionKeys.SPEC_PATH to sourceProof.specPath,
            Keys.CHECKPOINT_COMMIT to sourceProof.checkpointCommit,
            Keys.SOURCE_TEXT to sourceProof.sourceText,
          )
        } ?: receipt[Keys.STANDALONE_SOURCE_PROOF] == null
      } == true &&
      checkpointMatches(original, recorded, audit, proof, repositoryRoot) && catalogMatches(original, binding)
  }

  internal fun historicalEvidenceRefusal(
    original: WorkflowStateSnapshot,
    worker: AcquiredFeatureTaskRuntimeWorker,
    binding: AcceptanceCriterionRepairBinding,
    proof: AcceptanceCriterionRepairLegacyProof,
    repositoryRoot: Path,
  ): AcceptanceCriterionRepairRefusal? {
    return try {
      val descriptor = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(original.artifacts)
        ?: return AcceptanceCriterionRepairRefusal.HISTORICAL_SOURCE_UNPROVABLE
      val recorded = codec.decode(JsonCodec.valueToJsonString(descriptor).toByteArray(Charsets.UTF_8))
      val audit = recorded.selectedStrategies.singleOrNull { it.slot == PhaseSlot.AUDIT }
        ?: return AcceptanceCriterionRepairRefusal.HISTORICAL_SOURCE_UNPROVABLE
      if (!worker.goalChild) {
        val invariants = decodeRunInvariantsFromArtifact(
          DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_RUN_INVARIANTS.value(original.artifacts),
        ) ?: return AcceptanceCriterionRepairRefusal.HISTORICAL_SOURCE_UNPROVABLE
        val root = repositoryRoot.toAbsolutePath().normalize()
        val governed = try {
          Path.of(worker.governedSpecPath).let { if (it.isAbsolute) it.normalize() else root.resolve(it).normalize() }
        } catch (_: InvalidPathException) {
          return AcceptanceCriterionRepairRefusal.HISTORICAL_SOURCE_UNPROVABLE
        }
        val referenced = try {
          Path.of(invariants.specReference).toAbsolutePath().normalize()
        } catch (_: InvalidPathException) {
          return AcceptanceCriterionRepairRefusal.HISTORICAL_SOURCE_UNPROVABLE
        }
        if (!governed.startsWith(root) || governed != referenced) {
          return AcceptanceCriterionRepairRefusal.IDENTITY_CHANGED
        }
      }
      val sourceBinding = sourceBindingAtDiscovery(original, worker, audit, binding, proof, repositoryRoot)
        ?: return AcceptanceCriterionRepairRefusal.HISTORICAL_SOURCE_UNPROVABLE
      if (sourceBinding.specDigest != binding.specDigest ||
        sourceBinding.sourceCriteria?.let { it != binding.authoredCriteria } == true ||
        !sourceMatches(worker, repositoryRoot, binding)
      ) {
        return AcceptanceCriterionRepairRefusal.HISTORICAL_SOURCE_CHANGED
      }
      if (!catalogMatches(original, binding)) return AcceptanceCriterionRepairRefusal.IDENTITY_CHANGED
      val historicalRepository = discoveryRepositoryBinding(original, audit)
        ?: return AcceptanceCriterionRepairRefusal.HISTORICAL_TREE_UNPROVABLE
      if (historicalRepository.first != proof.treeDigest || historicalRepository.second != proof.checkpointId) {
        return AcceptanceCriterionRepairRefusal.HISTORICAL_TREE_CHANGED
      }
      if (!checkpointMatches(original, recorded, audit, proof, repositoryRoot)) {
        return AcceptanceCriterionRepairRefusal.HISTORICAL_TREE_CHANGED
      }
      null
    } catch (_: SkillBillRuntimeException) {
      AcceptanceCriterionRepairRefusal.HISTORICAL_SOURCE_UNPROVABLE
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

  private companion object {
    const val MAX_STANDALONE_SPEC_BYTES = 1_048_576
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

  private data class DiscoverySourceBinding(
    val specDigest: String,
    val standaloneProof: AcceptanceCriterionRepairStandaloneSourceProof?,
    val sourceCriteria: List<String>?,
  ) {
    fun sourceCatalogMatches(criteria: List<String>): Boolean =
      sourceCriteria?.let { it == criteria } ?: (standaloneProof == null)
  }
}
