package skillbill.infrastructure.sqlite.idestatus

import skillbill.contracts.JsonCodec
import skillbill.contracts.persistence.WorkBoardReadModelPayloadKeys
import skillbill.contracts.workflow.identity.status.GOAL_PLANNING_WAVE_CAP
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.idestatus.model.AgentActivityLabel
import skillbill.idestatus.model.IdeStatusDisplayGroup
import skillbill.idestatus.model.IdeStatusExecutionScope
import skillbill.idestatus.model.IdeStatusFreshness
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.idestatus.model.IdeStatusWorkflowFamily
import skillbill.idestatus.model.boundedIdeStatusActivity
import skillbill.ports.idestatus.model.IDE_STATUS_PAUSE_REASON_LABEL_MAX_LENGTH
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.ports.idestatus.model.IdeStatusExecutionIdentity
import skillbill.ports.idestatus.model.IdeStatusPauseReasonCode
import skillbill.ports.idestatus.model.IdeStatusProblemCode
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.idestatus.model.WorkBoardCompletionSource
import skillbill.ports.idestatus.model.WorkLiveSection
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.format.DateTimeParseException

internal object WorkBoardReadModelPayloadValidation {
  fun validItem(fields: Map<String, Any?>): Boolean {
    val repositoryRoot = requiredTextValue(fields, WorkBoardReadModelPayloadKeys.REPOSITORY_ROOT) ?: return false
    if (!validPath(repositoryRoot) || Checks.itemChecks.any { it(fields) }) return false

    val snapshot = requiredObject(fields, WorkBoardReadModelPayloadKeys.SNAPSHOT) ?: return false
    return validSnapshot(snapshot)
  }

  private fun validSnapshot(fields: Map<String, Any?>): Boolean {
    if (Checks.snapshotScalarChecks.any { it(fields) }) return false

    val currentActivity = fields[WorkBoardReadModelPayloadKeys.CURRENT_ACTIVITY] as? String
    if (currentActivity != null && boundedIdeStatusActivity(currentActivity) != currentActivity) return false
    return validSnapshotStep(fields) && Checks.snapshotObjectChecks.none { it(fields) }
  }

  private fun validSnapshotStep(fields: Map<String, Any?>): Boolean =
    requiredObject(fields, WorkBoardReadModelPayloadKeys.CURRENT_STEP).let { step ->
      step != null &&
        requiredNonBlankText(
          step,
          WorkBoardReadModelPayloadKeys.STEP_ID,
        ) &&
        requiredNonBlankText(
          step,
          WorkBoardReadModelPayloadKeys.STEP_LABEL,
        )
    }

  private fun validLease(fields: Map<String, Any?>): Boolean =
    exactLong(fields[WorkBoardReadModelPayloadKeys.LEASE_GENERATION])?.let { it > 0 } == true &&
      requiredNonBlankText(fields, WorkBoardReadModelPayloadKeys.LEASE_OWNER_TOKEN) &&
      requiredNonBlankText(fields, WorkBoardReadModelPayloadKeys.LEASE_HOST_IDENTITY) &&
      requiredNonBlankText(fields, WorkBoardReadModelPayloadKeys.LEASE_BOOT_IDENTITY) &&
      exactLong(fields[WorkBoardReadModelPayloadKeys.LEASE_PID])?.let { it > 0 } == true &&
      requiredNonBlankText(fields, WorkBoardReadModelPayloadKeys.LEASE_PROCESS_BIRTH_TOKEN) &&
      requiredInstant(fields, WorkBoardReadModelPayloadKeys.LEASE_HEARTBEAT_AT) &&
      requiredInstant(fields, WorkBoardReadModelPayloadKeys.LEASE_EXPIRES_AT)

  private fun validExecution(fields: Map<String, Any?>): Boolean {
    val scope =
      requiredEnumValue(
        fields,
        WorkBoardReadModelPayloadKeys.EXECUTION_SCOPE,
        IdeStatusExecutionScope.entries.map { it.wireValue },
      )
    val launch =
      requiredEnumValue(
        fields,
        WorkBoardReadModelPayloadKeys.LAUNCH_SCOPE,
        IdeStatusLaunchScope.entries.map { it.wireValue },
      )
    if (scope == null || launch == null || !validExecutionFields(fields)) return false
    val invocation = fields[WorkBoardReadModelPayloadKeys.INVOCATION_ID] as? String
    val phase = fields[WorkBoardReadModelPayloadKeys.PHASE_ID] as? String
    val qa = fields[WorkBoardReadModelPayloadKeys.QA_OUTCOME] as? String
    return IdeStatusExecutionIdentity.isPositiveDecimal(fields[WorkBoardReadModelPayloadKeys.RUN_SEQUENCE] as String) &&
      IdeStatusExecutionIdentity.isPositiveDecimal(fields[WorkBoardReadModelPayloadKeys.STATUS_REVISION] as String) &&
      when (scope) {
        IdeStatusExecutionScope.WORKFLOW.wireValue ->
          invocation == null && phase == null &&
            launch == IdeStatusLaunchScope.STANDALONE.wireValue && qa == null
        IdeStatusExecutionScope.STANDALONE_PHASE.wireValue -> !invocation.isNullOrBlank() && !phase.isNullOrBlank()
        else -> false
      }
  }

  private fun validExecutionFields(fields: Map<String, Any?>): Boolean =
    validExecutionIdentity(fields) && validExecutionReferences(fields)

  private fun validExecutionIdentity(fields: Map<String, Any?>): Boolean =
    listOf(
      requiredNonBlankText(fields, WorkBoardReadModelPayloadKeys.EXECUTION_ID),
      requiredNonBlankText(fields, WorkBoardReadModelPayloadKeys.STATUS_STORE_ID),
      requiredText(fields, WorkBoardReadModelPayloadKeys.RUN_SEQUENCE),
      requiredText(fields, WorkBoardReadModelPayloadKeys.STATUS_REVISION),
    ).all { it }

  private fun validExecutionReferences(fields: Map<String, Any?>): Boolean =
    optionalText(fields, WorkBoardReadModelPayloadKeys.INVOCATION_ID) &&
      optionalText(fields, WorkBoardReadModelPayloadKeys.PHASE_ID) &&
      optionalEnum(fields, WorkBoardReadModelPayloadKeys.QA_OUTCOME, StandaloneQaOutcome.entries.map { it.wireValue })

  private fun optionalObject(
    fields: Map<String, Any?>,
    key: String,
    validate: (Map<String, Any?>) -> Boolean,
  ): Boolean {
    if (!fields.containsKey(key) || fields[key] == null) return true
    val value = JsonCodec.anyToStringAnyMap(fields[key]) ?: return false
    return validate(value)
  }

  private fun requiredObject(
    fields: Map<String, Any?>,
    key: String,
  ): Map<String, Any?>? = JsonCodec.anyToStringAnyMap(fields[key])

  private fun requiredText(
    fields: Map<String, Any?>,
    key: String,
  ): Boolean = fields[key] is String

  private fun requiredTextValue(
    fields: Map<String, Any?>,
    key: String,
  ): String? = fields[key] as? String

  private fun requiredNonBlankText(
    fields: Map<String, Any?>,
    key: String,
  ): Boolean = (fields[key] as? String)?.isNotBlank() == true

  private fun optionalText(
    fields: Map<String, Any?>,
    key: String,
  ): Boolean = !fields.containsKey(key) || fields[key] == null || fields[key] is String

  private fun optionalNonBlankText(
    fields: Map<String, Any?>,
    key: String,
  ): Boolean = !fields.containsKey(key) || fields[key] == null || (fields[key] as? String)?.isNotBlank() == true

  private fun requiredBoolean(
    fields: Map<String, Any?>,
    key: String,
  ): Boolean = fields[key] is Boolean

  private fun optionalBoolean(
    fields: Map<String, Any?>,
    key: String,
  ): Boolean = !fields.containsKey(key) || fields[key] == null || fields[key] is Boolean

  private fun requiredEnum(
    fields: Map<String, Any?>,
    key: String,
    allowed: List<String>,
  ): Boolean = requiredEnumValue(fields, key, allowed) != null

  private fun optionalEnum(
    fields: Map<String, Any?>,
    key: String,
    allowed: List<String>,
  ): Boolean = !fields.containsKey(key) || fields[key] == null || requiredEnumValue(fields, key, allowed) != null

  private fun requiredEnumValue(
    fields: Map<String, Any?>,
    key: String,
    allowed: List<String>,
  ): String? = (fields[key] as? String)?.takeIf(allowed::contains)

  private fun requiredInt(
    fields: Map<String, Any?>,
    key: String,
  ): Boolean = exactInt(fields[key]) != null

  private fun optionalInt(
    fields: Map<String, Any?>,
    key: String,
  ): Boolean = !fields.containsKey(key) || fields[key] == null || exactInt(fields[key]) != null

  private fun nonNegativeInt(value: Any?): Boolean = exactInt(value)?.let { it >= 0 } == true

  fun exactInt(value: Any?): Int? =
    when (value) {
      is Int -> value
      is Long -> value.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
      is BigInteger ->
        try {
          value.intValueExact()
        } catch (_: ArithmeticException) {
          null
        }
      is BigDecimal ->
        try {
          value.intValueExact()
        } catch (_: ArithmeticException) {
          null
        }
      else -> null
    }

  fun exactLong(value: Any?): Long? =
    when (value) {
      is Int -> value.toLong()
      is Long -> value
      is BigInteger ->
        try {
          value.longValueExact()
        } catch (_: ArithmeticException) {
          null
        }
      is BigDecimal ->
        try {
          value.longValueExact()
        } catch (_: ArithmeticException) {
          null
        }
      else -> null
    }

  private fun optionalLong(
    fields: Map<String, Any?>,
    key: String,
  ): Boolean = !fields.containsKey(key) || fields[key] == null || exactLong(fields[key]) != null

  fun nullableStringList(
    fields: Map<String, Any?>,
    key: String,
  ): List<String>? = (fields[key] as? List<*>)?.map { it as? String ?: return null }

  private fun optionalInstant(
    fields: Map<String, Any?>,
    key: String,
  ): Boolean = !fields.containsKey(key) || fields[key] == null || validInstant(fields[key])

  private fun requiredInstant(
    fields: Map<String, Any?>,
    key: String,
  ): Boolean = validInstant(fields[key])

  private fun validInstant(value: Any?): Boolean {
    val text = value as? String ?: return false
    return try {
      WorkBoardReadModelTime.parse(text)
      true
    } catch (_: DateTimeParseException) {
      false
    }
  }

  private fun validPath(value: String): Boolean =
    try {
      Path.of(value)
      true
    } catch (_: InvalidPathException) {
      false
    }

  private object Checks {
    val itemChecks =
      listOf(
        ::invalidFieldGroup1,
        ::invalidFieldGroup2,
        ::invalidFieldGroup3,
        ::invalidFieldGroup4,
        ::invalidFieldGroup5,
        ::invalidFieldGroup6,
      )
    val snapshotScalarChecks =
      listOf(
        ::invalidFieldGroup7,
        ::invalidFieldGroup8,
        ::invalidFieldGroup9,
        ::invalidFieldGroup10,
        ::invalidFieldGroup11,
        ::invalidFieldGroup12,
        ::invalidFieldGroup13,
      )
    val snapshotObjectChecks = listOf(::invalidFieldGroup14, ::invalidFieldGroup15, ::invalidFieldGroup16)

    fun invalidFieldGroup1(fields: Map<String, Any?>): Boolean =
      !optionalInstant(fields, WorkBoardReadModelPayloadKeys.FINISHED_AT) ||
        !optionalEnum(
          fields,
          WorkBoardReadModelPayloadKeys.FINISHED_AT_SOURCE,
          WorkBoardCompletionSource.entries.map { it.wireValue },
        ) ||
        !optionalInstant(fields, WorkBoardReadModelPayloadKeys.FRESHNESS_DEADLINE)

    fun invalidFieldGroup2(fields: Map<String, Any?>): Boolean =
      !optionalEnum(fields, WorkBoardReadModelPayloadKeys.LIVE_SECTION, WorkLiveSection.entries.map { it.name }) ||
        !requiredBoolean(fields, WorkBoardReadModelPayloadKeys.CONTROL_ELIGIBLE) ||
        !requiredBoolean(fields, WorkBoardReadModelPayloadKeys.REQUIRES_ATTENTION)

    fun invalidFieldGroup3(fields: Map<String, Any?>): Boolean =
      !optionalEnum(
        fields,
        WorkBoardReadModelPayloadKeys.DISPLAY_GROUP,
        IdeStatusDisplayGroup.entries.map { it.name },
      ) ||
        !requiredInt(fields, WorkBoardReadModelPayloadKeys.DISPLAY_PRIORITY) ||
        !optionalText(fields, WorkBoardReadModelPayloadKeys.EXACT_PAUSE_REASON)

    fun invalidFieldGroup4(fields: Map<String, Any?>): Boolean =
      !requiredBoolean(fields, WorkBoardReadModelPayloadKeys.PAUSE_ELIGIBLE) ||
        !requiredBoolean(fields, WorkBoardReadModelPayloadKeys.RUNNING) ||
        !requiredBoolean(fields, WorkBoardReadModelPayloadKeys.RECENTLY_FINISHED_PHASE)

    fun invalidFieldGroup5(fields: Map<String, Any?>): Boolean =
      !requiredBoolean(fields, WorkBoardReadModelPayloadKeys.DONE_WITHIN_DAY) ||
        !optionalInstant(fields, WorkBoardReadModelPayloadKeys.MEMBERSHIP_DEADLINE)

    fun invalidFieldGroup6(fields: Map<String, Any?>): Boolean =
      !optionalObject(fields, WorkBoardReadModelPayloadKeys.CONTROL_LEASE, ::validLease) ||
        !optionalObject(fields, WorkBoardReadModelPayloadKeys.OBSERVATION) { value ->
          requiredText(
            value,
            WorkBoardReadModelPayloadKeys.OBSERVATION_STORE_ID,
          ) &&
            exactLong(value[WorkBoardReadModelPayloadKeys.OBSERVATION_REVISION]) != null
        } ||
        !optionalObject(fields, WorkBoardReadModelPayloadKeys.DATABASE_FILE_OBSERVATION) { value ->
          val path = requiredTextValue(value, WorkBoardReadModelPayloadKeys.DB_PATH) ?: return@optionalObject false
          requiredText(value, WorkBoardReadModelPayloadKeys.DB_FILE_KEY) && validPath(path)
        }

    fun invalidFieldGroup7(fields: Map<String, Any?>): Boolean =
      !requiredNonBlankText(fields, WorkBoardReadModelPayloadKeys.REPOSITORY_IDENTITY) ||
        !requiredEnum(
          fields,
          WorkBoardReadModelPayloadKeys.LIFECYCLE_STATE,
          IdeStatusLifecycleState.entries.map { it.wireValue },
        ) ||
        !requiredInstant(fields, WorkBoardReadModelPayloadKeys.UPDATED_AT)

    fun invalidFieldGroup8(fields: Map<String, Any?>): Boolean =
      !requiredEnum(fields, WorkBoardReadModelPayloadKeys.FRESHNESS, IdeStatusFreshness.entries.map { it.wireValue }) ||
        !requiredNonBlankText(fields, WorkBoardReadModelPayloadKeys.SUMMARY) ||
        !optionalText(fields, WorkBoardReadModelPayloadKeys.ISSUE_KEY)

    fun invalidFieldGroup9(fields: Map<String, Any?>): Boolean =
      !optionalText(fields, WorkBoardReadModelPayloadKeys.WORKFLOW_ID) ||
        !optionalEnum(
          fields,
          WorkBoardReadModelPayloadKeys.WORKFLOW_FAMILY,
          IdeStatusWorkflowFamily.entries.map { it.wireValue },
        ) ||
        !optionalInstant(fields, WorkBoardReadModelPayloadKeys.STARTED_AT)

    fun invalidFieldGroup10(fields: Map<String, Any?>): Boolean =
      !optionalBoolean(fields, WorkBoardReadModelPayloadKeys.PAUSE_REQUESTED) ||
        !optionalInstant(fields, WorkBoardReadModelPayloadKeys.PAUSED_AT) ||
        !optionalLong(fields, WorkBoardReadModelPayloadKeys.ACTIVE_DURATION_MS)

    fun invalidFieldGroup11(fields: Map<String, Any?>): Boolean =
      !optionalInstant(fields, WorkBoardReadModelPayloadKeys.ACTIVE_DURATION_AS_OF) ||
        !optionalInstant(fields, WorkBoardReadModelPayloadKeys.LAST_AGENT_ACTIVITY_AT) ||
        !optionalEnum(
          fields,
          WorkBoardReadModelPayloadKeys.LAST_AGENT_ACTIVITY_LABEL,
          AgentActivityLabel.entries.map { it.wireValue },
        )

    fun invalidFieldGroup12(fields: Map<String, Any?>): Boolean =
      !optionalInstant(fields, WorkBoardReadModelPayloadKeys.STALLED_SINCE) ||
        !optionalText(fields, WorkBoardReadModelPayloadKeys.CURRENT_ACTIVITY) ||
        !optionalText(fields, WorkBoardReadModelPayloadKeys.BRANCH_CORRELATION)

    fun invalidFieldGroup13(fields: Map<String, Any?>): Boolean =
      !requiredText(fields, WorkBoardReadModelPayloadKeys.CONTRACT_VERSION)

    fun invalidFieldGroup14(fields: Map<String, Any?>): Boolean =
      !optionalObject(fields, WorkBoardReadModelPayloadKeys.PROGRESS) { value ->
        nonNegativeInt(value[WorkBoardReadModelPayloadKeys.PROGRESS_COMPLETED]) &&
          nonNegativeInt(value[WorkBoardReadModelPayloadKeys.PROGRESS_TOTAL])
      } ||
        !optionalObject(fields, WorkBoardReadModelPayloadKeys.CURRENT_SUBTASK) { value ->
          requiredText(
            value,
            WorkBoardReadModelPayloadKeys.STEP_ID,
          ) &&
            optionalInstant(
              value,
              WorkBoardReadModelPayloadKeys.STARTED_AT,
            ) &&
            optionalLong(
              value,
              WorkBoardReadModelPayloadKeys.ACTIVE_DURATION_MS,
            ) &&
            optionalInstant(
              value,
              WorkBoardReadModelPayloadKeys.ACTIVE_DURATION_AS_OF,
            )
        } ||
        !optionalObject(fields, WorkBoardReadModelPayloadKeys.CURRENT_MODEL) { value ->
          requiredNonBlankText(
            value,
            WorkBoardReadModelPayloadKeys.MODEL_NAME,
          ) &&
            optionalNonBlankText(
              value,
              WorkBoardReadModelPayloadKeys.MODEL_EFFORT,
            ) &&
            optionalNonBlankText(
              value,
              WorkBoardReadModelPayloadKeys.PHASE_ID,
            )
        }

    fun invalidFieldGroup15(fields: Map<String, Any?>): Boolean =
      !optionalObject(fields, WorkBoardReadModelPayloadKeys.PLANNING, ::validPlanning) ||
        !optionalObject(fields, WorkBoardReadModelPayloadKeys.CURRENT_PHASE_EXECUTION, ::validPhaseExecution) ||
        !optionalObject(fields, WorkBoardReadModelPayloadKeys.PAUSE_REASON, ::validPauseReason)

    private fun validPlanning(value: Map<String, Any?>): Boolean =
      requiredEnum(
        value,
        WorkBoardReadModelPayloadKeys.PLANNING_STATE,
        GoalPlanningStatusState.entries.map { it.wireValue },
      ) &&
        requiredBoolean(value, WorkBoardReadModelPayloadKeys.PLANNING_SHARED_PREPLAN_PREPARED) &&
        nonNegativeInt(value[WorkBoardReadModelPayloadKeys.PLANNING_SUBTASK_COUNT]) &&
        nonNegativeInt(value[WorkBoardReadModelPayloadKeys.PLANNING_TOTAL_SUBTASK_COUNT]) &&
        optionalText(value, WorkBoardReadModelPayloadKeys.PLANNING_CURRENT_SUBTASK_ID) &&
        nullableStringList(value, WorkBoardReadModelPayloadKeys.PLANNING_WAVE_SUBTASK_IDS)?.let { list ->
          list.size <= GOAL_PLANNING_WAVE_CAP && list.all(String::isNotBlank) && list.distinct().size == list.size
        } == true && optionalText(value, WorkBoardReadModelPayloadKeys.REASON)

    private fun validPhaseExecution(value: Map<String, Any?>): Boolean {
      val kind =
        requiredEnumValue(
          value,
          WorkBoardReadModelPayloadKeys.PHASE_KIND,
          IdeStatusCurrentPhaseExecutionKind.entries.map { it.wireValue },
        )
      return requiredNonBlankText(value, WorkBoardReadModelPayloadKeys.PHASE_ID) && kind != null &&
        exactInt(value[WorkBoardReadModelPayloadKeys.PHASE_COUNT])?.let { it >= 1 } == true &&
        optionalInt(value, WorkBoardReadModelPayloadKeys.PROGRESS_TOTAL) &&
        (
          value[WorkBoardReadModelPayloadKeys.PROGRESS_TOTAL] == null ||
            !value.containsKey(WorkBoardReadModelPayloadKeys.PROGRESS_TOTAL) ||
            (
              kind == IdeStatusCurrentPhaseExecutionKind.BOUNDED_EDGE.wireValue &&
                exactInt(value[WorkBoardReadModelPayloadKeys.PROGRESS_TOTAL])?.let { it >= 1 } == true
            )
        ) &&
        optionalText(value, WorkBoardReadModelPayloadKeys.PHASE_CRITERION_REF)
    }

    private fun validPauseReason(value: Map<String, Any?>): Boolean =
      requiredEnum(
        value,
        WorkBoardReadModelPayloadKeys.CODE,
        IdeStatusPauseReasonCode.entries.map { it.wireValue },
      ) &&
        optionalText(value, WorkBoardReadModelPayloadKeys.STEP_LABEL) &&
        (value[WorkBoardReadModelPayloadKeys.STEP_LABEL] as? String)?.let {
          it.isNotBlank() &&
            it.length <= IDE_STATUS_PAUSE_REASON_LABEL_MAX_LENGTH
        }
          ?: true

    fun invalidFieldGroup16(fields: Map<String, Any?>): Boolean =
      !optionalObject(fields, WorkBoardReadModelPayloadKeys.PROBLEM) { value ->
        requiredEnum(value, WorkBoardReadModelPayloadKeys.CODE, IdeStatusProblemCode.entries.map { it.wireValue }) &&
          requiredNonBlankText(value, WorkBoardReadModelPayloadKeys.PROBLEM_MESSAGE) &&
          optionalObject(value, WorkBoardReadModelPayloadKeys.PROBLEM_DETAILS) { true }
      } ||
        !optionalObject(fields, WorkBoardReadModelPayloadKeys.EXECUTION, ::validExecution) ||
        !optionalObject(fields, WorkBoardReadModelPayloadKeys.WORKFLOW_QA_OUTCOME) { value ->
          requiredEnum(
            value,
            WorkBoardReadModelPayloadKeys.OUTCOME,
            StandaloneQaOutcome.entries.map { it.wireValue },
          ) &&
            optionalNonBlankText(
              value,
              WorkBoardReadModelPayloadKeys.REASON,
            ) &&
            optionalNonBlankText(
              value,
              WorkBoardReadModelPayloadKeys.QA_FAILURE_KIND,
            ) &&
            requiredNonBlankText(value, WorkBoardReadModelPayloadKeys.QA_RECORDED_AT)
        }
  }
}
