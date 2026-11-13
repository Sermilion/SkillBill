package skillbill.ports.idestatus.model

import java.time.Instant

data class StandalonePlanProgress(
  val report: StandalonePlanReport?,
  val stages: List<StandalonePlanStage>,
  val plannedSubtaskCount: Int,
  val totalSubtaskCount: Int,
  val currentPlanningSubtaskId: Int?,
  val planningWaveSubtaskIds: List<Int>,
  val blockedSubtaskId: Int?,
  val blockedReason: String?,
  val units: List<StandalonePlanUnit>,
  val stalledSince: Instant?,
  val planningWaveNumber: Int? = null,
  val planningWaveCapacity: Int? = null,
  val planningWaveHistoryComplete: Boolean = false,
  val blockedSubtaskIds: List<Int> = emptyList(),
  val waves: List<StandalonePlanWave> = emptyList(),
  val routingClassification: String? = null,
  val routingFailureCode: String? = null,
  val routingRecoveryGuidance: String? = null,
)

data class StandalonePlanStage(
  val phaseId: String,
  val firstStartedAt: Instant?,
  val finishedAt: Instant?,
  val briefingText: String?,
  val status: String? = null,
)

enum class StandalonePlanUnitState(val wireValue: String) {
  BLOCKED("blocked"),
  SAVED("saved"),
  WRITING("writing"),
  QUEUED("queued"),
}

data class StandalonePlanUnit(
  val subtaskId: Int,
  val name: String,
  val specPath: String,
  val dependsOn: List<Int>,
  val state: StandalonePlanUnitState,
  val startedAt: Instant?,
  val finishedAt: Instant?,
  val blockedReason: String?,
  val latestOutput: String?,
  val latestOutputAt: Instant? = null,
)

data class StandalonePlanWave(val number: Int?, val subtaskIds: List<Int>, val startedAt: Instant?)
