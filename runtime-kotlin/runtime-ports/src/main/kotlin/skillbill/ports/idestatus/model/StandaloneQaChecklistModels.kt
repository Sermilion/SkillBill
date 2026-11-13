package skillbill.ports.idestatus.model

import java.time.Instant

enum class StandaloneQaStepStatus(val wireValue: String) {
  PENDING("pending"),
  RUNNING("running"),
  PASSED("passed"),
  FAILED("failed"),
  SKIPPED("skipped"),
  ;

  val isSettled: Boolean get() = this == PASSED || this == FAILED || this == SKIPPED

  companion object {
    fun fromWire(value: String?): StandaloneQaStepStatus? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class StandaloneQaTargetState(val wireValue: String) {
  PENDING("pending"),
  RUNNING("running"),
  READY("ready"),
  SKIPPED("skipped"),
  ;

  companion object {
    fun fromWire(value: String?): StandaloneQaTargetState? = entries.firstOrNull { it.wireValue == value }
  }
}

data class StandaloneQaChecklistTarget(
  val name: String,
  val state: StandaloneQaTargetState,
  val detail: String? = null,
  val updatedAt: Instant? = null,
)

data class StandaloneQaChecklistStep(
  val text: String,
  val status: StandaloneQaStepStatus = StandaloneQaStepStatus.PENDING,
  val note: String? = null,
  val findingId: String? = null,
  val evidence: List<String> = emptyList(),
  val updatedAt: Instant? = null,
)

data class StandaloneQaChecklistCase(
  val id: String,
  val title: String,
  val steps: List<StandaloneQaChecklistStep>,
)

data class StandaloneQaChecklistFinding(
  val id: String,
  val title: String,
  val recordedAt: Instant,
)

data class StandaloneQaChecklist(
  val invocationDirectory: String? = null,
  val reportPath: String? = null,
  val testedCommit: String? = null,
  val criteriaSource: String? = null,
  val targets: List<StandaloneQaChecklistTarget> = emptyList(),
  val planRegisteredAt: Instant? = null,
  val cases: List<StandaloneQaChecklistCase> = emptyList(),
  val findings: List<StandaloneQaChecklistFinding> = emptyList(),
  val lastStepUpdateAt: Instant? = null,
)

enum class StandaloneQaChecklistWriteResult {
  WRITTEN,
  MISSING,
  FINISHED,
}
