package skillbill.goalrunner.model.quality

import skillbill.contracts.workflow.goal.GOAL_QUALITY_EXECUTION_CONTRACT_VERSION
import skillbill.goalrunner.model.history.accumulateGoalFinalRepairWarnings
import skillbill.workflow.model.WorkflowStepStatus
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.qa.QaRepairState
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateRepairOutput
import java.security.MessageDigest

enum class GoalQualityReadiness {
  CLEAN_UNSTARTED,
  ADMITTED,
  HISTORICAL_AMBIGUITY,

  ;

  val wireValue: String get() = name.lowercase()

  companion object {
    fun fromWire(value: String): GoalQualityReadiness =
      entries.firstOrNull { it.wireValue == value }
        ?: throw IllegalArgumentException("Unsupported goal quality readiness '$value'.")
  }
}

enum class GoalQualityExecutionStatus {
  QA_PENDING,
  VALIDATION_DECISION_PENDING,
  REQUIRED_VALIDATION_PENDING,
  VALIDATION_RUNNING,
  VALIDATION_COMPLETED_PENDING_SETTLEMENT,
  SETTLED_PASSED,
  SETTLED_SKIPPED,
  SETTLED_WARNED,
  RETIRED,

  ;

  val wireValue: String get() = name.lowercase()

  companion object {
    fun fromWire(value: String): GoalQualityExecutionStatus =
      entries.firstOrNull { it.wireValue == value }
        ?: throw IllegalArgumentException("Unsupported goal quality execution status '$value'.")
  }
}

enum class GoalQualityValidationDisposition {
  DECISION_PENDING,
  NOT_REQUIRED,
  REQUIRED_PENDING,
  RUNNING,
  COMPLETED,
  DELIBERATELY_WARNED,

  ;

  val wireValue: String get() = name.lowercase()

  companion object {
    fun fromWire(value: String): GoalQualityValidationDisposition =
      entries.firstOrNull { it.wireValue == value }
        ?: throw IllegalArgumentException("Unsupported goal quality validation disposition '$value'.")
  }
}

enum class GoalQualityPreQaBuildState(val wireValue: String) {
  PENDING("pending"),
  RUNNING("running"),
  PASSED("passed"),
  WARNED("warned"),
  ;

  companion object {
    fun fromWire(value: String): GoalQualityPreQaBuildState =
      entries.firstOrNull { it.wireValue == value }
        ?: throw IllegalArgumentException("Unsupported pre-QA build state '$value'.")
  }
}

data class GoalQualityExecutionAdmission(
  val parentWorkflowId: String,
  val invocationId: String,
  val baselineContentFingerprint: String,
  val selectionFingerprint: String,
  val governingInputFingerprint: String,
  val governingInputs: GoalQualityExecutionInputs,
  val executionPlans: GoalQualityExecutionPlans,
)

data class GoalQualityExecutionPlans(
  val build: String,
  val qa: String,
  val validation: String,
  val fingerprint: String = canonicalFingerprint(build, qa, validation),
) {
  init {
    require(build.isNotBlank())
    require(qa.isNotBlank())
    require(validation.isNotBlank())
    require(fingerprint == canonicalFingerprint(build, qa, validation))
  }

  companion object {
    private fun canonicalFingerprint(
      build: String,
      qa: String,
      validation: String,
    ): String =
      MessageDigest.getInstance("SHA-256")
        .digest("$build\u0000$qa\u0000$validation".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
  }
}

data class GoalQualityAcceptedOutput(
  val phaseId: String,
  val occurrence: Int,
  val output: String,
)

data class GoalQualityAcceptedEnvelope(
  val phaseId: String,
  val attempt: Int,
  val envelope: FeatureTaskRuntimeWorkflowArtifactMap,
)

data class GoalQualityAcceptedSourceObservation(
  val phaseId: String,
  val attempt: Int,
  val contentFingerprint: String,
  val completionSequenceNumber: Int = 0,
  val progressRevision: Long = 0,
  val validationOccurrence: Int = 0,
  val acceptedOutputFingerprint: String? = null,
)

data class GoalQualityValidationOccurrence(
  val occurrence: Int,
  val status: GoalQualityExecutionStatus,
  val validation: GoalQualityValidationDisposition,
  val finalValidationContentFingerprint: String?,
  val progress: GoalQualityPhaseProgress,
)

data class GoalQualityPreQaBuildOccurrence(
  val state: GoalQualityPreQaBuildState,
  val contentFingerprint: String,
  val progress: GoalQualityPhaseProgress,
)

data class GoalQualityAdoptionEvidence(
  val sourceContractVersion: String,
  val originalPayloadJson: String,
)

data class GoalQualityMigrationEvidence(
  val sourceContractVersion: String,
  val originalPayloadJson: String,
)

data class GoalQualityPhaseProgress(
  val phaseRecords: Map<String, FeatureTaskRuntimePhaseRecord> = emptyMap(),
  val phaseLedger: List<FeatureTaskRuntimePhaseLedgerEntry> = emptyList(),
  val acceptedOutputs: List<GoalQualityAcceptedOutput> = emptyList(),
  val acceptedEnvelopes: List<GoalQualityAcceptedEnvelope> = emptyList(),
  val acceptedSourceObservations: List<GoalQualityAcceptedSourceObservation> = emptyList(),
  val acceptedQaSourceWitnesses: List<GoalQualityQaSourceWitness> = emptyList(),
  val attemptHighWaterMarks: Map<String, Int> = emptyMap(),
  val ledgerSequenceHighWaterMark: Int = 0,
  val carriedValidationRepairsUsed: Int = 0,
  val carriedValidationRepairOutputs: List<FeatureTaskRuntimeValidationGateRepairOutput> = emptyList(),
  val carriedBuildRepairsUsed: Int = 0,
  val carriedBuildRepairOutputs: List<FeatureTaskRuntimeValidationGateRepairOutput> = emptyList(),
  val qaRepairState: QaRepairState? = null,
  val buildGateProgress: FeatureTaskRuntimeValidationGateProgress? = null,
  val buildGateProgressKey: String? = null,
  val validationGateProgress: FeatureTaskRuntimeValidationGateProgress? = null,
  val validationGateProgressKey: String? = null,
  val verificationCheckpoint: List<FeatureTaskRuntimeFindingVerificationDisposition>? = null,
  val verificationBoundary: Map<String, List<FeatureTaskRuntimeVerificationBoundaryHeadingProvenance>>? = null,
  val terminalBlockedStep: String? = null,
  val terminalBlockedReason: String? = null,
  val terminalCompleted: Boolean = false,
  val revision: Long = 0,
) {
  fun acceptedBlockedValidationEvent(
    phaseId: String,
    attempt: Int,
  ): FeatureTaskRuntimePhaseLedgerEntry? {
    val latestPhaseEvent =
      phaseLedger.filter { it.phaseId == phaseId }.maxByOrNull { it.sequenceNumber }
        ?: return null
    return latestPhaseEvent.takeIf {
      it.attemptCount == attempt && it.action == FeatureTaskRuntimePhaseLedgerAction.BLOCKED
    }
  }

  fun hasAcceptedValidationBoundary(): Boolean =
    phaseRecords.isNotEmpty() &&
      (
        phaseRecords.values.all { it.status == WorkflowStepStatus.COMPLETED } ||
          phaseRecords.values.count { it.status == WorkflowStepStatus.BLOCKED } == 1 &&
          phaseRecords.values.all { it.status in setOf(WorkflowStepStatus.COMPLETED, WorkflowStepStatus.BLOCKED) } &&
          phaseRecords.values.filter { it.status == WorkflowStepStatus.BLOCKED }.all { blocked ->
            acceptedBlockedValidationEvent(blocked.phaseId, blocked.attemptCount) != null
          }
      ) &&
      phaseRecords.values.filter { it.status == WorkflowStepStatus.COMPLETED }.all { completed ->
        phaseLedger.filter { it.phaseId == completed.phaseId }.maxByOrNull { it.sequenceNumber }?.let {
          it.attemptCount == completed.attemptCount && it.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE
        } == true
      }
}

data class GoalQualityExecutionProgress(
  val build: GoalQualityPhaseProgress = GoalQualityPhaseProgress(),
  val qa: GoalQualityPhaseProgress = GoalQualityPhaseProgress(),
  val validation: GoalQualityPhaseProgress = GoalQualityPhaseProgress(),
)

data class GoalQualityExecution(
  val parentWorkflowId: String,
  val invocationId: String,
  val baselineContentFingerprint: String,
  val selectionFingerprint: String?,
  val governingInputFingerprint: String?,
  val governingInputs: GoalQualityExecutionInputs?,
  val executionPlans: GoalQualityExecutionPlans?,
  val progress: GoalQualityExecutionProgress?,
  val status: GoalQualityExecutionStatus,
  val validation: GoalQualityValidationDisposition,
  val warning: String? = null,
  val settledContentFingerprint: String? = null,
  val finalValidationContentFingerprint: String? = null,
  val postQaContentFingerprint: String? = null,
  val qaTestedTreeIdentity: String? = null,
  val skipReason: String? = null,
  val validationOccurrences: List<GoalQualityValidationOccurrence> = emptyList(),
  val validationOccurrence: Int = 0,
  val preQaBuild: GoalQualityPreQaBuildState = GoalQualityPreQaBuildState.PENDING,
  val preQaBuildContentFingerprint: String? = null,
  val preQaBuildOccurrences: List<GoalQualityPreQaBuildOccurrence> = emptyList(),
)

data class GoalQualityExecutionRecord(
  val contractVersion: String = GOAL_QUALITY_EXECUTION_CONTRACT_VERSION,
  val readiness: GoalQualityReadiness,
  val execution: GoalQualityExecution? = null,
  val adoptionEvidence: GoalQualityAdoptionEvidence? = null,
  val migrationEvidence: GoalQualityMigrationEvidence? = null,
) {
  companion object

  fun admit(admission: GoalQualityExecutionAdmission): GoalQualityExecutionRecord {
    check(readiness == GoalQualityReadiness.CLEAN_UNSTARTED || readiness == GoalQualityReadiness.ADMITTED)
    val prior = execution
    if (prior != null && prior.status != GoalQualityExecutionStatus.RETIRED) {
      check(prior.parentWorkflowId == admission.parentWorkflowId)
      check(prior.selectionFingerprint == admission.selectionFingerprint)
      check(prior.governingInputFingerprint == admission.governingInputFingerprint)
      check(prior.governingInputs == admission.governingInputs)
      check(prior.executionPlans == admission.executionPlans)
      return this
    }
    return copy(
      readiness = GoalQualityReadiness.ADMITTED,
      execution =
        GoalQualityExecution(
          admission.parentWorkflowId,
          admission.invocationId,
          admission.baselineContentFingerprint,
          admission.selectionFingerprint,
          admission.governingInputFingerprint,
          admission.governingInputs,
          admission.executionPlans,
          GoalQualityExecutionProgress(),
          GoalQualityExecutionStatus.QA_PENDING,
          GoalQualityValidationDisposition.DECISION_PENDING,
        ),
    )
  }

  fun retire(): GoalQualityExecutionRecord {
    val current = execution ?: return this
    if (current.status == GoalQualityExecutionStatus.RETIRED) return this
    return copy(execution = current.copy(status = GoalQualityExecutionStatus.RETIRED))
  }

  fun startPreQaBuild(): GoalQualityExecutionRecord {
    val current = requireNotNull(execution)
    check(current.status != GoalQualityExecutionStatus.RETIRED)
    check(current.preQaBuild == GoalQualityPreQaBuildState.PENDING)
    return copy(execution = current.copy(preQaBuild = GoalQualityPreQaBuildState.RUNNING))
  }

  fun settlePreQaBuild(
    passed: Boolean,
    contentFingerprint: String,
    warning: String? = null,
  ): GoalQualityExecutionRecord {
    val current = requireNotNull(execution)
    check(current.status != GoalQualityExecutionStatus.RETIRED)
    check(current.preQaBuild == GoalQualityPreQaBuildState.RUNNING)
    require(contentFingerprint.isNotBlank())
    require(warning?.isBlank() != true)
    require(passed == (warning == null))
    return copy(
      execution =
        current.copy(
          preQaBuild = if (passed) GoalQualityPreQaBuildState.PASSED else GoalQualityPreQaBuildState.WARNED,
          preQaBuildContentFingerprint = contentFingerprint,
          warning = accumulateGoalFinalRepairWarnings(current.warning, warning),
        ),
    )
  }

  fun startNewPreQaBuildOccurrence(): GoalQualityExecutionRecord {
    val current = requireNotNull(execution)
    check(current.status == GoalQualityExecutionStatus.QA_PENDING)
    check(current.preQaBuild in setOf(GoalQualityPreQaBuildState.PASSED, GoalQualityPreQaBuildState.WARNED))
    val priorProgress = requireNotNull(current.progress).build
    val fingerprint = requireNotNull(current.preQaBuildContentFingerprint)
    val highWaterMarks = priorProgress.attemptHighWaterMarks.toMutableMap()
    priorProgress.phaseRecords.forEach { (phase, record) ->
      highWaterMarks[phase] = maxOf(highWaterMarks[phase] ?: 0, record.attemptCount)
    }
    priorProgress.phaseLedger.groupBy { it.phaseId }.forEach { (phase, entries) ->
      highWaterMarks[phase] = maxOf(highWaterMarks[phase] ?: 0, entries.maxOf { it.attemptCount })
    }
    priorProgress.acceptedOutputs.groupBy { it.phaseId }.forEach { (phase, outputs) ->
      highWaterMarks[phase] = maxOf(highWaterMarks[phase] ?: 0, outputs.maxOf { it.occurrence })
    }
    priorProgress.acceptedEnvelopes.groupBy { it.phaseId }.forEach { (phase, envelopes) ->
      highWaterMarks[phase] = maxOf(highWaterMarks[phase] ?: 0, envelopes.maxOf { it.attempt })
    }
    priorProgress.acceptedSourceObservations.groupBy { it.phaseId }.forEach { (phase, observations) ->
      highWaterMarks[phase] = maxOf(highWaterMarks[phase] ?: 0, observations.maxOf { it.attempt })
    }
    val archived = GoalQualityPreQaBuildOccurrence(current.preQaBuild, fingerprint, priorProgress)
    val nextProgress =
      GoalQualityPhaseProgress(
        revision = priorProgress.revision + 1,
        attemptHighWaterMarks = highWaterMarks,
        ledgerSequenceHighWaterMark =
          maxOf(
            priorProgress.ledgerSequenceHighWaterMark,
            priorProgress.phaseLedger.maxOfOrNull { it.sequenceNumber } ?: 0,
          ),
        carriedBuildRepairsUsed = priorProgress.buildGateProgress?.repairsUsed ?: priorProgress.carriedBuildRepairsUsed,
        carriedBuildRepairOutputs =
          priorProgress.buildGateProgress?.acceptedRepairOutputs ?: priorProgress.carriedBuildRepairOutputs,
      )
    return copy(
      execution =
        current.copy(
          progress = requireNotNull(current.progress).copy(build = nextProgress),
          preQaBuild = GoalQualityPreQaBuildState.PENDING,
          preQaBuildContentFingerprint = null,
          preQaBuildOccurrences = current.preQaBuildOccurrences + archived,
        ),
    )
  }

  fun startNewValidationOccurrence(): GoalQualityExecutionRecord {
    val current = requireNotNull(execution)
    check(
      current.status in
        setOf(
          GoalQualityExecutionStatus.VALIDATION_RUNNING,
          GoalQualityExecutionStatus.VALIDATION_COMPLETED_PENDING_SETTLEMENT,
          GoalQualityExecutionStatus.SETTLED_PASSED,
          GoalQualityExecutionStatus.SETTLED_SKIPPED,
          GoalQualityExecutionStatus.SETTLED_WARNED,
        ),
    )
    val oldProgress = requireNotNull(current.progress).validation
    check(
      current.status != GoalQualityExecutionStatus.VALIDATION_RUNNING ||
        oldProgress.hasAcceptedValidationBoundary(),
    )
    val attemptHighWaterMarks = validationAttemptHighWaterMarks(oldProgress)
    val archived =
      GoalQualityValidationOccurrence(
        occurrence = current.validationOccurrence,
        status = current.status,
        validation = current.validation,
        finalValidationContentFingerprint = current.finalValidationContentFingerprint,
        progress = oldProgress,
      )
    val freshProgress =
      GoalQualityPhaseProgress(
        revision = oldProgress.revision + 1,
        attemptHighWaterMarks = attemptHighWaterMarks,
        ledgerSequenceHighWaterMark =
          maxOf(
            oldProgress.ledgerSequenceHighWaterMark,
            oldProgress.phaseLedger.maxOfOrNull { it.sequenceNumber } ?: 0,
          ),
        carriedValidationRepairsUsed =
          oldProgress.validationGateProgress?.repairsUsed
            ?: oldProgress.carriedValidationRepairsUsed,
        carriedValidationRepairOutputs =
          oldProgress.validationGateProgress?.acceptedRepairOutputs
            ?: oldProgress.carriedValidationRepairOutputs,
      )
    return copy(
      execution =
        current.copy(
          status = GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING,
          validation = GoalQualityValidationDisposition.REQUIRED_PENDING,
          settledContentFingerprint = null,
          finalValidationContentFingerprint = null,
          progress = requireNotNull(current.progress).copy(validation = freshProgress),
          validationOccurrences = current.validationOccurrences + archived,
          validationOccurrence = current.validationOccurrence + 1,
        ),
    )
  }

  private fun validationAttemptHighWaterMarks(oldProgress: GoalQualityPhaseProgress): Map<String, Int> {
    val attemptHighWaterMarks = oldProgress.attemptHighWaterMarks.toMutableMap()
    oldProgress.phaseRecords.forEach { (phase, record) ->
      attemptHighWaterMarks[phase] = maxOf(attemptHighWaterMarks[phase] ?: 0, record.attemptCount)
    }
    oldProgress.phaseLedger.groupBy { it.phaseId }.forEach { (phase, entries) ->
      attemptHighWaterMarks[phase] = maxOf(attemptHighWaterMarks[phase] ?: 0, entries.maxOf { it.attemptCount })
    }
    oldProgress.acceptedOutputs.groupBy { it.phaseId }.forEach { (phase, outputs) ->
      attemptHighWaterMarks[phase] = maxOf(attemptHighWaterMarks[phase] ?: 0, outputs.maxOf { it.occurrence })
    }
    oldProgress.acceptedEnvelopes.groupBy { it.phaseId }.forEach { (phase, envelopes) ->
      attemptHighWaterMarks[phase] = maxOf(attemptHighWaterMarks[phase] ?: 0, envelopes.maxOf { it.attempt })
    }
    oldProgress.acceptedSourceObservations.groupBy { it.phaseId }.forEach { (phase, observations) ->
      attemptHighWaterMarks[phase] = maxOf(attemptHighWaterMarks[phase] ?: 0, observations.maxOf { it.attempt })
    }
    return attemptHighWaterMarks
  }

  fun acceptQa(
    postQaContentFingerprint: String,
    qaTestedTreeIdentity: String?,
    warning: String?,
    skipReason: String? = null,
  ): GoalQualityExecutionRecord {
    val current = checkExecution(GoalQualityExecutionStatus.QA_PENDING)
    require(postQaContentFingerprint.isNotBlank())
    require(qaTestedTreeIdentity?.isBlank() != true)
    require(warning?.isBlank() != true)
    require(qaTestedTreeIdentity != null || warning != null || skipReason != null)
    return copy(
      execution =
        current.copy(
          status = GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING,
          validation = GoalQualityValidationDisposition.REQUIRED_PENDING,
          warning = warning,
          postQaContentFingerprint = postQaContentFingerprint,
          qaTestedTreeIdentity = qaTestedTreeIdentity,
          skipReason = skipReason,
        ),
    )
  }

  fun startValidation(): GoalQualityExecutionRecord {
    val current = checkExecution(GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING)
    check(current.validation == GoalQualityValidationDisposition.REQUIRED_PENDING)
    return copy(
      execution =
        current.copy(
          status = GoalQualityExecutionStatus.VALIDATION_RUNNING,
          validation = GoalQualityValidationDisposition.RUNNING,
        ),
    )
  }

  fun requireFinalValidation(): GoalQualityExecutionRecord {
    val current = checkExecution(GoalQualityExecutionStatus.VALIDATION_DECISION_PENDING)
    check(
      current.validation in
        setOf(
          GoalQualityValidationDisposition.DECISION_PENDING,
          GoalQualityValidationDisposition.NOT_REQUIRED,
        ),
    )
    return copy(
      execution =
        current.copy(
          status = GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING,
          validation = GoalQualityValidationDisposition.REQUIRED_PENDING,
          finalValidationContentFingerprint = null,
        ),
    )
  }

  fun completeValidation(): GoalQualityExecutionRecord {
    val current = checkExecution(GoalQualityExecutionStatus.VALIDATION_RUNNING)
    check(current.validation == GoalQualityValidationDisposition.RUNNING)
    require(!current.finalValidationContentFingerprint.isNullOrBlank())
    return copy(
      execution =
        current.copy(
          status = GoalQualityExecutionStatus.VALIDATION_COMPLETED_PENDING_SETTLEMENT,
          validation = GoalQualityValidationDisposition.COMPLETED,
        ),
    )
  }

  fun warnValidation(
    warning: String,
    observedContentFingerprint: String? = null,
  ): GoalQualityExecutionRecord {
    val current = requireNotNull(execution)
    check(
      current.status in
        setOf(
          GoalQualityExecutionStatus.VALIDATION_DECISION_PENDING,
          GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING,
          GoalQualityExecutionStatus.VALIDATION_RUNNING,
        ),
    )
    val fingerprint = observedContentFingerprint ?: current.finalValidationContentFingerprint
    require(!fingerprint.isNullOrBlank())
    return copy(
      execution =
        current.copy(
          status = GoalQualityExecutionStatus.VALIDATION_COMPLETED_PENDING_SETTLEMENT,
          validation = GoalQualityValidationDisposition.DELIBERATELY_WARNED,
          warning = accumulateGoalFinalRepairWarnings(current.warning, warning),
          finalValidationContentFingerprint = fingerprint,
        ),
    )
  }

  fun settle(
    status: GoalQualityExecutionStatus,
    warning: String?,
    settledContentFingerprint: String,
  ): GoalQualityExecutionRecord {
    require(
      status in
        setOf(
          GoalQualityExecutionStatus.SETTLED_PASSED,
          GoalQualityExecutionStatus.SETTLED_SKIPPED,
          GoalQualityExecutionStatus.SETTLED_WARNED,
        ),
    )
    val current = requireNotNull(execution)
    check(
      current.validation in
        setOf(
          GoalQualityValidationDisposition.COMPLETED,
          GoalQualityValidationDisposition.DELIBERATELY_WARNED,
        ),
    )
    require(!current.finalValidationContentFingerprint.isNullOrBlank())
    val validation = current.validation
    require(settledContentFingerprint.isNotBlank())
    when (status) {
      GoalQualityExecutionStatus.SETTLED_PASSED -> {
        require(warning == null)
        require(
          validation in
            setOf(
              GoalQualityValidationDisposition.COMPLETED,
            ),
        )
      }
      GoalQualityExecutionStatus.SETTLED_SKIPPED -> {
        require(warning == null)
        require(validation == GoalQualityValidationDisposition.COMPLETED)
      }
      GoalQualityExecutionStatus.SETTLED_WARNED -> {
        require(!warning.isNullOrBlank())
        require(
          validation in
            setOf(
              GoalQualityValidationDisposition.COMPLETED,
              GoalQualityValidationDisposition.DELIBERATELY_WARNED,
            ),
        )
      }
      else -> Unit
    }
    return copy(
      execution =
        current.copy(
          status = status,
          validation = validation,
          warning = warning,
          settledContentFingerprint = settledContentFingerprint,
        ),
    )
  }

  private fun checkExecution(expected: GoalQualityExecutionStatus): GoalQualityExecution {
    val current = requireNotNull(execution)
    check(current.status == expected)
    return current
  }
}
