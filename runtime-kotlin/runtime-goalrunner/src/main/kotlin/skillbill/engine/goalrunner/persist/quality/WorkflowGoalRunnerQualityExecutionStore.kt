package skillbill.engine.goalrunner.persist.quality

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.goal.GOAL_FINALIZATION_HISTORY_CONTRACT_VERSION
import skillbill.contracts.workflow.goal.GOAL_QUALITY_EXECUTION_CONTRACT_VERSION
import skillbill.engine.goalrunner.history.GoalChildHistoryEvidenceStore
import skillbill.engine.goalrunner.model.history.GoalFinalizationHistoryPayloadKeys
import skillbill.engine.goalrunner.persist.GoalRunnerQualityExecutionStore
import skillbill.engine.goalrunner.persist.saveRunnerArtifactPatch
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.WorkflowFailureCode
import skillbill.goalrunner.model.quality.GoalQualityExecution
import skillbill.goalrunner.model.quality.GoalQualityExecutionAdmission
import skillbill.goalrunner.model.quality.GoalQualityExecutionInputs
import skillbill.goalrunner.model.quality.GoalQualityExecutionPlans
import skillbill.goalrunner.model.quality.GoalQualityExecutionProgress
import skillbill.goalrunner.model.quality.GoalQualityExecutionRecord
import skillbill.goalrunner.model.quality.GoalQualityExecutionStatus
import skillbill.goalrunner.model.quality.GoalQualityPhaseProgress
import skillbill.goalrunner.model.quality.GoalQualityReadiness
import skillbill.goalrunner.model.quality.GoalQualityReadiness.CLEAN_UNSTARTED
import skillbill.goalrunner.model.quality.GoalQualityValidationDisposition
import skillbill.ports.db.DatabaseSessionFactory
import skillbill.ports.persistence.UnitOfWork
import skillbill.ports.process.IdentifierGeneratorPort
import skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator
import skillbill.ports.workflow.WorkflowSnapshotValidator
import skillbill.ports.workflow.model.WorkflowFamily
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.engine.WorkflowEngine
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.engine.model.WorkflowStateSnapshot
import skillbill.workflow.model.DecompositionStatus
import skillbill.workflow.model.decompositionStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWireArtifactKind
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.skeleton.SkeletonDefinition
import java.io.IOException
import java.security.MessageDigest
import java.sql.SQLException
import kotlin.coroutines.cancellation.CancellationException

public class WorkflowGoalRunnerQualityExecutionStore
  @Inject
  public constructor(
    private val database: DatabaseSessionFactory,
    private val snapshotValidator: WorkflowSnapshotValidator,
    private val artifactValidator: FeatureTaskRuntimeWireArtifactValidator,
    private val identifiers: IdentifierGeneratorPort,
    private val historyEvidence: GoalChildHistoryEvidenceStore,
  ) : GoalRunnerQualityExecutionStore {
    private val engine = WorkflowEngine()

    override fun goalQualityExecution(parentWorkflowId: String): GoalQualityExecutionRecord? =
      withQualityPersistence {
        database.read { unitOfWork ->
          val parent = parent(unitOfWork, parentWorkflowId)
          val family = DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION
          if (!family.contains(parent.artifacts)) {
            null
          } else {
            decode(family.value(parent.artifacts)).also { validateParentIdentity(it, parentWorkflowId) }
          }
        }
      }

    override fun chooseFreshGoalQualityExecution(parentWorkflowId: String): Boolean =
      withQualityPersistence {
        database.transaction { unitOfWork ->
          val parent = parent(unitOfWork, parentWorkflowId)
          val payload =
            DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION.value(parent.artifacts)
              ?: return@transaction false
          val current = decode(payload)
          validateParentIdentity(current, parentWorkflowId)
          if (current.readiness != GoalQualityReadiness.HISTORICAL_AMBIGUITY) return@transaction false
          persist(unitOfWork, parent, current.copy(readiness = CLEAN_UNSTARTED, execution = null))
          true
        }
      }

    override fun resolvedGoalQualityChildIds(
      parentWorkflowId: String,
      expectedSelection: List<Pair<Int, String?>>,
    ): List<String> =
      withQualityPersistence {
        database.read { unitOfWork ->
          val parent = parent(unitOfWork, parentWorkflowId)
          val manifest = parent.decompositionRuntime() ?: invalid("Parent has no durable goal manifest.")
          if (manifest.subtasks.map { it.id to it.workflowId } != expectedSelection) {
            invalid("The goal child selection changed while final quality was being admitted.")
          }
          manifest.subtasks.mapNotNull { subtask ->
            subtask.workflowId ?: historyEvidence.restoredChildId(unitOfWork, parentWorkflowId, subtask.id)
          }
        }
      }

    override fun admitGoalQualityExecution(
      parentWorkflowId: String,
      baselineContentFingerprint: String,
      governingInputs: GoalQualityExecutionInputs,
      executionPlans: GoalQualityExecutionPlans,
    ): GoalQualityExecutionRecord =
      withQualityPersistence {
        database.transaction { unitOfWork ->
          val parent = parent(unitOfWork, parentWorkflowId)
          val family = DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION
          val record =
            family.value(parent.artifacts)?.let(::decode)
              ?: GoalQualityExecutionRecord(readiness = CLEAN_UNSTARTED)
          validateParentIdentity(record, parentWorkflowId)
          if (record.readiness == GoalQualityReadiness.HISTORICAL_AMBIGUITY) {
            invalid(
              "This parent has ambiguous historical quality state. An operator must " +
                "decide whether to start a fresh quality execution " +
                "(skill-bill goal fresh-quality <issue-key> --reason \"<why>\") or provide " +
                "authoritative original evidence.",
            )
          }
          record.execution?.takeIf { it.status != GoalQualityExecutionStatus.RETIRED }?.let { active ->
            val expectedFingerprints =
              selectionFingerprint(
                unitOfWork,
                parent,
              ) to governingInputFingerprint(governingInputs)
            val expectedInputs = governingInputs to executionPlans
            if ((active.selectionFingerprint to active.governingInputFingerprint) != expectedFingerprints ||
              (active.governingInputs to active.executionPlans) != expectedInputs
            ) {
              invalid(
                "The admitted goal quality execution inputs or accepted plans changed; " +
                  "a new invocation requires an explicit reset.",
              )
            }
          }
          val admitted =
            record.admit(
              GoalQualityExecutionAdmission(
                parentWorkflowId,
                "phr-${identifiers.randomToken()}",
                baselineContentFingerprint,
                selectionFingerprint(unitOfWork, parent),
                governingInputFingerprint(governingInputs),
                governingInputs,
                executionPlans,
              ),
            )
          if (admitted != record) {
            persist(unitOfWork, parent, admitted)
            ensureNewInvocationRepairContext(unitOfWork, parent(unitOfWork, parentWorkflowId))
          }
          admitted
        }
      }

    override fun goalQualityPhaseProgress(
      parentWorkflowId: String,
      invocationId: String,
      definitionId: String,
      acceptedPlan: String,
    ): GoalQualityPhaseProgress =
      withQualityPersistence {
        database.read { unitOfWork ->
          val parent = parent(unitOfWork, parentWorkflowId)
          val record = decode(DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION.value(parent.artifacts))
          val execution = record.execution ?: invalid("Admitted quality execution is absent.")
          val plans = execution.executionPlans ?: invalid("Admitted quality execution plans are absent.")
          if (execution.invocationId != invocationId || execution.status != progressOwnerStatus(definitionId)) {
            invalid("The goal-quality phase belongs to a retired or different invocation.")
          }
          val selected = selectedPlan(plans, definitionId)
          if (selected != acceptedPlan) invalid("The goal-quality phase plan does not match its admitted descriptor.")
          progressFor(requireNotNull(execution.progress), definitionId)
        }
      }

    override fun persistGoalQualityPhaseProgress(
      parentWorkflowId: String,
      invocationId: String,
      definitionId: String,
      acceptedPlan: String,
      progress: GoalQualityPhaseProgress,
    ): Boolean =
      withQualityPersistence {
        validatePhaseProgress(progress)
        database.transaction { unitOfWork ->
          val parent = parent(unitOfWork, parentWorkflowId)
          val family = DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION
          val payload = family.value(parent.artifacts) ?: return@transaction false
          val current = decode(payload)
          val execution = current.execution ?: return@transaction false
          val plans = execution.executionPlans ?: return@transaction false
          if (execution.invocationId != invocationId || execution.status != progressOwnerStatus(definitionId) ||
            selectedPlan(plans, definitionId) != acceptedPlan
          ) {
            return@transaction false
          }
          val existing = progressFor(requireNotNull(execution.progress), definitionId)
          if (existing == progress) return@transaction true
          if (existing.terminalCompleted || existing.terminalBlockedStep != null) return@transaction false
          val updatedProgress = nextPhaseProgress(existing, progress) ?: return@transaction false
          val updated =
            updateProgress(
              current,
              definitionId,
              updatedProgress,
            )
          persist(unitOfWork, parent, updated)
          true
        }
      }

    private fun nextPhaseProgress(
      existing: GoalQualityPhaseProgress,
      progress: GoalQualityPhaseProgress,
    ): GoalQualityPhaseProgress? {
      val terminalUpdate = progress.terminalCompleted || progress.terminalBlockedStep != null
      return if (terminalUpdate) {
        val sameAcceptedState =
          progress.copy(
            terminalBlockedStep = existing.terminalBlockedStep,
            terminalBlockedReason = existing.terminalBlockedReason,
            terminalCompleted = existing.terminalCompleted,
          ) == existing
        if (progress.revision != existing.revision || !sameAcceptedState) return null
        existing.copy(
          terminalBlockedStep = progress.terminalBlockedStep,
          terminalBlockedReason = progress.terminalBlockedReason,
          terminalCompleted = progress.terminalCompleted,
          revision = existing.revision + 1,
        )
      } else {
        if (progress.revision != existing.revision + 1) return null
        progress
      }
    }

    private fun governingInputFingerprint(inputs: GoalQualityExecutionInputs): String {
      val json = JsonCodec.mapToJsonString(inputs.toPersistenceWire())
      return MessageDigest.getInstance("SHA-256")
        .digest(json.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    }

    private fun selectedPlan(
      plans: GoalQualityExecutionPlans,
      definitionId: String,
    ): String =
      when (definitionId) {
        SkeletonDefinition.GOAL_QA.id -> plans.qa
        SkeletonDefinition.VALIDATION.id -> plans.validation
        else -> invalid("Unsupported goal-quality phase '$definitionId'.")
      }

    private fun progressOwnerStatus(definitionId: String): GoalQualityExecutionStatus =
      when (definitionId) {
        SkeletonDefinition.GOAL_QA.id -> GoalQualityExecutionStatus.QA_PENDING
        SkeletonDefinition.VALIDATION.id -> GoalQualityExecutionStatus.VALIDATION_RUNNING
        else -> invalid("Unsupported goal-quality phase '$definitionId'.")
      }

    private fun progressFor(
      progress: GoalQualityExecutionProgress,
      definitionId: String,
    ): GoalQualityPhaseProgress =
      when (definitionId) {
        SkeletonDefinition.GOAL_QA.id -> progress.qa
        SkeletonDefinition.VALIDATION.id -> progress.validation
        else -> invalid("Unsupported goal-quality phase '$definitionId'.")
      }

    private fun updateProgress(
      record: GoalQualityExecutionRecord,
      definitionId: String,
      progress: GoalQualityPhaseProgress,
    ): GoalQualityExecutionRecord {
      val execution = record.execution ?: invalid("Admitted quality execution is absent.")
      val current = execution.progress ?: invalid("Admitted quality execution phase progress is absent.")
      val updated =
        when (definitionId) {
          SkeletonDefinition.GOAL_QA.id -> current.copy(qa = progress)
          SkeletonDefinition.VALIDATION.id -> current.copy(validation = progress)
          else -> invalid("Unsupported goal-quality phase '$definitionId'.")
        }
      return record.copy(execution = execution.copy(progress = updated))
    }

    private fun validatePhaseProgress(progress: GoalQualityPhaseProgress) {
      val invalidRecords = progress.phaseRecords.any { (phase, record) -> phase != record.phaseId }
      val invalidLedger =
        progress.phaseLedger.map { it.sequenceNumber }.distinct().size != progress.phaseLedger.size ||
          progress.phaseLedger.zipWithNext().any { (left, right) -> right.sequenceNumber <= left.sequenceNumber }
      val invalidOutputs =
        progress.acceptedOutputs.any { it.phaseId.isBlank() || it.occurrence < 1 } ||
          progress.acceptedOutputs.map { it.phaseId to it.occurrence }.distinct().size != progress.acceptedOutputs.size
      val invalidEnvelopes =
        progress.acceptedEnvelopes.any {
          it.phaseId.isBlank() || it.attempt < 1 || !it.envelope.isObject
        } ||
          progress.acceptedEnvelopes.map { it.phaseId to it.attempt }.distinct().size != progress.acceptedEnvelopes.size
      if (listOf(
          invalidRecords,
          invalidLedger,
          invalidOutputs,
          invalidEnvelopes,
          invalidGateKeys(progress),
          invalidTerminalProgress(progress),
        ).any { it }
      ) {
        invalid("Goal-quality phase progress has conflicting identities or terminal state.")
      }
    }

    internal fun retireForSelectionChange(
      unitOfWork: UnitOfWork,
      parentWorkflowId: String,
    ): Boolean =
      withQualityPersistence {
        val parent = parent(unitOfWork, parentWorkflowId)
        val family = DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION
        val payload = family.value(parent.artifacts) ?: return@withQualityPersistence false
        val current = decode(payload)
        validateParentIdentity(current, parentWorkflowId)
        val retired = current.retire()
        if (retired == current) return@withQualityPersistence false
        persist(unitOfWork, parent, retired)
        val refreshedParent = parent(unitOfWork, parentWorkflowId)
        val existingHistory = DurableWorkflowArtifactFamily.GOAL_FINALIZATION_HISTORY.value(refreshedParent.artifacts)
        if (existingHistory != null) {
          val source =
            JsonCodec.anyToStringAnyMap(existingHistory)
              ?: invalidFinalHistory("Finalization history is not an object.")
          val upgraded = GoalFinalizationHistoryMigration.upgrade(source)
          validateFinalizationHistory(upgraded)
          if (upgraded != source) {
            engine.saveRunnerArtifactPatch(
              unitOfWork.workflowStates,
              WorkflowFamily.TASK_RUNTIME,
              refreshedParent,
              mapOf(DurableWorkflowArtifactFamily.GOAL_FINALIZATION_HISTORY.entry(upgraded)),
            )
          }
        }
        val finalHistory =
          mapOf(
            GoalFinalizationHistoryPayloadKeys.CONTRACT_VERSION to GOAL_FINALIZATION_HISTORY_CONTRACT_VERSION,
            GoalFinalizationHistoryPayloadKeys.REPAIR_CONTEXT to null,
            GoalFinalizationHistoryPayloadKeys.COMPLETION to null,
          )
        artifactValidator.validate(
          FeatureTaskRuntimeWireArtifactKind.GOAL_FINALIZATION_HISTORY,
          FeatureTaskRuntimeWorkflowArtifactMap.from(finalHistory),
          DurableWorkflowArtifactFamily.GOAL_FINALIZATION_HISTORY.label(),
        )
        engine.saveRunnerArtifactPatch(
          unitOfWork.workflowStates,
          WorkflowFamily.TASK_RUNTIME,
          refreshedParent,
          mapOf(DurableWorkflowArtifactFamily.GOAL_FINALIZATION_HISTORY.entry(finalHistory)),
        )
        true
      }

    override fun transitionGoalQualityExecution(
      parentWorkflowId: String,
      expectedInvocationId: String,
      expected: GoalQualityExecutionRecord,
      next: GoalQualityExecutionRecord,
    ): Boolean =
      withQualityPersistence {
        database.transaction { unitOfWork ->
          val parent = parent(unitOfWork, parentWorkflowId)
          val family = DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION
          val payload = family.value(parent.artifacts) ?: invalid("Admitted quality execution is absent.")
          val current = decode(payload)
          validateParentIdentity(current, parentWorkflowId)
          val execution = current.execution ?: invalid("Admitted quality execution is absent.")
          if (execution.invocationId == expectedInvocationId && next == current) return@transaction true
          if (execution.invocationId != expectedInvocationId || !sameOuterSnapshot(current, expected)) {
            return@transaction false
          }
          val nextExecution = next.execution ?: return@transaction false
          val nextWithLatestProgress =
            next.copy(
              execution = nextExecution.copy(progress = execution.progress),
            )
          if (!validTransition(current, nextWithLatestProgress)) return@transaction false
          val terminal =
            nextWithLatestProgress.execution?.status in
              setOf(
                GoalQualityExecutionStatus.SETTLED_PASSED,
                GoalQualityExecutionStatus.SETTLED_SKIPPED,
                GoalQualityExecutionStatus.SETTLED_WARNED,
              )
          if (terminal && !hasFinalRepairContext(unitOfWork, parent)) return@transaction false
          persist(unitOfWork, parent(unitOfWork, parentWorkflowId), nextWithLatestProgress)
          true
        }
      }

    private fun parent(
      unitOfWork: UnitOfWork,
      parentWorkflowId: String,
    ): WorkflowStateSnapshot {
      val snapshot =
        unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, parentWorkflowId)
          ?: invalid("Parent workflow '$parentWorkflowId' is missing.")
      snapshotValidator.validate(snapshot, WorkflowFamily.TASK_RUNTIME.definition.workflowName)
      return snapshot
    }

    private fun selectionFingerprint(
      unitOfWork: UnitOfWork,
      parent: WorkflowStateSnapshot,
    ): String {
      val manifest = parent.decompositionRuntime() ?: invalid("Parent has no durable goal manifest.")
      val retainedEvidence = historyEvidence.read(unitOfWork, parent.workflowId)
      val identity =
        buildList {
          add(parent.workflowId)
          manifest.subtasks.forEach { subtask ->
            add(subtask.id.toString())
            val childId =
              subtask.workflowId ?: historyEvidence.restoredChildId(unitOfWork, parent.workflowId, subtask.id)
            if (childId == null && subtask.status.decompositionStatus() == DecompositionStatus.SKIPPED) {
              add(DecompositionStatus.SKIPPED.wireValue)
              return@forEach
            }
            val selectedChildId = childId ?: invalid("Selected subtask '${subtask.id}' has no recorded child identity.")
            add(selectedChildId)
            val child = unitOfWork.workflowStates.get(WorkflowFamily.TASK_RUNTIME, selectedChildId)
            val currentPlan =
              child?.let {
                JsonCodec.anyToStringAnyMap(
                  DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_EXECUTION_PLAN.value(it.artifacts),
                )
              }
            val retained =
              retainedEvidence.singleOrNull {
                it.subtaskId == subtask.id && it.childWorkflowId == selectedChildId
              }
            val plan = currentPlan ?: retained?.executionPlan
            val definition = historyEvidence.definition(plan)
            val retainedDefinition = retained?.executionPlan?.let(historyEvidence::definition)
            if (currentPlan != null && retainedDefinition != null && retainedDefinition != definition) {
              invalid("Selected child '$selectedChildId' conflicts with its preserved definition.")
            }
            add(definition.id)
            add(definition.semanticRevision.toString())
          }
        }.joinToString("\u0000")
      return MessageDigest.getInstance("SHA-256")
        .digest(identity.toByteArray())
        .joinToString("") { "%02x".format(it) }
    }

    private fun decode(value: Any?): GoalQualityExecutionRecord {
      val map = JsonCodec.anyToStringAnyMap(value) ?: invalid("Stored goal quality execution is not an object.")
      validate(map)
      val decoded = GoalQualityExecutionRecord.fromPersistenceWire(map)
      val missingRetainedExecution = decoded.execution?.executionPlans == null || decoded.execution?.progress == null
      val record =
        if (decoded.readiness == GoalQualityReadiness.ADMITTED &&
          decoded.execution?.status != GoalQualityExecutionStatus.RETIRED &&
          missingRetainedExecution
        ) {
          decoded.copy(
            contractVersion = GOAL_QUALITY_EXECUTION_CONTRACT_VERSION,
            readiness = GoalQualityReadiness.HISTORICAL_AMBIGUITY,
            execution = null,
          )
        } else {
          decoded.copy(contractVersion = GOAL_QUALITY_EXECUTION_CONTRACT_VERSION)
        }
      if ((record.readiness == GoalQualityReadiness.ADMITTED) != (record.execution != null)) {
        invalid("Stored goal quality readiness and admitted execution disagree.")
      }
      if (record.readiness == GoalQualityReadiness.HISTORICAL_AMBIGUITY && record.execution != null) {
        invalid("Historical ambiguity cannot carry a synthesized execution.")
      }
      record.execution?.let { execution ->
        validateExecutionInputs(execution)
        validateExecutionIdentity(execution)
        validateExecutionDisposition(execution)
      }
      return record
    }

    private fun validateExecutionInputs(execution: GoalQualityExecution) {
      if (execution.status == GoalQualityExecutionStatus.RETIRED) return
      val inputs =
        execution.governingInputs
          ?: invalid("An active quality execution has no retained governing inputs.")
      val blankRequired =
        listOf(
          inputs.issueKey,
          inputs.repoRoot,
          inputs.invokedAgentId,
          inputs.parentSpecPath,
          inputs.branch,
        ).any(String::isBlank)
      val blankOptional =
        listOfNotNull(
          inputs.configuredAgentOverrideId,
          inputs.acceptanceCriteriaReadFailure,
        ).any(String::isBlank)
      val invalidTimeout = inputs.timeoutMillis?.let { it <= 0 } == true
      val invalidIdentity = blankRequired || blankOptional
      if (invalidIdentity || invalidTimeout || execution.governingInputFingerprint !=
        governingInputFingerprint(
          inputs,
        )
      ) {
        invalid("Stored quality governing inputs have a blank value or do not match their fingerprint.")
      }
    }

    private fun validateExecutionIdentity(execution: GoalQualityExecution) {
      val blankRequired =
        listOf(
          execution.parentWorkflowId,
          execution.invocationId,
          execution.baselineContentFingerprint,
        ).any(String::isBlank)
      val blankOptional =
        listOfNotNull(
          execution.warning,
          execution.selectionFingerprint,
          execution.settledContentFingerprint,
          execution.postQaContentFingerprint,
          execution.qaTestedTreeIdentity,
        ).any(String::isBlank)
      val missingFingerprint =
        execution.status != GoalQualityExecutionStatus.RETIRED &&
          execution.governingInputFingerprint.isNullOrBlank()
      if (blankRequired || blankOptional || missingFingerprint) {
        invalid("Stored goal quality execution has a blank identity, baseline, or warning.")
      }
      if (execution.status != GoalQualityExecutionStatus.RETIRED &&
        (execution.executionPlans == null || execution.progress == null)
      ) {
        invalid("An admitted quality execution has no accepted plans or phase progress.")
      }
    }

    private fun validateExecutionDisposition(execution: GoalQualityExecution) {
      val hasFingerprint = execution.settledContentFingerprint != null
      val hasWarning = execution.warning != null
      val noValidation = execution.validation == GoalQualityValidationDisposition.NOT_REQUIRED
      val passedValidation = noValidation || execution.validation == GoalQualityValidationDisposition.COMPLETED
      val warnedValidation =
        noValidation ||
          execution.validation == GoalQualityValidationDisposition.DELIBERATELY_WARNED
      val valid =
        when (execution.status) {
          GoalQualityExecutionStatus.SETTLED_PASSED -> passedValidation && !hasWarning && hasFingerprint
          GoalQualityExecutionStatus.SETTLED_SKIPPED -> noValidation && !hasWarning && hasFingerprint
          GoalQualityExecutionStatus.SETTLED_WARNED -> hasWarning && hasFingerprint && warnedValidation
          else -> {
            validatePendingDisposition(execution)
            true
          }
        }
      if (!valid) invalid("A settled quality execution has an invalid validation, warning or fingerprint.")
    }

    private fun validatePendingDisposition(execution: GoalQualityExecution) {
      val validation = execution.validation
      val noValidation = validation == GoalQualityValidationDisposition.NOT_REQUIRED
      val decisionPending = validation == GoalQualityValidationDisposition.DECISION_PENDING
      val validationRequired = validation == GoalQualityValidationDisposition.REQUIRED_PENDING
      val running = validation == GoalQualityValidationDisposition.RUNNING
      val completed = validation == GoalQualityValidationDisposition.COMPLETED
      val warned = validation == GoalQualityValidationDisposition.DELIBERATELY_WARNED
      val valid =
        when (execution.status) {
          GoalQualityExecutionStatus.QA_PENDING -> decisionPending
          GoalQualityExecutionStatus.VALIDATION_DECISION_PENDING -> decisionPending || noValidation
          GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING -> validationRequired
          GoalQualityExecutionStatus.VALIDATION_RUNNING -> running
          GoalQualityExecutionStatus.VALIDATION_COMPLETED_PENDING_SETTLEMENT -> completed || warned
          else -> true
        }
      if (!valid) invalid("A pending quality execution has an invalid validation disposition.")
    }

    private fun validateParentIdentity(
      record: GoalQualityExecutionRecord,
      parentWorkflowId: String,
    ) {
      val execution = record.execution ?: return
      if (execution.parentWorkflowId != parentWorkflowId) {
        invalid(
          "Stored goal quality execution belongs to '${execution.parentWorkflowId}', " +
            "not '$parentWorkflowId'.",
        )
      }
    }

    private fun validTransition(
      current: GoalQualityExecutionRecord,
      next: GoalQualityExecutionRecord,
    ): Boolean {
      val prior = current.execution ?: return false
      val candidate = next.execution ?: return false
      val sameRecord =
        next.readiness == GoalQualityReadiness.ADMITTED && next.contractVersion == current.contractVersion
      val allowedStatus = candidate.warning?.isBlank() != true && candidate.status in allowedNextStatuses(prior.status)
      val sameIdentity = sameExecutionIdentity(prior, candidate)
      val validObservation = validQaObservation(prior, candidate) && validSettledFingerprint(prior, candidate)
      return sameRecord && allowedStatus && sameIdentity && validObservation &&
        validCandidateDisposition(prior, candidate)
    }

    private fun validCandidateDisposition(
      prior: GoalQualityExecution,
      candidate: GoalQualityExecution,
    ): Boolean {
      return when (candidate.status) {
        GoalQualityExecutionStatus.QA_PENDING,
        GoalQualityExecutionStatus.RETIRED,
        -> false
        GoalQualityExecutionStatus.VALIDATION_DECISION_PENDING ->
          candidate.validation in
            setOf(
              GoalQualityValidationDisposition.DECISION_PENDING,
              GoalQualityValidationDisposition.NOT_REQUIRED,
            ) && (prior.status == GoalQualityExecutionStatus.QA_PENDING || candidate.warning == prior.warning)
        GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING ->
          candidate.validation == GoalQualityValidationDisposition.REQUIRED_PENDING &&
            (prior.status == GoalQualityExecutionStatus.QA_PENDING || candidate.warning == prior.warning)
        GoalQualityExecutionStatus.VALIDATION_RUNNING ->
          candidate.validation == GoalQualityValidationDisposition.RUNNING && candidate.warning == prior.warning
        GoalQualityExecutionStatus.VALIDATION_COMPLETED_PENDING_SETTLEMENT ->
          candidate.validation in
            setOf(
              GoalQualityValidationDisposition.COMPLETED,
              GoalQualityValidationDisposition.DELIBERATELY_WARNED,
            ) &&
            if (candidate.validation == GoalQualityValidationDisposition.DELIBERATELY_WARNED) {
              candidate.warning != null
            } else {
              candidate.warning == prior.warning
            }
        else -> validTerminalDisposition(candidate)
      }
    }

    private fun validTerminalDisposition(candidate: GoalQualityExecution): Boolean =
      when (candidate.status) {
        GoalQualityExecutionStatus.SETTLED_PASSED ->
          candidate.validation in
            setOf(
              GoalQualityValidationDisposition.NOT_REQUIRED,
              GoalQualityValidationDisposition.COMPLETED,
            ) && candidate.warning == null
        GoalQualityExecutionStatus.SETTLED_SKIPPED ->
          candidate.validation == GoalQualityValidationDisposition.NOT_REQUIRED && candidate.warning == null
        GoalQualityExecutionStatus.SETTLED_WARNED ->
          candidate.validation in
            setOf(
              GoalQualityValidationDisposition.NOT_REQUIRED,
              GoalQualityValidationDisposition.DELIBERATELY_WARNED,
            ) && candidate.warning != null
        else -> false
      }

    private fun sameExecutionIdentity(
      prior: GoalQualityExecution,
      candidate: GoalQualityExecution,
    ): Boolean =
      prior.copy(
        status = candidate.status,
        validation = candidate.validation,
        warning = candidate.warning,
        settledContentFingerprint = candidate.settledContentFingerprint,
        postQaContentFingerprint = candidate.postQaContentFingerprint,
        qaTestedTreeIdentity = candidate.qaTestedTreeIdentity,
        skipReason = candidate.skipReason,
        progress = candidate.progress,
      ) == candidate

    private fun validQaObservation(
      prior: GoalQualityExecution,
      candidate: GoalQualityExecution,
    ): Boolean =
      if (prior.status == GoalQualityExecutionStatus.QA_PENDING) {
        val hasObservation = !candidate.postQaContentFingerprint.isNullOrBlank()
        val hasTestedTree = !candidate.qaTestedTreeIdentity.isNullOrBlank()
        val justified = hasTestedTree || !candidate.warning.isNullOrBlank() || candidate.skipReason != null
        val requiresObservation =
          hasTestedTree || candidate.status == GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING
        justified && (!requiresObservation || hasObservation)
      } else {
        candidate.skipReason == prior.skipReason &&
          candidate.postQaContentFingerprint == prior.postQaContentFingerprint &&
          candidate.qaTestedTreeIdentity == prior.qaTestedTreeIdentity
      }

    private fun validSettledFingerprint(
      prior: GoalQualityExecution,
      candidate: GoalQualityExecution,
    ): Boolean {
      val terminal =
        candidate.status in
          setOf(
            GoalQualityExecutionStatus.SETTLED_PASSED,
            GoalQualityExecutionStatus.SETTLED_SKIPPED,
            GoalQualityExecutionStatus.SETTLED_WARNED,
          )
      return terminal == (candidate.settledContentFingerprint != null) &&
        (terminal || candidate.settledContentFingerprint == prior.settledContentFingerprint)
    }

    private fun sameOuterSnapshot(
      current: GoalQualityExecutionRecord,
      expected: GoalQualityExecutionRecord,
    ): Boolean =
      current.copy(
        execution = current.execution?.copy(progress = null),
      ) ==
        expected.copy(
          execution = expected.execution?.copy(progress = null),
        )

    private fun persist(
      unitOfWork: UnitOfWork,
      parent: WorkflowStateSnapshot,
      record: GoalQualityExecutionRecord,
    ) {
      val payload = record.toPersistenceWire()
      validate(payload)
      engine.saveRunnerArtifactPatch(
        unitOfWork.workflowStates,
        WorkflowFamily.TASK_RUNTIME,
        parent,
        mapOf(DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION.entry(payload)),
      )
    }

    private fun ensureNewInvocationRepairContext(
      unitOfWork: UnitOfWork,
      parent: WorkflowStateSnapshot,
    ) {
      val family = DurableWorkflowArtifactFamily.GOAL_FINALIZATION_HISTORY
      val existing = family.value(parent.artifacts)
      val source =
        existing?.let {
          JsonCodec.anyToStringAnyMap(it)
            ?: throw SkillBillRuntimeException(
              WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY,
              "Stored finalization history is not an object.",
            )
        }
          ?: mapOf(
            GoalFinalizationHistoryPayloadKeys.CONTRACT_VERSION to GOAL_FINALIZATION_HISTORY_CONTRACT_VERSION,
            GoalFinalizationHistoryPayloadKeys.REPAIR_CONTEXT to null,
            GoalFinalizationHistoryPayloadKeys.COMPLETION to null,
          )
      val payload = GoalFinalizationHistoryMigration.upgrade(source)
      if (existing != null) {
        validateFinalizationHistory(payload)
      }
      val context = payload[GoalFinalizationHistoryPayloadKeys.REPAIR_CONTEXT]
      val initialized =
        if (context != null) {
          payload
        } else {
          payload +
            mapOf(
              GoalFinalizationHistoryPayloadKeys.REPAIR_CONTEXT to
                mapOf(
                  GoalFinalizationHistoryPayloadKeys.OUTPUTS to emptyList<Any>(),
                  GoalFinalizationHistoryPayloadKeys.WARNING to null,
                ),
              GoalFinalizationHistoryPayloadKeys.COMPLETION to null,
            )
        }
      validateFinalizationHistory(initialized)
      if (initialized == source) return
      engine.saveRunnerArtifactPatch(
        unitOfWork.workflowStates,
        WorkflowFamily.TASK_RUNTIME,
        parent,
        mapOf(family.entry(initialized)),
      )
    }

    private fun hasFinalRepairContext(
      unitOfWork: UnitOfWork,
      parent: WorkflowStateSnapshot,
    ): Boolean {
      val payload = DurableWorkflowArtifactFamily.GOAL_FINALIZATION_HISTORY.value(parent.artifacts) ?: return false
      val map = JsonCodec.anyToStringAnyMap(payload) ?: invalid("Finalization history is not an object.")
      val upgraded = GoalFinalizationHistoryMigration.upgrade(map)
      validateFinalizationHistory(upgraded)
      if (upgraded != map) {
        engine.saveRunnerArtifactPatch(
          unitOfWork.workflowStates,
          WorkflowFamily.TASK_RUNTIME,
          parent,
          mapOf(DurableWorkflowArtifactFamily.GOAL_FINALIZATION_HISTORY.entry(upgraded)),
        )
      }
      return upgraded[GoalFinalizationHistoryPayloadKeys.REPAIR_CONTEXT] != null
    }

    private fun validateFinalizationHistory(payload: Map<String, Any?>) {
      artifactValidator.validate(
        FeatureTaskRuntimeWireArtifactKind.GOAL_FINALIZATION_HISTORY,
        FeatureTaskRuntimeWorkflowArtifactMap.from(payload),
        DurableWorkflowArtifactFamily.GOAL_FINALIZATION_HISTORY.label(),
      )
    }

    private fun invalidFinalHistory(reason: String): Nothing =
      throw SkillBillRuntimeException(WorkflowFailureCode.INVALID_GOAL_FINALIZATION_HISTORY, reason)

    private fun validate(payload: Map<String, Any?>) {
      artifactValidator.validate(
        FeatureTaskRuntimeWireArtifactKind.GOAL_QUALITY_EXECUTION,
        FeatureTaskRuntimeWorkflowArtifactMap.from(payload),
        DurableWorkflowArtifactFamily.GOAL_QUALITY_EXECUTION.label(),
      )
    }

    private fun invalid(reason: String): Nothing =
      throw SkillBillRuntimeException(WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION, reason)

    private fun <T> withQualityPersistence(block: () -> T): T =
      try {
        block()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (failure: SkillBillRuntimeException) {
        throw failure
      } catch (failure: IOException) {
        throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
          "Required goal quality execution persistence operation failed.",
          failure,
        )
      } catch (failure: SQLException) {
        throw SkillBillRuntimeException(
          WorkflowFailureCode.INVALID_GOAL_QUALITY_EXECUTION,
          "Required goal quality execution persistence operation failed.",
          failure,
        )
      }
  }

private fun invalidGateKeys(progress: GoalQualityPhaseProgress): Boolean {
  val build =
    (progress.buildGateProgress == null) != (progress.buildGateProgressKey == null) ||
      progress.buildGateProgressKey?.isBlank() == true
  val validation =
    (progress.validationGateProgress == null) != (progress.validationGateProgressKey == null) ||
      progress.validationGateProgressKey?.isBlank() == true
  return build || validation
}

private fun invalidTerminalProgress(progress: GoalQualityPhaseProgress): Boolean {
  val invalidIdentity =
    progress.revision < 0 || progress.terminalBlockedStep?.isBlank() == true ||
      progress.terminalBlockedReason?.isBlank() == true
  val hasBlock = progress.terminalBlockedStep != null || progress.terminalBlockedReason != null
  val incompleteBlock = (progress.terminalBlockedStep == null) != (progress.terminalBlockedReason == null)
  return invalidIdentity || if (progress.terminalCompleted) hasBlock else incompleteBlock
}

private fun allowedNextStatuses(status: GoalQualityExecutionStatus): Set<GoalQualityExecutionStatus> =
  when (status) {
    GoalQualityExecutionStatus.QA_PENDING ->
      setOf(
        GoalQualityExecutionStatus.VALIDATION_DECISION_PENDING,
        GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING,
      )
    GoalQualityExecutionStatus.VALIDATION_DECISION_PENDING ->
      setOf(
        GoalQualityExecutionStatus.VALIDATION_DECISION_PENDING,
        GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING,
        GoalQualityExecutionStatus.SETTLED_PASSED,
        GoalQualityExecutionStatus.SETTLED_SKIPPED,
        GoalQualityExecutionStatus.SETTLED_WARNED,
      )
    GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING ->
      setOf(
        GoalQualityExecutionStatus.VALIDATION_RUNNING,
        GoalQualityExecutionStatus.SETTLED_WARNED,
      )
    GoalQualityExecutionStatus.VALIDATION_RUNNING ->
      setOf(
        GoalQualityExecutionStatus.VALIDATION_COMPLETED_PENDING_SETTLEMENT,
        GoalQualityExecutionStatus.SETTLED_PASSED,
        GoalQualityExecutionStatus.SETTLED_WARNED,
      )
    GoalQualityExecutionStatus.VALIDATION_COMPLETED_PENDING_SETTLEMENT ->
      setOf(
        GoalQualityExecutionStatus.SETTLED_PASSED,
        GoalQualityExecutionStatus.SETTLED_WARNED,
      )
    GoalQualityExecutionStatus.SETTLED_PASSED,
    GoalQualityExecutionStatus.SETTLED_SKIPPED,
    GoalQualityExecutionStatus.SETTLED_WARNED,
    GoalQualityExecutionStatus.RETIRED,
    -> emptySet()
  }
