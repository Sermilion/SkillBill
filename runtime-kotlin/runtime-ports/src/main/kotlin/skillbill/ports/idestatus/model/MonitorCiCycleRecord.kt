package skillbill.ports.idestatus.model

import java.time.Instant

enum class MonitorCiCycleOutcome(val wireValue: String) {
  PASSED("passed"),
  FAILED("failed"),
  CONFLICTED("conflicted"),
  MERGED("merged"),
  NO_CHECKS("no_checks"),
  NO_PULL_REQUEST("no_pull_request"),
  BLOCKED("blocked"),
  UNAVAILABLE("unavailable"),
  ;

  companion object {
    fun fromWire(value: String?): MonitorCiCycleOutcome? = entries.firstOrNull { it.wireValue == value }
  }
}

data class MonitorCiCycleRecord(
  val cycle: Int,
  val phaseId: String,
  val startedAt: Instant,
  val observedAt: Instant,
  val finishedAt: Instant? = null,
  val outcome: MonitorCiCycleOutcome? = null,
  val headSha: String? = null,
  val pullRequestNumber: Int? = null,
  val pullRequestUrl: String? = null,
  val conflictBaseBranch: String? = null,
  val repairCommitSha: String? = null,
  val checks: List<MonitorCiCycleCheck> = emptyList(),
)

data class MonitorCiCycleCheck(
  val name: String,
  val bucket: MonitorCiCheckBucket,
  val link: String,
  val startedAt: Instant? = null,
  val completedAt: Instant? = null,
)

enum class MonitorCiCheckBucket(val wireValue: String) {
  PASS("pass"),
  SKIPPING("skipping"),
  FAIL("fail"),
  CANCEL("cancel"),
  PENDING("pending"),
  ;

  companion object {
    fun fromWire(value: String?): MonitorCiCheckBucket? = entries.firstOrNull { it.wireValue == value }
  }
}
