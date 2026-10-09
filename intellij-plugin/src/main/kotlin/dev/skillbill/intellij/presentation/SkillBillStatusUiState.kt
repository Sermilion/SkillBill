package dev.skillbill.intellij.presentation

import dev.skillbill.intellij.domain.CurrentPhaseExecution
import dev.skillbill.intellij.domain.CurrentPhaseModel
import dev.skillbill.intellij.domain.GoalPlanningInfo
import dev.skillbill.intellij.domain.PauseReason
import java.time.Duration
import java.time.Instant


sealed class SkillBillStatusUiState {
    abstract val headline: String
    abstract val detail: String?
    abstract val goalElapsed: Duration?
    abstract val subtaskElapsed: Duration?
    abstract val progressCompleted: Int?
    abstract val progressTotal: Int?
    abstract val accessibilityText: String
    open val issueKey: String? get() = null
    open val workflowId: String? get() = null
    open val stepLabel: String? get() = null
    open val startedAt: Instant? get() = null
    open val subtaskStartedAt: Instant? get() = null
    open val lastUpdated: Instant? get() = null
    open val problemSummary: String? get() = null

    
    open val workflowFamily: String? get() = null

    
    open val pauseRequested: Boolean? get() = null

    
    open val planning: GoalPlanningInfo? get() = null

    
    open val currentModel: CurrentPhaseModel? get() = null

    open val pauseReason: PauseReason? get() = null

    
    open val currentPhaseExecution: CurrentPhaseExecution? get() = null
    open val executionScope: String? get() = null
    open val phaseId: String? get() = null
    open val currentActivity: String? get() = null

    
    open val stale: Boolean get() = this is Stale

    data class Idle(
        override val headline: String = "Skill Bill: idle",
        override val detail: String? = null,
        override val goalElapsed: Duration? = null,
        override val subtaskElapsed: Duration? = null,
        override val progressCompleted: Int? = null,
        override val progressTotal: Int? = null,
        override val lastUpdated: Instant? = null,
        override val executionScope: String? = null,
        override val phaseId: String? = null,
        override val currentActivity: String? = null,
        override val stale: Boolean = false,
    ) : SkillBillStatusUiState() {
        override val accessibilityText: String = headline
    }

    data class Done(
        override val headline: String,
        override val detail: String? = null,
        override val goalElapsed: Duration? = null,
        override val subtaskElapsed: Duration? = null,
        override val progressCompleted: Int? = null,
        override val progressTotal: Int? = null,
        override val issueKey: String? = null,
        override val startedAt: Instant? = null,
        override val lastUpdated: Instant? = null,
        override val executionScope: String? = null,
        override val phaseId: String? = null,
        override val currentActivity: String? = null,
        override val stale: Boolean = false,
    ) : SkillBillStatusUiState() {
        override val accessibilityText: String = "$headline (done)"
    }

    data class Active(
        override val headline: String,
        override val detail: String?,
        override val goalElapsed: Duration?,
        override val subtaskElapsed: Duration?,
        override val progressCompleted: Int?,
        override val progressTotal: Int?,
        override val issueKey: String?,
        override val workflowId: String?,
        override val stepLabel: String,
        override val startedAt: Instant?,
        override val subtaskStartedAt: Instant?,
        override val lastUpdated: Instant?,
        override val planning: GoalPlanningInfo? = null,
        override val workflowFamily: String? = null,
        override val pauseRequested: Boolean? = null,
        override val currentModel: CurrentPhaseModel? = null,
        override val currentPhaseExecution: CurrentPhaseExecution? = null,
        override val problemSummary: String? = null,
        
        val activeDurationMs: Long? = null,
        val activeDurationAsOf: Instant? = null,
        val subtaskActiveDurationMs: Long? = null,
        val subtaskActiveDurationAsOf: Instant? = null,
        val lastAgentActivityAt: Instant? = null,
        val lastAgentActivityLabel: String? = null,
        override val executionScope: String? = null,
        override val phaseId: String? = null,
        override val currentActivity: String? = null,
    ) : SkillBillStatusUiState() {
        override val accessibilityText: String =
            buildString {
                append(headline)
                goalElapsed?.let { append(", goal elapsed ").append(formatDuration(it)) }
                subtaskElapsed?.let { append(", subtask elapsed ").append(formatDuration(it)) }
            }
    }

    data class Paused(
        override val headline: String,
        override val detail: String?,
        override val goalElapsed: Duration?,
        override val subtaskElapsed: Duration?,
        override val progressCompleted: Int?,
        override val progressTotal: Int?,
        override val issueKey: String?,
        override val workflowId: String?,
        override val stepLabel: String,
        override val startedAt: Instant?,
        override val subtaskStartedAt: Instant?,
        override val lastUpdated: Instant?,
        override val planning: GoalPlanningInfo? = null,
        override val workflowFamily: String? = null,
        override val pauseRequested: Boolean? = null,
        override val currentModel: CurrentPhaseModel? = null,
        override val currentPhaseExecution: CurrentPhaseExecution? = null,
        override val pauseReason: PauseReason? = null,
        override val problemSummary: String? = null,
        val lastAgentActivityAt: Instant? = null,
        val lastAgentActivityLabel: String? = null,
        override val executionScope: String? = null,
        override val phaseId: String? = null,
        override val currentActivity: String? = null,
    ) : SkillBillStatusUiState() {
        override val accessibilityText: String = "$headline (paused)"
    }

    data class Stale(
        override val headline: String,
        override val detail: String?,
        override val goalElapsed: Duration?,
        override val subtaskElapsed: Duration?,
        override val progressCompleted: Int?,
        override val progressTotal: Int?,
        override val issueKey: String? = null,
        override val workflowId: String? = null,
        override val stepLabel: String? = null,
        override val startedAt: Instant? = null,
        override val subtaskStartedAt: Instant? = null,
        override val lastUpdated: Instant? = null,
        override val problemSummary: String? = "Cached status is stale",
        override val planning: GoalPlanningInfo? = null,
        override val currentModel: CurrentPhaseModel? = null,
        override val currentPhaseExecution: CurrentPhaseExecution? = null,
        val lastAgentActivityAt: Instant? = null,
        val lastAgentActivityLabel: String? = null,
        override val executionScope: String? = null,
        override val phaseId: String? = null,
        override val currentActivity: String? = null,
    ) : SkillBillStatusUiState() {
        override val accessibilityText: String = "$headline (stale)"
    }

    data class Blocked(
        override val headline: String,
        override val detail: String?,
        override val goalElapsed: Duration?,
        override val subtaskElapsed: Duration?,
        override val progressCompleted: Int? = null,
        override val progressTotal: Int? = null,
        override val issueKey: String? = null,
        override val workflowId: String? = null,
        override val stepLabel: String? = null,
        override val startedAt: Instant? = null,
        override val subtaskStartedAt: Instant? = null,
        override val lastUpdated: Instant? = null,
        override val problemSummary: String? = null,
        override val stale: Boolean = false,
        override val currentModel: CurrentPhaseModel? = null,
        override val currentPhaseExecution: CurrentPhaseExecution? = null,
        override val pauseReason: PauseReason? = null,
        override val executionScope: String? = null,
        override val phaseId: String? = null,
        override val currentActivity: String? = null,
    ) : SkillBillStatusUiState() {
        override val accessibilityText: String = "$headline (blocked)"
    }

    data class Failed(
        override val headline: String,
        override val detail: String?,
        override val goalElapsed: Duration?,
        override val subtaskElapsed: Duration?,
        override val progressCompleted: Int? = null,
        override val progressTotal: Int? = null,
        override val issueKey: String? = null,
        override val workflowId: String? = null,
        override val stepLabel: String? = null,
        override val startedAt: Instant? = null,
        override val subtaskStartedAt: Instant? = null,
        override val lastUpdated: Instant? = null,
        override val problemSummary: String? = null,
        override val stale: Boolean = false,
        override val currentModel: CurrentPhaseModel? = null,
        override val currentPhaseExecution: CurrentPhaseExecution? = null,
        override val executionScope: String? = null,
        override val phaseId: String? = null,
        override val currentActivity: String? = null,
    ) : SkillBillStatusUiState() {
        override val accessibilityText: String = "$headline (failed)"
    }

    data class Unavailable(
        override val headline: String,
        override val detail: String?,
        override val goalElapsed: Duration? = null,
        override val subtaskElapsed: Duration? = null,
        override val progressCompleted: Int? = null,
        override val progressTotal: Int? = null,
        val reasonCode: String,
        override val lastUpdated: Instant? = null,
        override val problemSummary: String? = null,
    ) : SkillBillStatusUiState() {
        override val accessibilityText: String = "$headline (unavailable)"
    }

    data class Incompatible(
        override val headline: String,
        override val detail: String?,
        override val goalElapsed: Duration? = null,
        override val subtaskElapsed: Duration? = null,
        override val progressCompleted: Int? = null,
        override val progressTotal: Int? = null,
        val foundContractVersion: String?,
        override val lastUpdated: Instant? = null,
        override val problemSummary: String? = null,
    ) : SkillBillStatusUiState() {
        override val accessibilityText: String = "$headline (incompatible)"
    }
}

internal fun formatDuration(duration: Duration): String {
    val totalSeconds = duration.seconds.coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "%dh %02dm".format(hours, minutes)
        minutes > 0 -> "%dm %02ds".format(minutes, seconds)
        else -> "%ds".format(seconds)
    }
}
