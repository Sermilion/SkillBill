package skillbill.ports.idestatus.model

import java.time.Instant

enum class ValidationCheckEvent(val wireValue: String) {
  PLANNED("planned"),
  STARTED("started"),
  FINISHED("finished"),
  ;

  companion object {
    fun fromWire(value: String?): ValidationCheckEvent? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class ValidationCheckOutcome(val wireValue: String) {
  PASSED("passed"),
  FAILED("failed"),
  BLOCKED("blocked"),
  SKIPPED("skipped"),
  ;

  companion object {
    fun fromWire(value: String?): ValidationCheckOutcome? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class LiveChecksAvailability(val wireValue: String) {
  AVAILABLE("available"),
  UNAVAILABLE("unavailable"),
  ;

  companion object {
    fun fromWire(value: String?): LiveChecksAvailability? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class StandaloneValidationOutcome(val wireValue: String) {
  PASSED("passed"),
  FAILED("failed"),
  BLOCKED("blocked"),
  ;

  companion object {
    fun fromWire(value: String?): StandaloneValidationOutcome? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class LiveChecksWriteMode {
  UNAVAILABLE,
  KEEP_OR_UNAVAILABLE,
  AVAILABLE,
}

enum class StandaloneNarrowWriteResult {
  WRITTEN,
  MISSING,
  FINISHED,
}

data class ValidationCheckEventRecord(
  val invocationId: String,
  val sequence: Int,
  val checkId: String,
  val event: ValidationCheckEvent,
  val name: String,
  val command: String,
  val workingDir: String,
  val outcome: ValidationCheckOutcome?,
  val exitCode: Int?,
  val note: String?,
  val attempt: Int,
  val recordedAt: Instant,
)
