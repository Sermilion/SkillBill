package skillbill.infrastructure.sqlite.idestatus

import skillbill.contracts.JsonCodec
import skillbill.contracts.JsonPayloadContract
import skillbill.contracts.persistence.WorkBoardReadModelPayloadKeys
import skillbill.goalrunner.model.GoalPlanningStatusState
import skillbill.goalrunner.model.GoalRunnerExecutionLease
import skillbill.idestatus.model.AgentActivityLabel
import skillbill.idestatus.model.IdeStatusExecutionScope
import skillbill.idestatus.model.IdeStatusFreshness
import skillbill.idestatus.model.IdeStatusLaunchScope
import skillbill.idestatus.model.IdeStatusLifecycleState
import skillbill.idestatus.model.IdeStatusWorkflowFamily
import skillbill.infrastructure.sqlite.idestatus.WorkBoardReadModelPayloadValidation.exactInt
import skillbill.infrastructure.sqlite.idestatus.WorkBoardReadModelPayloadValidation.exactLong
import skillbill.infrastructure.sqlite.idestatus.WorkBoardReadModelPayloadValidation.nullableStringList
import skillbill.ports.filesystem.model.DatabaseFileObservation
import skillbill.ports.idestatus.model.IdeStatusCurrentModel
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecution
import skillbill.ports.idestatus.model.IdeStatusCurrentPhaseExecutionKind
import skillbill.ports.idestatus.model.IdeStatusCurrentSubtask
import skillbill.ports.idestatus.model.IdeStatusExecutionIdentity
import skillbill.ports.idestatus.model.IdeStatusPauseReason
import skillbill.ports.idestatus.model.IdeStatusPauseReasonCode
import skillbill.ports.idestatus.model.IdeStatusPlanning
import skillbill.ports.idestatus.model.IdeStatusProblem
import skillbill.ports.idestatus.model.IdeStatusProblemCode
import skillbill.ports.idestatus.model.IdeStatusProblemDetails
import skillbill.ports.idestatus.model.IdeStatusProgress
import skillbill.ports.idestatus.model.IdeStatusSnapshot
import skillbill.ports.idestatus.model.IdeStatusStep
import skillbill.ports.idestatus.model.IdeStatusWorkflowQaOutcome
import skillbill.ports.idestatus.model.StandaloneQaOutcome
import skillbill.ports.idestatus.model.WorkBoardCompletionSource
import skillbill.ports.idestatus.model.WorkBoardItem
import skillbill.ports.idestatus.model.WorkBoardReadModelRow
import skillbill.ports.work.model.WorkStoreObservation
import java.nio.file.Path
import java.time.Instant

internal data class WorkBoardRowMetadata(
  val workId: String,
  val updatedAt: Instant,
  val searchText: String,
  val reprojectAt: Instant?,
  val projectedAt: Instant,
  val membershipDeadline: Instant?,
)

internal object WorkBoardReadModelPayloadCodec {
  fun encode(row: WorkBoardReadModelRow): String =
    JsonCodec.mapToJsonString(
      mapOf(
        WorkBoardReadModelPayloadKeys.SNAPSHOT to snapshotMap(row.item.snapshot),
        WorkBoardReadModelPayloadKeys.REPOSITORY_ROOT to row.item.repositoryRoot,
        WorkBoardReadModelPayloadKeys.FINISHED_AT to row.item.finishedAt?.let(WorkBoardReadModelTime::format),
        WorkBoardReadModelPayloadKeys.FINISHED_AT_SOURCE to row.item.finishedAtSource?.wireValue,
        WorkBoardReadModelPayloadKeys.CONTROL_LEASE to row.item.controlLease?.let(::leaseMap),
        WorkBoardReadModelPayloadKeys.OBSERVATION to
          row.item.observation?.let {
            mapOf(
              WorkBoardReadModelPayloadKeys.OBSERVATION_STORE_ID to it.storeId,
              WorkBoardReadModelPayloadKeys.OBSERVATION_REVISION to it.revision,
            )
          },
        WorkBoardReadModelPayloadKeys.FRESHNESS_DEADLINE to
          row.item.freshnessDeadline?.let(WorkBoardReadModelTime::format),
        WorkBoardReadModelPayloadKeys.LIVE_SECTION to row.item.liveSection?.name,
        WorkBoardReadModelPayloadKeys.CONTROL_ELIGIBLE to row.item.controlEligible,
        WorkBoardReadModelPayloadKeys.REQUIRES_ATTENTION to row.item.requiresAttention,
        WorkBoardReadModelPayloadKeys.DISPLAY_GROUP to row.item.displayGroup?.name,
        WorkBoardReadModelPayloadKeys.DISPLAY_PRIORITY to row.item.displayPriority,
        WorkBoardReadModelPayloadKeys.EXACT_PAUSE_REASON to row.item.exactPauseReason,
        WorkBoardReadModelPayloadKeys.PAUSE_ELIGIBLE to row.item.pauseEligible,
        WorkBoardReadModelPayloadKeys.RUNNING to row.item.running,
        WorkBoardReadModelPayloadKeys.RECENTLY_FINISHED_PHASE to row.item.recentlyFinishedPhase,
        WorkBoardReadModelPayloadKeys.DONE_WITHIN_DAY to row.item.doneWithinDay,
        WorkBoardReadModelPayloadKeys.MEMBERSHIP_DEADLINE to
          row.item.membershipDeadline?.let(WorkBoardReadModelTime::format),
        WorkBoardReadModelPayloadKeys.DATABASE_FILE_OBSERVATION to
          row.item.databaseFileObservation?.let {
            mapOf(
              WorkBoardReadModelPayloadKeys.DB_PATH to it.path.toString(),
              WorkBoardReadModelPayloadKeys.DB_FILE_KEY to it.fileKey,
            )
          },
      ),
    )

  fun decode(
    payload: String,
    metadata: WorkBoardRowMetadata,
  ): WorkBoardReadModelRow? {
    val fields =
      JsonCodec.parseObjectOrNull(payload)
        ?.let(JsonCodec::jsonElementToValue)
        ?.let(JsonCodec::anyToStringAnyMap)
        ?: return null
    if (!WorkBoardReadModelPayloadValidation.validItem(fields)) return null
    val snapshotFields = JsonCodec.anyToStringAnyMap(fields[WorkBoardReadModelPayloadKeys.SNAPSHOT]) ?: return null
    val snapshot = snapshotFrom(snapshotFields)
    val item =
      WorkBoardItem(
        snapshot = snapshot,
        repositoryRoot = fields.text(WorkBoardReadModelPayloadKeys.REPOSITORY_ROOT),
        finishedAt = fields.instant(WorkBoardReadModelPayloadKeys.FINISHED_AT),
        finishedAtSource =
          fields.nullableText(WorkBoardReadModelPayloadKeys.FINISHED_AT_SOURCE)?.let { value ->
            WorkBoardCompletionSource.entries.firstOrNull { it.wireValue == value }
              ?: error("Unknown completion source.")
          },
        controlLease = fields.map(WorkBoardReadModelPayloadKeys.CONTROL_LEASE)?.let(::leaseFrom),
        observation =
          fields.map(WorkBoardReadModelPayloadKeys.OBSERVATION)?.let {
            WorkStoreObservation(
              it.text(WorkBoardReadModelPayloadKeys.OBSERVATION_STORE_ID),
              it.long(WorkBoardReadModelPayloadKeys.OBSERVATION_REVISION),
            )
          },
        freshnessDeadline = fields.instant(WorkBoardReadModelPayloadKeys.FRESHNESS_DEADLINE),
        liveSection = fields.enumOrNull(WorkBoardReadModelPayloadKeys.LIVE_SECTION),
        controlEligible = fields.boolean(WorkBoardReadModelPayloadKeys.CONTROL_ELIGIBLE),
        requiresAttention = fields.boolean(WorkBoardReadModelPayloadKeys.REQUIRES_ATTENTION),
        displayGroup = fields.enumOrNull(WorkBoardReadModelPayloadKeys.DISPLAY_GROUP),
        displayPriority = fields.int(WorkBoardReadModelPayloadKeys.DISPLAY_PRIORITY),
        exactPauseReason = fields.nullableText(WorkBoardReadModelPayloadKeys.EXACT_PAUSE_REASON),
        pauseEligible = fields.boolean(WorkBoardReadModelPayloadKeys.PAUSE_ELIGIBLE),
        running = fields.boolean(WorkBoardReadModelPayloadKeys.RUNNING),
        recentlyFinishedPhase = fields.boolean(WorkBoardReadModelPayloadKeys.RECENTLY_FINISHED_PHASE),
        doneWithinDay = fields.boolean(WorkBoardReadModelPayloadKeys.DONE_WITHIN_DAY),
        membershipDeadline = fields.instant(WorkBoardReadModelPayloadKeys.MEMBERSHIP_DEADLINE),
        databaseFileObservation =
          fields.map(WorkBoardReadModelPayloadKeys.DATABASE_FILE_OBSERVATION)?.let {
            DatabaseFileObservation(
              Path.of(it.text(WorkBoardReadModelPayloadKeys.DB_PATH)),
              it.text(WorkBoardReadModelPayloadKeys.DB_FILE_KEY),
            )
          },
      )
    return WorkBoardReadModelRow(
      metadata.workId,
      item,
      metadata.updatedAt,
      metadata.searchText,
      metadata.reprojectAt,
      metadata.projectedAt,
      metadata.membershipDeadline,
    )
  }

  private fun snapshotMap(snapshot: IdeStatusSnapshot): Map<String, Any?> =
    snapshotIdentityMap(snapshot) + snapshotProgressMap(snapshot) + snapshotStateMap(snapshot)

  private fun snapshotIdentityMap(snapshot: IdeStatusSnapshot): Map<String, Any?> =
    mapOf(
      WorkBoardReadModelPayloadKeys.REPOSITORY_IDENTITY to snapshot.repositoryIdentity,
      WorkBoardReadModelPayloadKeys.LIFECYCLE_STATE to snapshot.lifecycleState.wireValue,
      WorkBoardReadModelPayloadKeys.CURRENT_STEP to
        mapOf(
          WorkBoardReadModelPayloadKeys.STEP_ID to
            snapshot.currentStep.id,
          WorkBoardReadModelPayloadKeys.STEP_LABEL to snapshot.currentStep.label,
        ),
      WorkBoardReadModelPayloadKeys.UPDATED_AT to WorkBoardReadModelTime.format(snapshot.updatedAt),
      WorkBoardReadModelPayloadKeys.FRESHNESS to snapshot.freshness.wireValue,
      WorkBoardReadModelPayloadKeys.SUMMARY to snapshot.summary,
      WorkBoardReadModelPayloadKeys.ISSUE_KEY to snapshot.issueKey,
      WorkBoardReadModelPayloadKeys.WORKFLOW_ID to snapshot.workflowId,
      WorkBoardReadModelPayloadKeys.WORKFLOW_FAMILY to snapshot.workflowFamily?.wireValue,
      WorkBoardReadModelPayloadKeys.STARTED_AT to snapshot.startedAt?.let(WorkBoardReadModelTime::format),
      WorkBoardReadModelPayloadKeys.PROGRESS to
        snapshot.progress?.let {
          mapOf(
            WorkBoardReadModelPayloadKeys.PROGRESS_COMPLETED to
              it.completed,
            WorkBoardReadModelPayloadKeys.PROGRESS_TOTAL to it.total,
          )
        },
    )

  private fun snapshotProgressMap(snapshot: IdeStatusSnapshot): Map<String, Any?> =
    mapOf(
      WorkBoardReadModelPayloadKeys.CURRENT_SUBTASK to
        snapshot.currentSubtask?.let {
          mapOf(
            WorkBoardReadModelPayloadKeys.STEP_ID to it.id,
            WorkBoardReadModelPayloadKeys.STARTED_AT to it.startedAt?.let(WorkBoardReadModelTime::format),
            WorkBoardReadModelPayloadKeys.ACTIVE_DURATION_MS to it.activeDurationMs,
            WorkBoardReadModelPayloadKeys.ACTIVE_DURATION_AS_OF to
              it.activeDurationAsOf?.let(WorkBoardReadModelTime::format),
          )
        },
      WorkBoardReadModelPayloadKeys.CURRENT_MODEL to
        snapshot.currentModel?.let {
          mapOf(
            WorkBoardReadModelPayloadKeys.MODEL_NAME to
              it.model,
            WorkBoardReadModelPayloadKeys.MODEL_EFFORT to it.effort,
            WorkBoardReadModelPayloadKeys.PHASE_ID to it.phaseId,
          )
        },
      WorkBoardReadModelPayloadKeys.PLANNING to
        snapshot.planning?.let {
          mapOf(
            WorkBoardReadModelPayloadKeys.PLANNING_STATE to it.state.wireValue,
            WorkBoardReadModelPayloadKeys.PLANNING_SHARED_PREPLAN_PREPARED to it.sharedPreplanPrepared,
            WorkBoardReadModelPayloadKeys.PLANNING_SUBTASK_COUNT to it.plannedSubtaskCount,
            WorkBoardReadModelPayloadKeys.PLANNING_TOTAL_SUBTASK_COUNT to it.totalSubtaskCount,
            WorkBoardReadModelPayloadKeys.PLANNING_CURRENT_SUBTASK_ID to it.currentPlanningSubtaskId,
            WorkBoardReadModelPayloadKeys.PLANNING_WAVE_SUBTASK_IDS to it.planningWaveSubtaskIds,
            WorkBoardReadModelPayloadKeys.REASON to it.reason,
          )
        },
      WorkBoardReadModelPayloadKeys.CURRENT_PHASE_EXECUTION to
        snapshot.currentPhaseExecution?.let {
          mapOf(
            WorkBoardReadModelPayloadKeys.PHASE_ID to it.phaseId,
            WorkBoardReadModelPayloadKeys.PHASE_KIND to it.kind.wireValue,
            WorkBoardReadModelPayloadKeys.PHASE_COUNT to it.count,
            WorkBoardReadModelPayloadKeys.PROGRESS_TOTAL to it.total,
            WorkBoardReadModelPayloadKeys.PHASE_CRITERION_REF to it.criterionRef,
          )
        },
      WorkBoardReadModelPayloadKeys.PAUSE_REASON to
        snapshot.pauseReason?.let {
          mapOf(
            WorkBoardReadModelPayloadKeys.CODE to
              it.code.wireValue,
            WorkBoardReadModelPayloadKeys.STEP_LABEL to it.label,
          )
        },
    )

  private fun snapshotStateMap(snapshot: IdeStatusSnapshot): Map<String, Any?> =
    mapOf(
      WorkBoardReadModelPayloadKeys.PAUSE_REQUESTED to snapshot.pauseRequested,
      WorkBoardReadModelPayloadKeys.PAUSED_AT to snapshot.pausedAt?.let(WorkBoardReadModelTime::format),
      WorkBoardReadModelPayloadKeys.ACTIVE_DURATION_MS to snapshot.activeDurationMs,
      WorkBoardReadModelPayloadKeys.ACTIVE_DURATION_AS_OF to
        snapshot.activeDurationAsOf?.let(WorkBoardReadModelTime::format),
      WorkBoardReadModelPayloadKeys.LAST_AGENT_ACTIVITY_AT to
        snapshot.lastAgentActivityAt?.let(WorkBoardReadModelTime::format),
      WorkBoardReadModelPayloadKeys.LAST_AGENT_ACTIVITY_LABEL to snapshot.lastAgentActivityLabel?.wireValue,
      WorkBoardReadModelPayloadKeys.STALLED_SINCE to snapshot.stalledSince?.let(WorkBoardReadModelTime::format),
      WorkBoardReadModelPayloadKeys.PROBLEM to
        snapshot.problem?.let {
          mapOf(
            WorkBoardReadModelPayloadKeys.CODE to
              it.code.wireValue,
            WorkBoardReadModelPayloadKeys.PROBLEM_MESSAGE to it.message,
            WorkBoardReadModelPayloadKeys.PROBLEM_DETAILS to it.details?.asWirePayload()?.toPayload(),
          )
        },
      WorkBoardReadModelPayloadKeys.EXECUTION to
        snapshot.execution?.let {
          mapOf(
            WorkBoardReadModelPayloadKeys.EXECUTION_SCOPE to it.scope.wireValue,
            WorkBoardReadModelPayloadKeys.EXECUTION_ID to it.executionId,
            WorkBoardReadModelPayloadKeys.STATUS_STORE_ID to it.statusStoreId,
            WorkBoardReadModelPayloadKeys.RUN_SEQUENCE to it.runSequence,
            WorkBoardReadModelPayloadKeys.STATUS_REVISION to it.statusRevision,
            WorkBoardReadModelPayloadKeys.INVOCATION_ID to it.invocationId,
            WorkBoardReadModelPayloadKeys.PHASE_ID to it.phaseId,
            WorkBoardReadModelPayloadKeys.LAUNCH_SCOPE to it.launchScope.wireValue,
            WorkBoardReadModelPayloadKeys.QA_OUTCOME to it.qaOutcome?.wireValue,
          )
        },
      WorkBoardReadModelPayloadKeys.WORKFLOW_QA_OUTCOME to
        snapshot.workflowQaOutcome?.let {
          mapOf(
            WorkBoardReadModelPayloadKeys.OUTCOME to it.outcome.wireValue,
            WorkBoardReadModelPayloadKeys.REASON to it.reason,
            WorkBoardReadModelPayloadKeys.QA_FAILURE_KIND to it.failureKind,
            WorkBoardReadModelPayloadKeys.QA_RECORDED_AT to it.recordedAt,
          )
        },
      WorkBoardReadModelPayloadKeys.CURRENT_ACTIVITY to snapshot.currentActivity,
      WorkBoardReadModelPayloadKeys.BRANCH_CORRELATION to snapshot.branchCorrelation,
      WorkBoardReadModelPayloadKeys.CONTRACT_VERSION to snapshot.contractVersion,
    )

  private fun snapshotFrom(fields: Map<String, Any?>): IdeStatusSnapshot =
    snapshotActivity(snapshotPlanning(snapshotIdentity(fields), fields), fields)

  private fun snapshotIdentity(fields: Map<String, Any?>): IdeStatusSnapshot =
    IdeStatusSnapshot(
      repositoryIdentity = fields.text(WorkBoardReadModelPayloadKeys.REPOSITORY_IDENTITY),
      lifecycleState =
        fields.wireEnum(
          WorkBoardReadModelPayloadKeys.LIFECYCLE_STATE,
          IdeStatusLifecycleState.entries,
        ) { it.wireValue },
      currentStep =
        IdeStatusStep(
          fields.map(WorkBoardReadModelPayloadKeys.CURRENT_STEP)?.text(WorkBoardReadModelPayloadKeys.STEP_ID)
            ?: error("Missing current step id."),
          fields.map(WorkBoardReadModelPayloadKeys.CURRENT_STEP)?.text(WorkBoardReadModelPayloadKeys.STEP_LABEL)
            ?: error("Missing current step label."),
        ),
      updatedAt =
        fields.instant(
          WorkBoardReadModelPayloadKeys.UPDATED_AT,
        ) ?: error("Missing snapshot update time."),
      freshness =
        fields.wireEnum(
          WorkBoardReadModelPayloadKeys.FRESHNESS,
          IdeStatusFreshness.entries,
        ) { it.wireValue },
      summary = fields.text(WorkBoardReadModelPayloadKeys.SUMMARY),
      issueKey = fields.nullableText(WorkBoardReadModelPayloadKeys.ISSUE_KEY),
      workflowId = fields.nullableText(WorkBoardReadModelPayloadKeys.WORKFLOW_ID),
      workflowFamily =
        fields.wireEnumOrNull(
          WorkBoardReadModelPayloadKeys.WORKFLOW_FAMILY,
          IdeStatusWorkflowFamily.entries,
        ) { it.wireValue },
      progress =
        fields.map(WorkBoardReadModelPayloadKeys.PROGRESS)?.let {
          IdeStatusProgress(
            it.int(WorkBoardReadModelPayloadKeys.PROGRESS_COMPLETED),
            it.int(WorkBoardReadModelPayloadKeys.PROGRESS_TOTAL),
          )
        },
      startedAt = fields.instant(WorkBoardReadModelPayloadKeys.STARTED_AT),
      currentSubtask =
        fields.map(WorkBoardReadModelPayloadKeys.CURRENT_SUBTASK)?.let {
          IdeStatusCurrentSubtask(
            it.text(WorkBoardReadModelPayloadKeys.STEP_ID),
            it.instant(WorkBoardReadModelPayloadKeys.STARTED_AT),
            it.nullableLong(WorkBoardReadModelPayloadKeys.ACTIVE_DURATION_MS),
            it.instant(WorkBoardReadModelPayloadKeys.ACTIVE_DURATION_AS_OF),
          )
        },
      currentModel =
        fields.map(WorkBoardReadModelPayloadKeys.CURRENT_MODEL)?.let {
          IdeStatusCurrentModel(
            it.text(WorkBoardReadModelPayloadKeys.MODEL_NAME),
            it.nullableText(WorkBoardReadModelPayloadKeys.MODEL_EFFORT),
            it.nullableText(WorkBoardReadModelPayloadKeys.PHASE_ID),
          )
        },
    )

  private fun snapshotPlanning(
    snapshot: IdeStatusSnapshot,
    fields: Map<String, Any?>,
  ): IdeStatusSnapshot =
    snapshot.copy(
      planning =
        fields.map(WorkBoardReadModelPayloadKeys.PLANNING)?.let {
          IdeStatusPlanning(
            it.wireEnum(
              WorkBoardReadModelPayloadKeys.PLANNING_STATE,
              GoalPlanningStatusState.entries,
            ) { value -> value.wireValue },
            it.boolean(WorkBoardReadModelPayloadKeys.PLANNING_SHARED_PREPLAN_PREPARED),
            it.int(WorkBoardReadModelPayloadKeys.PLANNING_SUBTASK_COUNT),
            it.int(WorkBoardReadModelPayloadKeys.PLANNING_TOTAL_SUBTASK_COUNT),
            it.nullableText(WorkBoardReadModelPayloadKeys.PLANNING_CURRENT_SUBTASK_ID),
            it.stringList(WorkBoardReadModelPayloadKeys.PLANNING_WAVE_SUBTASK_IDS),
            it.nullableText(WorkBoardReadModelPayloadKeys.REASON),
          )
        },
      currentPhaseExecution =
        fields.map(WorkBoardReadModelPayloadKeys.CURRENT_PHASE_EXECUTION)?.let {
          IdeStatusCurrentPhaseExecution(
            it.text(WorkBoardReadModelPayloadKeys.PHASE_ID),
            it.wireEnum(
              WorkBoardReadModelPayloadKeys.PHASE_KIND,
              IdeStatusCurrentPhaseExecutionKind.entries,
            ) { value -> value.wireValue },
            it.int(WorkBoardReadModelPayloadKeys.PHASE_COUNT),
            it.nullableInt(WorkBoardReadModelPayloadKeys.PROGRESS_TOTAL),
            it.nullableText(WorkBoardReadModelPayloadKeys.PHASE_CRITERION_REF),
          )
        },
      pauseRequested = fields.nullableBoolean(WorkBoardReadModelPayloadKeys.PAUSE_REQUESTED),
      pausedAt = fields.instant(WorkBoardReadModelPayloadKeys.PAUSED_AT),
      pauseReason =
        fields.map(WorkBoardReadModelPayloadKeys.PAUSE_REASON)?.let {
          IdeStatusPauseReason(
            IdeStatusPauseReasonCode.entries.first { reason ->
              reason.wireValue == it.text(WorkBoardReadModelPayloadKeys.CODE)
            },
            it.nullableText(WorkBoardReadModelPayloadKeys.STEP_LABEL),
          )
        },
    )

  private fun snapshotActivity(
    snapshot: IdeStatusSnapshot,
    fields: Map<String, Any?>,
  ): IdeStatusSnapshot =
    snapshot.copy(
      activeDurationMs = fields.nullableLong(WorkBoardReadModelPayloadKeys.ACTIVE_DURATION_MS),
      activeDurationAsOf = fields.instant(WorkBoardReadModelPayloadKeys.ACTIVE_DURATION_AS_OF),
      lastAgentActivityAt = fields.instant(WorkBoardReadModelPayloadKeys.LAST_AGENT_ACTIVITY_AT),
      lastAgentActivityLabel =
        fields.nullableText(WorkBoardReadModelPayloadKeys.LAST_AGENT_ACTIVITY_LABEL)
          ?.let(AgentActivityLabel::fromWire),
      stalledSince = fields.instant(WorkBoardReadModelPayloadKeys.STALLED_SINCE),
      problem =
        fields.map(WorkBoardReadModelPayloadKeys.PROBLEM)?.let {
          IdeStatusProblem(
            IdeStatusProblemCode.entries.first {
                code ->
              code.wireValue == it.text(WorkBoardReadModelPayloadKeys.CODE)
            },
            it.text(WorkBoardReadModelPayloadKeys.PROBLEM_MESSAGE),
            it.map(WorkBoardReadModelPayloadKeys.PROBLEM_DETAILS)?.let { payload ->
              IdeStatusProblemDetails.from(
                object : JsonPayloadContract {
                  override fun toPayload() = payload
                },
              )
            },
          )
        },
      execution =
        fields.map(WorkBoardReadModelPayloadKeys.EXECUTION)?.let {
          IdeStatusExecutionIdentity(
            it.wireEnum(
              WorkBoardReadModelPayloadKeys.EXECUTION_SCOPE,
              IdeStatusExecutionScope.entries,
            ) { value -> value.wireValue },
            it.text(WorkBoardReadModelPayloadKeys.EXECUTION_ID),
            it.text(WorkBoardReadModelPayloadKeys.STATUS_STORE_ID),
            it.text(WorkBoardReadModelPayloadKeys.RUN_SEQUENCE),
            it.text(WorkBoardReadModelPayloadKeys.STATUS_REVISION),
            it.nullableText(WorkBoardReadModelPayloadKeys.INVOCATION_ID),
            it.nullableText(WorkBoardReadModelPayloadKeys.PHASE_ID),
            it.wireEnum(
              WorkBoardReadModelPayloadKeys.LAUNCH_SCOPE,
              IdeStatusLaunchScope.entries,
            ) { value -> value.wireValue },
            it.nullableText(WorkBoardReadModelPayloadKeys.QA_OUTCOME)?.let(StandaloneQaOutcome::fromWire),
          )
        },
      currentActivity = fields.nullableText(WorkBoardReadModelPayloadKeys.CURRENT_ACTIVITY),
      branchCorrelation = fields.nullableText(WorkBoardReadModelPayloadKeys.BRANCH_CORRELATION),
      workflowQaOutcome =
        fields.map(WorkBoardReadModelPayloadKeys.WORKFLOW_QA_OUTCOME)?.let {
          IdeStatusWorkflowQaOutcome(
            StandaloneQaOutcome.fromWire(it.text(WorkBoardReadModelPayloadKeys.OUTCOME))
              ?: error("Unknown QA outcome."),
            it.nullableText(WorkBoardReadModelPayloadKeys.REASON),
            it.nullableText(WorkBoardReadModelPayloadKeys.QA_FAILURE_KIND),
            it.text(WorkBoardReadModelPayloadKeys.QA_RECORDED_AT),
          )
        },
      contractVersion = fields.text(WorkBoardReadModelPayloadKeys.CONTRACT_VERSION),
    )

  private fun leaseMap(lease: GoalRunnerExecutionLease): Map<String, Any?> =
    mapOf(
      WorkBoardReadModelPayloadKeys.LEASE_GENERATION to lease.generation,
      WorkBoardReadModelPayloadKeys.LEASE_OWNER_TOKEN to lease.ownerToken,
      WorkBoardReadModelPayloadKeys.LEASE_HOST_IDENTITY to lease.hostIdentity,
      WorkBoardReadModelPayloadKeys.LEASE_BOOT_IDENTITY to lease.bootIdentity,
      WorkBoardReadModelPayloadKeys.LEASE_PID to lease.pid,
      WorkBoardReadModelPayloadKeys.LEASE_PROCESS_BIRTH_TOKEN to lease.processBirthToken,
      WorkBoardReadModelPayloadKeys.LEASE_HEARTBEAT_AT to WorkBoardReadModelTime.format(lease.heartbeatAt),
      WorkBoardReadModelPayloadKeys.LEASE_EXPIRES_AT to WorkBoardReadModelTime.format(lease.expiresAt),
    )

  private fun leaseFrom(fields: Map<String, Any?>): GoalRunnerExecutionLease =
    GoalRunnerExecutionLease(
      fields.long(WorkBoardReadModelPayloadKeys.LEASE_GENERATION),
      fields.text(WorkBoardReadModelPayloadKeys.LEASE_OWNER_TOKEN),
      fields.text(WorkBoardReadModelPayloadKeys.LEASE_HOST_IDENTITY),
      fields.text(WorkBoardReadModelPayloadKeys.LEASE_BOOT_IDENTITY),
      fields.long(WorkBoardReadModelPayloadKeys.LEASE_PID),
      fields.text(WorkBoardReadModelPayloadKeys.LEASE_PROCESS_BIRTH_TOKEN),
      WorkBoardReadModelTime.parse(fields.text(WorkBoardReadModelPayloadKeys.LEASE_HEARTBEAT_AT)),
      WorkBoardReadModelTime.parse(fields.text(WorkBoardReadModelPayloadKeys.LEASE_EXPIRES_AT)),
    )

  private fun <T> Map<String, Any?>.wireEnum(
    key: String,
    values: List<T>,
    wire: (T) -> String,
  ): T = values.firstOrNull { wire(it) == text(key) } ?: error("Unknown $key.")

  private fun <T> Map<String, Any?>.wireEnumOrNull(
    key: String,
    values: List<T>,
    wire: (T) -> String,
  ): T? = nullableText(key)?.let { value -> values.firstOrNull { wire(it) == value } ?: error("Unknown $key.") }

  private inline fun <reified T : Enum<T>> Map<
    String,
    Any?,
  >.enumOrNull(key: String): T? =
    nullableText(key)?.let(::enumValueOf)

  private fun Map<String, Any?>.text(key: String): String = this[key] as? String ?: error("Missing $key.")

  private fun Map<String, Any?>.nullableText(key: String): String? = this[key] as? String

  private fun Map<String, Any?>.boolean(key: String): Boolean = this[key] as? Boolean ?: error("Missing $key.")

  private fun Map<String, Any?>.nullableBoolean(key: String): Boolean? = this[key] as? Boolean

  private fun Map<String, Any?>.int(key: String): Int = exactInt(this[key]) ?: error("Invalid $key.")

  private fun Map<String, Any?>.long(key: String): Long = exactLong(this[key]) ?: error("Invalid $key.")

  private fun Map<String, Any?>.nullableLong(key: String): Long? = this[key]?.let(::exactLong)

  private fun Map<String, Any?>.nullableInt(key: String): Int? = this[key]?.let(::exactInt)

  private fun Map<
    String,
    Any?,
  >.stringList(key: String): List<String> =
    nullableStringList(
      this,
      key,
    ) ?: error("Invalid $key.")

  private fun Map<String, Any?>.instant(key: String): Instant? = nullableText(key)?.let(WorkBoardReadModelTime::parse)

  private fun Map<String, Any?>.map(key: String): Map<String, Any?>? = JsonCodec.anyToStringAnyMap(this[key])
}
