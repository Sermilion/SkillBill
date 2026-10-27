package skillbill.goalrunner.model.quality

import skillbill.contracts.workflow.goal.GOAL_QUALITY_EXECUTION_CONTRACT_VERSION
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import skillbill.workflow.taskruntime.model.feature.FeatureTaskRuntimeVerificationBoundaryHeadingProvenance
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseRecord
import skillbill.workflow.taskruntime.model.qa.QaRepairState
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeFindingVerificationDisposition
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationGateProgress
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
  val qa: String,
  val validation: String,
  val fingerprint: String = canonicalFingerprint(qa, validation),
) {
  init {
    require(qa.isNotBlank())
    require(validation.isNotBlank())
    require(fingerprint == canonicalFingerprint(qa, validation))
  }

  companion object {
    private fun canonicalFingerprint(
      qa: String,
      validation: String,
    ): String =
      MessageDigest.getInstance("SHA-256")
        .digest("$qa\u0000$validation".toByteArray(Charsets.UTF_8))
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

data class GoalQualityPhaseProgress(
  val phaseRecords: Map<String, FeatureTaskRuntimePhaseRecord> = emptyMap(),
  val phaseLedger: List<FeatureTaskRuntimePhaseLedgerEntry> = emptyList(),
  val acceptedOutputs: List<GoalQualityAcceptedOutput> = emptyList(),
  val acceptedEnvelopes: List<GoalQualityAcceptedEnvelope> = emptyList(),
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
)

data class GoalQualityExecutionProgress(
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
  val postQaContentFingerprint: String? = null,
  val qaTestedTreeIdentity: String? = null,
  val skipReason: String? = null,
)

data class GoalQualityExecutionRecord(
  val contractVersion: String = GOAL_QUALITY_EXECUTION_CONTRACT_VERSION,
  val readiness: GoalQualityReadiness,
  val execution: GoalQualityExecution? = null,
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

  fun acceptQa(
    postQaContentFingerprint: String,
    qaTestedTreeIdentity: String?,
    warning: String?,
    validationRequired: Boolean,
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
          status =
            if (validationRequired) {
              GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING
            } else {
              GoalQualityExecutionStatus.VALIDATION_DECISION_PENDING
            },
          validation =
            if (validationRequired) {
              GoalQualityValidationDisposition.REQUIRED_PENDING
            } else {
              GoalQualityValidationDisposition.NOT_REQUIRED
            },
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

  fun completeValidation(): GoalQualityExecutionRecord {
    val current = checkExecution(GoalQualityExecutionStatus.VALIDATION_RUNNING)
    check(current.validation == GoalQualityValidationDisposition.RUNNING)
    return copy(
      execution =
        current.copy(
          status = GoalQualityExecutionStatus.VALIDATION_COMPLETED_PENDING_SETTLEMENT,
          validation = GoalQualityValidationDisposition.COMPLETED,
        ),
    )
  }

  fun warnValidation(warning: String): GoalQualityExecutionRecord {
    val current = requireNotNull(execution)
    check(
      current.status in
        setOf(
          GoalQualityExecutionStatus.VALIDATION_DECISION_PENDING,
          GoalQualityExecutionStatus.REQUIRED_VALIDATION_PENDING,
          GoalQualityExecutionStatus.VALIDATION_RUNNING,
        ),
    )
    return copy(
      execution =
        current.copy(
          status = GoalQualityExecutionStatus.VALIDATION_COMPLETED_PENDING_SETTLEMENT,
          validation = GoalQualityValidationDisposition.DELIBERATELY_WARNED,
          warning = listOfNotNull(current.warning, warning).distinct().joinToString(" "),
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
          GoalQualityValidationDisposition.NOT_REQUIRED,
          GoalQualityValidationDisposition.COMPLETED,
          GoalQualityValidationDisposition.DELIBERATELY_WARNED,
        ),
    )
    val validation =
      if (status == GoalQualityExecutionStatus.SETTLED_WARNED &&
        current.validation != GoalQualityValidationDisposition.NOT_REQUIRED
      ) {
        GoalQualityValidationDisposition.DELIBERATELY_WARNED
      } else {
        current.validation
      }
    require(settledContentFingerprint.isNotBlank())
    when (status) {
      GoalQualityExecutionStatus.SETTLED_PASSED -> {
        require(warning == null)
        require(
          validation in
            setOf(
              GoalQualityValidationDisposition.NOT_REQUIRED,
              GoalQualityValidationDisposition.COMPLETED,
            ),
        )
      }
      GoalQualityExecutionStatus.SETTLED_SKIPPED -> {
        require(warning == null)
        require(validation == GoalQualityValidationDisposition.NOT_REQUIRED)
      }
      GoalQualityExecutionStatus.SETTLED_WARNED -> {
        require(!warning.isNullOrBlank())
        require(
          validation in
            setOf(
              GoalQualityValidationDisposition.NOT_REQUIRED,
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
