package dev.skillbill.tray.infrastructure.cli

import dev.skillbill.tray.domain.*
import dev.skillbill.tray.domain.Retention
import kotlinx.serialization.json.*
import java.time.Instant

class IncompatibleContractException : IllegalArgumentException("The Skill Bill CLI returned an incompatible contract.")
class MalformedPayloadException : IllegalArgumentException("The Skill Bill CLI returned malformed data.")

object WorkPayloadDecoder {
  fun board(source: String): Board {
    val root = source.jsonObjectOrFail()
    if (root.string(WorkPayloadKeys.CONTRACT_VERSION) != "1.1") throw IncompatibleContractException()
    val pageSize = root.requiredInt(WorkPayloadKeys.PAGE_SIZE)
    if (pageSize !in 1..100 || !root.containsKey(WorkPayloadKeys.NEXT_CURSOR)) throw MalformedPayloadException()
    val nextCursor = root.optionalString(WorkPayloadKeys.NEXT_CURSOR)
    if (nextCursor != null && nextCursor.isBlank()) throw MalformedPayloadException()
    val observed = root.instant(WorkPayloadKeys.OBSERVED_AT)
    val retention = Retention.entries.singleOrNull { it.cliValue == root.string(WorkPayloadKeys.FINISHED_WITHIN) } ?: throw MalformedPayloadException()
    val items = root.array(WorkPayloadKeys.ITEMS).map { decodeItem(it.jsonObjectOrFail()) }
    val diagnostics = root.array(WorkPayloadKeys.DIAGNOSTICS).map { diagnostic ->
      val reason = diagnostic.jsonObjectOrFail().string(WorkPayloadKeys.REASON)
      if (reason !in setOf("missing_repository_identity", "invalid_repository_identity", "missing_completion_time")) throw MalformedPayloadException()
      reason
    }
    val problem = root[WorkPayloadKeys.PROBLEM]?.jsonObjectOrFail()?.let { Problem(it.string(WorkPayloadKeys.CODE), "Skill Bill's database has not been created yet.") }
    if (problem != null && problem.code != "absent_database") throw MalformedPayloadException()
    if (items.size > pageSize || (problem != null && nextCursor != null)) throw MalformedPayloadException()
    return Board(observed, retention, items, problem, diagnostics, nextCursor)
  }

  fun detail(source: String, expectedIdentity: WorkIdentity? = null): DetailLookup {
    val root = source.jsonObjectOrFail()
    if (root.string(WorkPayloadKeys.CONTRACT_VERSION) != "1.1") throw IncompatibleContractException()
    val observed = root.instant(WorkPayloadKeys.OBSERVED_AT)
    val pageSize = root.requiredInt(WorkPayloadKeys.PAGE_SIZE)
    if (pageSize !in 1..100 || !root.containsKey(WorkPayloadKeys.NEXT_CURSOR)) throw MalformedPayloadException()
    val nextCursor = root.optionalString(WorkPayloadKeys.NEXT_CURSOR)
    if (nextCursor != null && nextCursor.isBlank()) throw MalformedPayloadException()
    val item = root[WorkPayloadKeys.ITEM] ?: throw MalformedPayloadException()
    if (item == JsonNull) {
      if (nextCursor != null) throw MalformedPayloadException()
      val problem = root[WorkPayloadKeys.PROBLEM].jsonObjectOrFail()
      val code = problem.string(WorkPayloadKeys.CODE)
      problem.string(WorkPayloadKeys.MESSAGE)
      if (code !in setOf("absent_database", "unknown_work_item", "ambiguous_work_item", "invalid_repository_identity")) throw MalformedPayloadException()
      return DetailLookup(null, Problem(code, safeDetailProblem(code)))
    }
    if (root.containsKey(WorkPayloadKeys.PROBLEM)) throw MalformedPayloadException()
    val value = item.jsonObjectOrFail()
    listOf(WorkPayloadKeys.SUBTASKS, WorkPayloadKeys.PHASES, WorkPayloadKeys.EXECUTION_HISTORY, WorkPayloadKeys.COMPLETED_SUBTASK_VALIDATION).forEach { key ->
      if (value.objectListOrNull(key).orEmpty().size > pageSize) throw MalformedPayloadException()
    }
    val work = decodeItem(value)
    if (expectedIdentity != null && work.identity != expectedIdentity) {
      return DetailLookup(null, Problem("unknown_work_item", safeDetailProblem("unknown_work_item")))
    }
    return DetailLookup(Detail(
      item = work,
      stepPipeline = value.requiredStringList(WorkPayloadKeys.STEP_PIPELINE),
      goalFinishPipeline = value.stringList(WorkPayloadKeys.GOAL_FINISH_PIPELINE),
      featureTitle = value.optionalString(WorkPayloadKeys.FEATURE_TITLE),
      currentPhase = value.optionalString(WorkPayloadKeys.CURRENT_PHASE),
      completeCount = value.optionalInt(WorkPayloadKeys.COMPLETE_COUNT) ?: 0,
      pendingCount = value.optionalInt(WorkPayloadKeys.PENDING_COUNT) ?: 0,
      blockedCount = value.optionalInt(WorkPayloadKeys.BLOCKED_COUNT) ?: 0,
      activeAgent = value.optionalString(WorkPayloadKeys.ACTIVE_AGENT),
      executionLiveness = value.optionalString(WorkPayloadKeys.EXECUTION_LIVENESS),
      extras = emptyMap(),
      observedAt = observed,
      nextCursor = nextCursor,
      executionHistory = value.objectListOrNull(WorkPayloadKeys.EXECUTION_HISTORY).orEmpty().map { instance ->
        ExecutionInstance(instance.string(WorkPayloadKeys.INVOCATION_ID), instance.string(WorkPayloadKeys.EXECUTION_ID),
          instance.string(WorkPayloadKeys.LIFECYCLE_STATE), instance.instant(WorkPayloadKeys.STARTED_AT),
          instance.instant(WorkPayloadKeys.UPDATED_AT), instance.optionalInstant(WorkPayloadKeys.FINISHED_AT),
          instance.optionalString(WorkPayloadKeys.EXECUTION_RESULT))
      },
      attempts = DetailAttempts(
        value.intMap(WorkPayloadKeys.PHASE_ATTEMPT_COUNTS),
        value.intMap(WorkPayloadKeys.CUMULATIVE_FIX_ITERATIONS),
        value.intMap(WorkPayloadKeys.RE_ATTEMPT_CAUSE_COUNTS),
        value.optionalInt(WorkPayloadKeys.FINDINGS_IN_SCOPE),
        value.optionalInt(WorkPayloadKeys.SUPERVISOR_KILL_COUNT) ?: 0,
      ),
      validation = value.objectListOrNull(WorkPayloadKeys.COMPLETED_SUBTASK_VALIDATION).orEmpty().map(::decodeValidation),
      latestActivity = LatestActivity(
        event = value.objectOrNull(WorkPayloadKeys.LATEST_OBSERVABILITY_EVENT)?.let { event ->
          ActivityEvent(event.optionalString(WorkPayloadKeys.WORKFLOW_PHASE), event.optionalString(WorkPayloadKeys.WORKER_ROLE), event.optionalString(WorkPayloadKeys.ACTIVITY_SUMMARY), event.optionalInstant(WorkPayloadKeys.TIMESTAMP))
        },
        edit = value.objectOrNull(WorkPayloadKeys.LATEST_WORKTREE_EDIT)?.let { edit ->
          WorktreeEdit(edit.optionalInstant(WorkPayloadKeys.RECORDED_AT), edit.stringList(WorkPayloadKeys.PATH_SAMPLE), edit.optionalInt(WorkPayloadKeys.NET_INSERTIONS), edit.optionalInt(WorkPayloadKeys.NET_DELETIONS), edit.optionalString(WorkPayloadKeys.PHASE_ID))
        },
      ),
      subtasks = value.stringObjectList(WorkPayloadKeys.SUBTASKS).map { subtask ->
        WorkSubtask(
          id = subtask.requiredInt(WorkPayloadKeys.SUBTASK_ID),
          name = subtask.string(WorkPayloadKeys.NAME),
          status = subtask.string(WorkPayloadKeys.STATUS),
          commitSha = subtask.optionalString(WorkPayloadKeys.COMMIT_SHA),
          blockedReason = subtask.optionalString(WorkPayloadKeys.BLOCKED_REASON),
          dependencies = subtask.stringObjectList(WorkPayloadKeys.DEPENDENCIES).map { dependency ->
            WorkDependency(dependency.requiredInt(WorkPayloadKeys.SUBTASK_ID), dependency.requiredBoolean(WorkPayloadKeys.OPTIONAL), dependency.requiredBoolean(WorkPayloadKeys.SKIPPED))
          },
        )
      },
      phases = value.stringObjectList(WorkPayloadKeys.PHASES).map { phase ->
        WorkPhase(phase.string(WorkPayloadKeys.PHASE_ID), phase.string(WorkPayloadKeys.STATUS), phase.requiredInt(WorkPayloadKeys.ATTEMPT_COUNT), phase.requiredBoolean(WorkPayloadKeys.FINISHED))
      },
    ), null)
  }

  private fun safeDetailProblem(code: String) = when (code) {
    "absent_database" -> "Skill Bill's database has not been created yet."
    "unknown_work_item" -> "This work item is no longer available."
    "ambiguous_work_item" -> "Skill Bill found more than one item for this identifier."
    else -> "This work item has no usable repository identity."
  }

  private fun decodeItem(wrapper: JsonObject): WorkItem {
    val snapshot = wrapper[WorkPayloadKeys.SNAPSHOT].jsonObjectOrFail()
    if (snapshot.string(WorkPayloadKeys.CONTRACT_VERSION) != "0.3") throw MalformedPayloadException()
    val root = wrapper.string(WorkPayloadKeys.REPOSITORY_ROOT).takeIf(String::isNotBlank) ?: throw MalformedPayloadException()
    val repository = snapshot.string(WorkPayloadKeys.REPOSITORY_IDENTITY).takeIf(String::isNotBlank) ?: throw MalformedPayloadException()
    val familyValue = snapshot.optionalString(WorkPayloadKeys.WORKFLOW_FAMILY)
    val scopeValue = snapshot.optionalString(WorkPayloadKeys.EXECUTION_SCOPE)
    val completionSource = wrapper.optionalString(WorkPayloadKeys.FINISHED_AT_SOURCE)
    if (familyValue != null && familyValue !in setOf("feature-goal", "feature-task-runtime", "feature-verify")) throw MalformedPayloadException()
    if (scopeValue != null && scopeValue !in setOf("workflow", "standalone_phase")) throw MalformedPayloadException()
    if (completionSource != null && completionSource !in setOf("standalone_finished_at", "workflow_completion", "goal_completion", "goal_state_entry", "estimated_goal_state_entry")) throw MalformedPayloadException()
    val step = snapshot[WorkPayloadKeys.CURRENT_STEP].jsonObjectOrFail()
    val progress = snapshot[WorkPayloadKeys.PROGRESS]?.jsonObjectOrFail()
    val subtask = snapshot[WorkPayloadKeys.CURRENT_SUBTASK]?.jsonObjectOrFail()
    val planning = snapshot[WorkPayloadKeys.PLANNING]?.jsonObjectOrFail()
    val execution = snapshot[WorkPayloadKeys.CURRENT_PHASE_EXECUTION]?.jsonObjectOrFail()
    val model = snapshot[WorkPayloadKeys.CURRENT_MODEL]?.jsonObjectOrFail()
    val executionKind = execution?.optionalString(WorkPayloadKeys.KIND)?.let { value ->
      when (value) {
        WorkPayloadKeys.PASS -> PhaseExecutionKind.PASS
        WorkPayloadKeys.SEMANTIC_LOOP -> PhaseExecutionKind.SEMANTIC_LOOP
        WorkPayloadKeys.GATE_RUN -> PhaseExecutionKind.GATE_RUN
        WorkPayloadKeys.BOUNDED_EDGE -> PhaseExecutionKind.BOUNDED_EDGE
        WorkPayloadKeys.ATTEMPT -> PhaseExecutionKind.ATTEMPT
        else -> throw MalformedPayloadException()
      }
    }
    val executionTotal = execution?.optionalInt(WorkPayloadKeys.TOTAL)
    if (execution != null && executionTotal != null && executionKind != PhaseExecutionKind.BOUNDED_EDGE) throw MalformedPayloadException()
    if (execution != null && execution.requiredInt(WorkPayloadKeys.COUNT) < 1) throw MalformedPayloadException()
    if (executionTotal != null && executionTotal < 1) throw MalformedPayloadException()
    val reason = snapshot[WorkPayloadKeys.PAUSE_REASON]?.jsonObjectOrFail()
    return WorkItem(
      identity = WorkIdentity(snapshot.optionalString(WorkPayloadKeys.WORKFLOW_ID) ?: snapshot.optionalString(WorkPayloadKeys.INVOCATION_ID) ?: throw MalformedPayloadException(), repository),
      workflowId = snapshot.optionalString(WorkPayloadKeys.WORKFLOW_ID),
      invocationId = snapshot.optionalString(WorkPayloadKeys.INVOCATION_ID),
      repositoryRoot = root,
      branch = snapshot.optionalString(WorkPayloadKeys.BRANCH_CORRELATION),
      issueKey = snapshot.optionalString(WorkPayloadKeys.ISSUE_KEY),
      family = when (familyValue) {
        "feature-goal" -> WorkFamily.FEATURE_GOAL
        "feature-task-runtime" -> WorkFamily.FEATURE_TASK_RUNTIME
        "feature-verify" -> WorkFamily.FEATURE_VERIFY
        else -> null
      },
      scope = when (scopeValue) {
        "workflow" -> ExecutionScope.WORKFLOW
        "standalone_phase" -> ExecutionScope.STANDALONE_PHASE
        else -> null
      },
      phaseId = snapshot.optionalString(WorkPayloadKeys.PHASE_ID),
      activity = snapshot.optionalString(WorkPayloadKeys.CURRENT_ACTIVITY),
      lifecycle = enumValue(snapshot.string(WorkPayloadKeys.LIFECYCLE_STATE), Lifecycle.entries),
      freshness = enumValue(snapshot.string(WorkPayloadKeys.FRESHNESS), Freshness.entries),
      summary = snapshot.string(WorkPayloadKeys.SUMMARY),
      currentStepId = step.string(WorkPayloadKeys.ID),
      currentStepLabel = step.string(WorkPayloadKeys.LABEL),
      progressCompleted = progress?.optionalInt(WorkPayloadKeys.COMPLETED),
      progressTotal = progress?.optionalInt(WorkPayloadKeys.TOTAL),
      currentSubtaskId = subtask?.string(WorkPayloadKeys.ID),
      currentSubtaskStartedAt = subtask?.optionalInstant(WorkPayloadKeys.STARTED_AT),
      currentPhaseId = model?.optionalString(WorkPayloadKeys.PHASE_ID),
      phaseExecution = if (execution != null) PhaseExecution(execution.string(WorkPayloadKeys.PHASE_ID), executionKind ?: throw MalformedPayloadException(), execution.requiredInt(WorkPayloadKeys.COUNT), executionTotal) else null,
      currentModel = model?.string(WorkPayloadKeys.MODEL),
      currentEffort = model?.optionalString(WorkPayloadKeys.EFFORT),
      startedAt = snapshot.optionalInstant(WorkPayloadKeys.STARTED_AT),
      lastAgentActivityLabel = snapshot.optionalString(WorkPayloadKeys.LAST_AGENT_ACTIVITY_LABEL),
      lastAgentActivityAt = snapshot.optionalInstant(WorkPayloadKeys.LAST_AGENT_ACTIVITY_AT),
      activeDurationMs = snapshot.optionalLong(WorkPayloadKeys.ACTIVE_DURATION_MS),
      activeDurationAsOf = snapshot.optionalInstant(WorkPayloadKeys.ACTIVE_DURATION_AS_OF),
      subtaskActiveDurationMs = subtask?.optionalLong(WorkPayloadKeys.ACTIVE_DURATION_MS),
      subtaskActiveDurationAsOf = subtask?.optionalInstant(WorkPayloadKeys.ACTIVE_DURATION_AS_OF),
      pauseRequested = snapshot.optionalBoolean(WorkPayloadKeys.PAUSE_REQUESTED) ?: false,
      pauseReasonCode = reason?.optionalString(WorkPayloadKeys.CODE),
      pauseReason = reason?.optionalString(WorkPayloadKeys.LABEL),
      planningState = planning?.optionalString(WorkPayloadKeys.STATE),
      plannedSubtasks = planning?.optionalInt(WorkPayloadKeys.PLANNED_SUBTASK_COUNT),
      totalSubtasks = planning?.optionalInt(WorkPayloadKeys.TOTAL_SUBTASK_COUNT),
      planningWaveSubtaskIds = planning?.stringList(WorkPayloadKeys.PLANNING_WAVE_SUBTASK_IDS).orEmpty(),
      executionCount = execution?.optionalInt(WorkPayloadKeys.COUNT),
      executionTotal = execution?.optionalInt(WorkPayloadKeys.TOTAL),
      updatedAt = snapshot.instant(WorkPayloadKeys.UPDATED_AT),
      finishedAt = wrapper.optionalInstant(WorkPayloadKeys.FINISHED_AT),
      finishedAtSource = completionSource,
      executionId = snapshot.optionalString(WorkPayloadKeys.EXECUTION_ID),
      statusStoreId = snapshot.optionalString(WorkPayloadKeys.STATUS_STORE_ID),
      runSequence = snapshot.optionalString(WorkPayloadKeys.RUN_SEQUENCE),
      statusRevision = snapshot.optionalString(WorkPayloadKeys.STATUS_REVISION),
    )
  }
}

private fun decodeValidation(value: JsonObject): SubtaskValidation {
  val evidence = value.objectOrNull(WorkPayloadKeys.VALIDATION_EVIDENCE)?.let { wire ->
    if (wire.string(WorkPayloadKeys.CONTRACT_VERSION) != "0.2") throw MalformedPayloadException()
    wire.requiredObjectList(WorkPayloadKeys.RESULTS).map { ValidationCommand(it.string(WorkPayloadKeys.COMMAND), it.requiredInt(WorkPayloadKeys.EXIT_CODE)) }
  }.orEmpty()
  val result = value.objectOrNull(WorkPayloadKeys.VALIDATION_RESULT)?.let { wire ->
    ValidationResult(
      wire.string(WorkPayloadKeys.VALIDATION_STATUS),
      wire.requiredStringList(WorkPayloadKeys.CHECKS),
      wire.requiredInt(WorkPayloadKeys.GATE_RUN_COUNT),
      wire.requiredObjectList(WorkPayloadKeys.GATE_RUNS).map { gate ->
        ValidationGateRun(gate.requiredLong(WorkPayloadKeys.DURATION_MS), gate.string(WorkPayloadKeys.OUTCOME), gate.string(WorkPayloadKeys.CACHE_MODE), gate.requiredInt(WorkPayloadKeys.EXECUTED_WORK_UNITS), gate.stringList(WorkPayloadKeys.EXECUTED_CHECKS), gate.optionalString(WorkPayloadKeys.COMMAND), gate.optionalInt(WorkPayloadKeys.EXIT_CODE))
      },
    )
  }
  return SubtaskValidation(value.requiredInt(WorkPayloadKeys.SUBTASK_ID), value.optionalBoolean(WorkPayloadKeys.VALIDATION_PASSED), value.optionalString(WorkPayloadKeys.INTEGRITY_PROBLEM), evidence, result)
}

private fun String.jsonObjectOrFail() = try { Json.parseToJsonElement(this).jsonObject } catch (_: Exception) { throw MalformedPayloadException() }
private fun JsonElement?.jsonObjectOrFail() = try { this!!.jsonObject } catch (_: Exception) { throw MalformedPayloadException() }
private fun JsonObject.string(key: String) = optionalString(key)?.takeIf(String::isNotBlank) ?: throw MalformedPayloadException()
private fun JsonObject.optionalString(key: String): String? {
  val element = this[key] ?: return null
  if (element == JsonNull) return null
  val primitive = try { element.jsonPrimitive } catch (_: Exception) { throw MalformedPayloadException() }
  if (!primitive.isString) throw MalformedPayloadException()
  return primitive.content
}
private fun JsonObject.array(key: String) = try { this[key]!!.jsonArray } catch (_: Exception) { throw MalformedPayloadException() }
private fun JsonObject.instant(key: String) = optionalInstant(key) ?: throw MalformedPayloadException()
private fun JsonObject.optionalInstant(key: String) = optionalString(key)?.let { try { Instant.parse(it) } catch (_: Exception) { throw MalformedPayloadException() } }
private fun JsonObject.optionalInt(key: String) = this[key]?.let { if (it == JsonNull) null else try { it.jsonPrimitive.let { primitive -> if (primitive.isString) throw MalformedPayloadException(); primitive.int } } catch (_: Exception) { throw MalformedPayloadException() } }
private fun JsonObject.requiredInt(key: String) = optionalInt(key) ?: throw MalformedPayloadException()
private fun JsonObject.requiredLong(key: String) = optionalLong(key) ?: throw MalformedPayloadException()
private fun JsonObject.optionalLong(key: String) = this[key]?.let { if (it == JsonNull) null else try { it.jsonPrimitive.let { primitive -> if (primitive.isString) throw MalformedPayloadException(); primitive.long } } catch (_: Exception) { throw MalformedPayloadException() } }
private fun JsonObject.optionalBoolean(key: String) = this[key]?.let { if (it == JsonNull) null else try { it.jsonPrimitive.let { primitive -> if (primitive.isString) throw MalformedPayloadException(); primitive.boolean } } catch (_: Exception) { throw MalformedPayloadException() } }
private fun JsonObject.requiredBoolean(key: String) = optionalBoolean(key) ?: throw MalformedPayloadException()
private fun JsonObject.stringList(key: String) = this[key]?.let { try { it.jsonArray.map { entry -> entry.jsonPrimitive.takeIf(JsonPrimitive::isString)?.content ?: throw MalformedPayloadException() } } catch (_: Exception) { throw MalformedPayloadException() } } ?: emptyList()
private fun JsonObject.requiredStringList(key: String) = try { this[key]!!.jsonArray.map { it.jsonPrimitive.takeIf(JsonPrimitive::isString)?.content ?: throw MalformedPayloadException() } } catch (_: Exception) { throw MalformedPayloadException() }
private fun JsonObject.stringObjectList(key: String) = this[key]?.let { try { it.jsonArray.map { entry -> entry.jsonObject } } catch (_: Exception) { throw MalformedPayloadException() } } ?: emptyList()
private fun JsonObject.objectListOrNull(key: String) = this[key]?.let { if (it == JsonNull) null else try { it.jsonArray.map { entry -> entry.jsonObject } } catch (_: Exception) { throw MalformedPayloadException() } }
private fun JsonObject.requiredObjectList(key: String) = try { this[key]!!.jsonArray.map { it.jsonObject } } catch (_: Exception) { throw MalformedPayloadException() }
private fun JsonObject.objectOrNull(key: String) = this[key]?.let { if (it == JsonNull) null else try { it.jsonObject } catch (_: Exception) { throw MalformedPayloadException() } }
private fun JsonObject.arrayOrNull(key: String) = this[key]?.let { if (it == JsonNull) null else try { it.jsonArray } catch (_: Exception) { throw MalformedPayloadException() } }
private fun JsonObject.intMap(key: String): Map<String, Int> = objectOrNull(key)?.mapValues { (_, value) -> try { value.jsonPrimitive.let { primitive -> if (primitive.isString) throw MalformedPayloadException(); primitive.int } } catch (_: Exception) { throw MalformedPayloadException() } }.orEmpty()
private fun <T : Enum<T>> enumValue(value: String, values: List<T>): T = values.firstOrNull { it.name.equals(value, true) } ?: throw MalformedPayloadException()
