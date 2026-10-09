package dev.skillbill.intellij.infrastructure.cli

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import dev.skillbill.intellij.domain.ACTIVE_DURATION_AS_OF_WIRE_KEY
import dev.skillbill.intellij.domain.AGENT_ACTIVITY_LABELS
import dev.skillbill.intellij.domain.BRANCH_CORRELATION_WIRE_KEY
import dev.skillbill.intellij.domain.LAST_AGENT_ACTIVITY_AT_WIRE_KEY
import dev.skillbill.intellij.domain.LAST_AGENT_ACTIVITY_LABEL_WIRE_KEY
import dev.skillbill.intellij.domain.ACTIVE_DURATION_MS_WIRE_KEY
import dev.skillbill.intellij.domain.CURRENT_ACTIVITY_MAX_CODE_POINTS
import dev.skillbill.intellij.domain.CURRENT_ACTIVITY_WIRE_KEY
import dev.skillbill.intellij.domain.CURRENT_MODEL_WIRE_KEY
import dev.skillbill.intellij.domain.CURRENT_PHASE_EXECUTION_KINDS
import dev.skillbill.intellij.domain.CURRENT_PHASE_EXECUTION_WIRE_KEY
import dev.skillbill.intellij.domain.CurrentPhaseExecution
import dev.skillbill.intellij.domain.CurrentPhaseModel
import dev.skillbill.intellij.domain.EFFORT_MAX_LENGTH
import dev.skillbill.intellij.domain.EXECUTION_ID_WIRE_KEY
import dev.skillbill.intellij.domain.EXECUTION_SCOPE_WIRE_KEY
import dev.skillbill.intellij.domain.MODEL_MAX_LENGTH
import dev.skillbill.intellij.domain.PHASE_ID_MAX_LENGTH
import dev.skillbill.intellij.domain.PHASE_ID_WIRE_KEY
import dev.skillbill.intellij.domain.GoalPlanningInfo
import dev.skillbill.intellij.domain.IDE_STATUS_CONTRACT_VERSION
import dev.skillbill.intellij.domain.INVOCATION_ID_WIRE_KEY
import dev.skillbill.intellij.domain.NO_MATCHING_WORK_REASON_CODE
import dev.skillbill.intellij.domain.PAUSED_AT_WIRE_KEY
import dev.skillbill.intellij.domain.PAUSE_REASON_CODES
import dev.skillbill.intellij.domain.PAUSE_REASON_LABEL_MAX_LENGTH
import dev.skillbill.intellij.domain.PAUSE_REASON_WIRE_KEY
import dev.skillbill.intellij.domain.PauseReason
import dev.skillbill.intellij.domain.StatusExecutionMetadata
import dev.skillbill.intellij.domain.PAUSE_REQUESTED_WIRE_KEY
import dev.skillbill.intellij.domain.SkillBillStatusOutcome
import dev.skillbill.intellij.domain.StatusDiagnostic
import dev.skillbill.intellij.domain.UnavailableReason
import dev.skillbill.intellij.domain.RUN_SEQUENCE_WIRE_KEY
import dev.skillbill.intellij.domain.STATUS_REVISION_WIRE_KEY
import dev.skillbill.intellij.domain.STATUS_STORE_ID_WIRE_KEY
import dev.skillbill.intellij.infrastructure.AbsolutePathGuard
import java.time.Instant


object IdeStatusJsonMapper {
    fun map(
        stdout: String,
        observedAt: Instant,
        exitCode: Int?,
    ): SkillBillStatusOutcome {
        if (exitCode != null && exitCode != 0) {
            return SkillBillStatusOutcome.Unavailable(
                observedAt = observedAt,
                summary = "Skill Bill status command failed",
                reasonCode = UnavailableReason.PROCESS_FAILURE,
                diagnostic = StatusDiagnostic(
                    exitCode = exitCode,
                    reasonCode = "non_zero_exit",
                ),
            )
        }
        val root = parseObject(stdout) ?: return malformed(observedAt, "malformed_json")
        val contractVersion = root.getAsString("contract_version")
        if (contractVersion == null) {
            return SkillBillStatusOutcome.Incompatible(
                observedAt = observedAt,
                summary = "IDE status contract version missing",
                foundContractVersion = null,
                diagnostic = StatusDiagnostic(
                    contractVersionMismatch = true,
                    reasonCode = "missing_contract_version",
                ),
            )
        }
        if (contractVersion != IDE_STATUS_CONTRACT_VERSION) {
            return SkillBillStatusOutcome.Incompatible(
                observedAt = observedAt,
                summary = "IDE status contract version incompatible",
                foundContractVersion = contractVersion,
                diagnostic = StatusDiagnostic(
                    contractVersionMismatch = true,
                    foundContractVersion = contractVersion,
                    reasonCode = "contract_version_mismatch",
                ),
            )
        }

        val execution = parseExecution(root)
        if (execution.invalid) {
            return SkillBillStatusOutcome.Incompatible(
                observedAt = observedAt,
                summary = "IDE status execution identity is malformed",
                foundContractVersion = contractVersion,
                diagnostic = StatusDiagnostic(reasonCode = "invalid_execution_identity"),
            )
        }

        val problem = root.getAsJsonObjectOrNull("problem")
        val problemCode = problem?.getAsString("code")
        if (problemCode == "schema_incompatible") {
            return SkillBillStatusOutcome.Incompatible(
                observedAt = observedAt,
                summary = safeSummary(problem.getAsString("message"), "Schema incompatible"),
                foundContractVersion = problem.getAsJsonObjectOrNull("details")
                    ?.getAsString("found_contract_version"),
                diagnostic = StatusDiagnostic(
                    contractVersionMismatch = true,
                    reasonCode = problemCode,
                ),
            )
        }

        val freshness = root.getAsString("freshness")
        val lifecycle = root.getAsString("lifecycle_state") ?: "idle"
        val summary = safeSummary(root.getAsString("summary"), "Skill Bill status")
        val repositoryIdentity = root.getAsString("repository_identity")
        val issueKey = root.getAsString("issue_key")
        val workflowId = root.getAsString("workflow_id")
        val workflowFamily = root.getAsString("workflow_family")
        val step = root.getAsJsonObjectOrNull("current_step")
        val stepId = step?.getAsString("id")
        val stepLabel = step?.getAsString("label")
        val progress = root.getAsJsonObjectOrNull("progress")
        val progressCompleted = progress?.getAsInt("completed")
        val progressTotal = progress?.getAsInt("total")
        val startedAt = root.getAsInstant("started_at")
        val subtask = root.getAsJsonObjectOrNull("current_subtask")
        val subtaskId = subtask?.getAsString("id")
        val subtaskStartedAt = subtask?.getAsInstant("started_at")
        val subtaskActiveDurationMs = subtask?.getAsNonNegativeLong(ACTIVE_DURATION_MS_WIRE_KEY)
        val subtaskActiveDurationAsOf = subtask?.getAsInstant(ACTIVE_DURATION_AS_OF_WIRE_KEY)
        val updatedAt = root.getAsInstant("updated_at")
        val planning = root.parsePlanning()
        val currentModel = root.parseCurrentModel()
        val currentPhaseExecution = root.parseCurrentPhaseExecution()

        val pauseRequested = root.getAsBoolean(PAUSE_REQUESTED_WIRE_KEY)
        val pausedAt = root.getAsInstant(PAUSED_AT_WIRE_KEY)
        val pauseReason = root.parsePauseReason()
        val activeDurationMs = root.getAsNonNegativeLong(ACTIVE_DURATION_MS_WIRE_KEY)
        val activeDurationAsOf = root.getAsInstant(ACTIVE_DURATION_AS_OF_WIRE_KEY)
        val agentActivity = root.parseAgentActivity()



        if (problemCode == NO_MATCHING_WORK_REASON_CODE) {
            return SkillBillStatusOutcome.Idle(
                observedAt = observedAt,
                summary = safeSummary(problem?.getAsString("message"), summary),
                repositoryIdentity = repositoryIdentity,
                diagnostic = StatusDiagnostic(reasonCode = NO_MATCHING_WORK_REASON_CODE),
            )
        }

        if (problemCode != null) {
            val unavailable = when (problemCode) {
                "missing_repository_identity" -> UnavailableReason.MISSING_REPOSITORY
                "absent_database" -> UnavailableReason.ABSENT_DATABASE
                "no_matching_work" -> UnavailableReason.NO_MATCHING_WORK
                "invalid_repository_input" -> UnavailableReason.INVALID_REPOSITORY_INPUT
                "incompatible_record" -> UnavailableReason.MISCONFIGURED
                else -> UnavailableReason.MISCONFIGURED
            }
            return SkillBillStatusOutcome.Unavailable(
                observedAt = observedAt,
                summary = safeSummary(problem?.getAsString("message"), summary),
                reasonCode = unavailable,
                diagnostic = StatusDiagnostic(reasonCode = problemCode),
            )
        }

        val isStale = freshness == "stale"




        if (isStale && (lifecycle == "active" || lifecycle == "paused")) {
            return SkillBillStatusOutcome.Stale(
                observedAt = observedAt,
                summary = summary,
                repositoryIdentity = repositoryIdentity,
                issueKey = issueKey,
                currentStepId = stepId,
                currentStepLabel = stepLabel,
                progressCompleted = progressCompleted,
                progressTotal = progressTotal,
                startedAt = startedAt,
                currentSubtaskId = subtaskId,
                subtaskStartedAt = subtaskStartedAt,
                updatedAt = updatedAt,
                fromCache = false,
                planning = planning,
                activeDurationMs = activeDurationMs,
                activeDurationAsOf = activeDurationAsOf,
                subtaskActiveDurationMs = subtaskActiveDurationMs,
                subtaskActiveDurationAsOf = subtaskActiveDurationAsOf,
                currentModel = currentModel,
                currentPhaseExecution = currentPhaseExecution,
                lastAgentActivityAt = agentActivity?.first,
                lastAgentActivityLabel = agentActivity?.second,
                execution = execution.metadata,
            )
        }

        return when (lifecycle) {
            "active", "paused" -> {
                if (repositoryIdentity.isNullOrBlank() || stepId.isNullOrBlank() || stepLabel.isNullOrBlank() || updatedAt == null) {
                    malformed(observedAt, "incomplete_active_payload")
                } else if (lifecycle == "paused") {
                    SkillBillStatusOutcome.Paused(
                        observedAt = observedAt,
                        summary = summary,
                        repositoryIdentity = repositoryIdentity,
                        issueKey = issueKey,
                        workflowId = workflowId,
                        workflowFamily = workflowFamily,
                        currentStepId = stepId,
                        currentStepLabel = stepLabel,
                        progressCompleted = progressCompleted,
                        progressTotal = progressTotal,
                        startedAt = startedAt,
                        currentSubtaskId = subtaskId,
                        subtaskStartedAt = subtaskStartedAt,
                        updatedAt = updatedAt,
                        planning = planning,
                        pauseRequested = pauseRequested,
                        pausedAt = pausedAt,
                        activeDurationMs = activeDurationMs,
                        activeDurationAsOf = activeDurationAsOf,
                        subtaskActiveDurationMs = subtaskActiveDurationMs,
                        subtaskActiveDurationAsOf = subtaskActiveDurationAsOf,
                        currentModel = currentModel,
                        currentPhaseExecution = currentPhaseExecution,
                        pauseReason = pauseReason,
                        lastAgentActivityAt = agentActivity?.first,
                        lastAgentActivityLabel = agentActivity?.second,
                        execution = execution.metadata,
                    )
                } else {
                    SkillBillStatusOutcome.Active(
                        observedAt = observedAt,
                        summary = summary,
                        repositoryIdentity = repositoryIdentity,
                        issueKey = issueKey,
                        workflowId = workflowId,
                        workflowFamily = workflowFamily,
                        currentStepId = stepId,
                        currentStepLabel = stepLabel,
                        progressCompleted = progressCompleted,
                        progressTotal = progressTotal,
                        startedAt = startedAt,
                        currentSubtaskId = subtaskId,
                        subtaskStartedAt = subtaskStartedAt,
                        updatedAt = updatedAt,
                        planning = planning,
                        pauseRequested = pauseRequested,
                        pausedAt = pausedAt,
                        activeDurationMs = activeDurationMs,
                        activeDurationAsOf = activeDurationAsOf,
                        subtaskActiveDurationMs = subtaskActiveDurationMs,
                        subtaskActiveDurationAsOf = subtaskActiveDurationAsOf,
                        currentModel = currentModel,
                        currentPhaseExecution = currentPhaseExecution,
                        lastAgentActivityAt = agentActivity?.first,
                        lastAgentActivityLabel = agentActivity?.second,
                        execution = execution.metadata,
                    )
                }
            }

            "blocked" -> SkillBillStatusOutcome.Blocked(
                observedAt = observedAt,
                summary = summary,
                repositoryIdentity = repositoryIdentity,
                issueKey = issueKey,
                currentStepId = stepId,
                currentStepLabel = stepLabel,
                startedAt = startedAt,
                currentSubtaskId = subtaskId,
                subtaskStartedAt = subtaskStartedAt,
                updatedAt = updatedAt,
                stale = isStale,
                activeDurationMs = activeDurationMs,
                activeDurationAsOf = activeDurationAsOf,
                subtaskActiveDurationMs = subtaskActiveDurationMs,
                subtaskActiveDurationAsOf = subtaskActiveDurationAsOf,
                currentModel = currentModel,
                currentPhaseExecution = currentPhaseExecution,
                pauseReason = pauseReason,
                execution = execution.metadata,
            )

            "failed" -> SkillBillStatusOutcome.Failed(
                observedAt = observedAt,
                summary = summary,
                repositoryIdentity = repositoryIdentity,
                issueKey = issueKey,
                currentStepId = stepId,
                currentStepLabel = stepLabel,
                startedAt = startedAt,
                currentSubtaskId = subtaskId,
                subtaskStartedAt = subtaskStartedAt,
                updatedAt = updatedAt,
                stale = isStale,
                activeDurationMs = activeDurationMs,
                activeDurationAsOf = activeDurationAsOf,
                subtaskActiveDurationMs = subtaskActiveDurationMs,
                subtaskActiveDurationAsOf = subtaskActiveDurationAsOf,
                currentModel = currentModel,
                currentPhaseExecution = currentPhaseExecution,
                execution = execution.metadata,
            )

            "idle" -> SkillBillStatusOutcome.Idle(
                observedAt = observedAt,
                summary = summary,
                repositoryIdentity = repositoryIdentity,
                stale = isStale,
            )

            "terminal" -> SkillBillStatusOutcome.Done(
                observedAt = observedAt,
                summary = summary,
                repositoryIdentity = repositoryIdentity,
                issueKey = issueKey,
                progressCompleted = progressCompleted,
                progressTotal = progressTotal,
                startedAt = startedAt,
                updatedAt = updatedAt,
                stale = isStale,
                activeDurationMs = activeDurationMs,
                activeDurationAsOf = activeDurationAsOf,
                execution = execution.metadata,
            )

            else -> SkillBillStatusOutcome.Unavailable(
                observedAt = observedAt,
                summary = "Unknown lifecycle state",
                reasonCode = UnavailableReason.MALFORMED_OUTPUT,
                diagnostic = StatusDiagnostic(reasonCode = "unknown_lifecycle"),
            )
        }
    }

    private fun malformed(observedAt: Instant, code: String): SkillBillStatusOutcome =
        SkillBillStatusOutcome.Unavailable(
            observedAt = observedAt,
            summary = "Malformed Skill Bill status output",
            reasonCode = UnavailableReason.MALFORMED_OUTPUT,
            diagnostic = StatusDiagnostic(reasonCode = code),
        )

    private fun parseObject(stdout: String): JsonObject? =
        try {
            val element = JsonParser.parseString(stdout.trim())
            if (element.isJsonObject) element.asJsonObject else null
        } catch (_: JsonSyntaxException) {
            null
        } catch (_: IllegalStateException) {
            null
        }

    private fun safeSummary(raw: String?, fallback: String): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return fallback

        return AbsolutePathGuard.redact(value).take(512)
    }

    
    private fun JsonObject.parsePlanning(): GoalPlanningInfo? {
        val planning = getAsJsonObjectOrNull("planning") ?: return null
        val state = planning.getAsString("state")?.takeUnless { it.isBlank() } ?: return null
        val sharedPreplanPrepared = planning.getAsBoolean("shared_preplan_prepared") ?: return null
        val planned = planning.getAsStrictInt("planned_subtask_count")?.takeIf { it >= 0 } ?: return null
        val total = planning.getAsStrictInt("total_subtask_count")?.takeIf { it >= 0 } ?: return null
        return GoalPlanningInfo(
            state = state,
            sharedPreplanPrepared = sharedPreplanPrepared,
            plannedSubtaskCount = planned,
            totalSubtaskCount = total,
            currentPlanningSubtaskId = planning.getAsString("current_planning_subtask_id")
                ?.takeUnless { it.isBlank() },
            reason = planning.getAsString("reason")?.let { safeSummary(it, "") }?.takeUnless { it.isEmpty() },
        )
    }

    
    private fun JsonObject.parseCurrentModel(): CurrentPhaseModel? {
        val currentModel = getAsJsonObjectOrNull(CURRENT_MODEL_WIRE_KEY) ?: return null
        val model = currentModel.boundedString("model", MODEL_MAX_LENGTH) ?: return null
        return CurrentPhaseModel(
            model = model,
            effort = currentModel.boundedString("effort", EFFORT_MAX_LENGTH),
            phaseId = currentModel.boundedString("phase_id", PHASE_ID_MAX_LENGTH),
        )
    }

    
    private fun JsonObject.parseCurrentPhaseExecution(): CurrentPhaseExecution? {
        val execution = getAsJsonObjectOrNull(CURRENT_PHASE_EXECUTION_WIRE_KEY) ?: return null
        val phaseId = execution.boundedString("phase_id", PHASE_ID_MAX_LENGTH) ?: return null
        val kind = execution.getAsStringPrimitive("kind")?.takeIf { it in CURRENT_PHASE_EXECUTION_KINDS }
            ?: return null
        val count = execution.getAsStrictInt("count")?.takeIf { it >= 1 } ?: return null
        val totalElement = execution.get("total")
        val total = when {
            totalElement == null -> null

            totalElement.isJsonNull -> return null
            kind != "bounded_edge" -> return null
            else -> execution.getAsStrictInt("total")?.takeIf { it >= 1 } ?: return null
        }
        return CurrentPhaseExecution(
            phaseId = phaseId,
            kind = kind,
            count = count,
            total = total,
        )
    }

    private fun JsonObject.parsePauseReason(): PauseReason? {
        val reason = getAsJsonObjectOrNull(PAUSE_REASON_WIRE_KEY) ?: return null
        val code = reason.getAsStringPrimitive("code")?.takeIf { it in PAUSE_REASON_CODES } ?: return null
        return PauseReason(
            code = code,
            label = reason.boundedString("label", PAUSE_REASON_LABEL_MAX_LENGTH),
        )
    }

    private fun JsonObject.boundedString(key: String, maxLength: Int): String? =
        getAsStringPrimitive(key)?.trim()?.takeIf { it.isNotBlank() && it.length <= maxLength }

    private fun JsonObject.getAsString(key: String): String? =
        get(key)?.takeUnless { it.isJsonNull }?.asStringOrNull()

    
    private fun JsonObject.getAsStringPrimitive(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.getAsInt(key: String): Int? =
        get(key)?.takeUnless { it.isJsonNull }?.let {
            runCatching { it.asInt }.getOrNull()
        }

    
    private fun JsonObject.getAsStrictInt(key: String): Int? {
        val element = get(key) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
        return runCatching { element.asBigDecimal.intValueExact() }.getOrNull()
    }

    
    private fun JsonObject.getAsBoolean(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    
    private fun JsonObject.getAsNonNegativeLong(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.let { runCatching { it.asLong }.getOrNull() }
            ?.takeIf { it >= 0 }

    private fun JsonObject.getAsInstant(key: String): Instant? {
        val raw = getAsString(key) ?: return null
        return runCatching { Instant.parse(raw) }.getOrNull()
    }

    private fun JsonObject.getAsJsonObjectOrNull(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonElement.asStringOrNull(): String? =
        runCatching { asString }.getOrNull()

    private fun JsonObject.parseAgentActivity(): Pair<Instant, String>? {
        val at = getAsInstant(LAST_AGENT_ACTIVITY_AT_WIRE_KEY)
        val label = getAsString(LAST_AGENT_ACTIVITY_LABEL_WIRE_KEY)
        if (at == null && label == null) return null
        if (at == null || label == null || label !in AGENT_ACTIVITY_LABELS) {
            return null
        }
        return at to label
    }

    private fun parseExecution(root: JsonObject): ParsedExecution {
        val keys = listOf(
            EXECUTION_SCOPE_WIRE_KEY,
            EXECUTION_ID_WIRE_KEY,
            STATUS_STORE_ID_WIRE_KEY,
            RUN_SEQUENCE_WIRE_KEY,
            STATUS_REVISION_WIRE_KEY,
            INVOCATION_ID_WIRE_KEY,
            PHASE_ID_WIRE_KEY,
        )
        if (keys.none { root.has(it) } && !root.has(CURRENT_ACTIVITY_WIRE_KEY)) return ParsedExecution(null, false)
        val scope = root.getAsStringPrimitive(EXECUTION_SCOPE_WIRE_KEY)
        val executionId = root.getAsStringPrimitive(EXECUTION_ID_WIRE_KEY)
        val storeId = root.getAsStringPrimitive(STATUS_STORE_ID_WIRE_KEY)
        val branchCorrelation = root.getAsStringPrimitive(BRANCH_CORRELATION_WIRE_KEY)
        val sequence = root.getAsStringPrimitive(RUN_SEQUENCE_WIRE_KEY)
        val revision = root.getAsStringPrimitive(STATUS_REVISION_WIRE_KEY)
        val invocationId = root.getAsStringPrimitive(INVOCATION_ID_WIRE_KEY)
        val phaseId = root.boundedString(PHASE_ID_WIRE_KEY, PHASE_ID_MAX_LENGTH)
        val invocationFieldValid = !root.has(INVOCATION_ID_WIRE_KEY) || invocationId != null
        val phaseFieldValid = !root.has(PHASE_ID_WIRE_KEY) || phaseId != null
        val validScope = scope == "workflow" || scope == "standalone_phase"
        val validNumbers = sequence?.matches(Regex("[1-9][0-9]*")) == true &&
            revision?.matches(Regex("[1-9][0-9]*")) == true
        val validScopeIdentity = when (scope) {
            "workflow" -> invocationId == null && phaseId == null
            "standalone_phase" -> !invocationId.isNullOrBlank() && !phaseId.isNullOrBlank()
            else -> false
        }
        val activity = root.getAsStringPrimitive(CURRENT_ACTIVITY_WIRE_KEY)
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.codePointCount(0, it.length) <= CURRENT_ACTIVITY_MAX_CODE_POINTS }
            ?.takeIf { value -> value.none(Char::isISOControl) }
        val activityValid = !root.has(CURRENT_ACTIVITY_WIRE_KEY) || activity != null
        if (!validScope || executionId.isNullOrBlank() || storeId.isNullOrBlank() || !validNumbers ||
            !invocationFieldValid || !phaseFieldValid ||
            !validScopeIdentity || !activityValid
        ) {
            return ParsedExecution(null, true)
        }
        return ParsedExecution(
            metadata = StatusExecutionMetadata(
                executionScope = scope,
                executionId = executionId,
                statusStoreId = storeId,
                branchCorrelation = branchCorrelation,
                runSequence = sequence,
                statusRevision = revision,
                invocationId = invocationId,
                phaseId = phaseId,
                currentActivity = activity,
            ),
            invalid = false,
        )
    }

    private data class ParsedExecution(
        val metadata: StatusExecutionMetadata?,
        val invalid: Boolean,
    )
}
