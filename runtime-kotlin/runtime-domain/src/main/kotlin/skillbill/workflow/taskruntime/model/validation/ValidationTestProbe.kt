package skillbill.workflow.taskruntime.model.validation

enum class ValidationTestProbeResult(val wireValue: String) {
  KILLED("killed"),
  SURVIVED("survived"),
  NOT_RUN("not_run"),
  ;

  companion object {
    fun fromWire(value: String): ValidationTestProbeResult? = entries.firstOrNull { it.wireValue == value }
  }
}

data class ValidationTestProbe(
  val testRef: String,
  val guardedRef: String,
  val result: ValidationTestProbeResult,
  val reason: String,
  val repaired: Boolean,
)

object ValidationTestProbeShape {
  const val MAX_PROBES = 6

  fun issue(probes: List<ValidationTestProbe>): String? =
    when {
      probes.size > MAX_PROBES -> "at most $MAX_PROBES test probes may be reported, got ${probes.size}."
      else -> duplicateRef(probes.map { it.testRef }) ?: probes.firstNotNullOfOrNull(::probeIssue)
    }

  fun duplicateRef(testRefs: List<String>): String? =
    testRefs.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }
      ?.let { "test probe ${it.key} is reported more than once." }

  private fun probeIssue(probe: ValidationTestProbe): String? =
    when {
      probe.testRef.isBlank() || probe.guardedRef.isBlank() -> "a test probe needs a test and a guarded ref."
      probe.result == ValidationTestProbeResult.NOT_RUN && probe.reason.isBlank() ->
        "test probe ${probe.testRef} is not_run without a reason."
      probe.repaired && probe.result != ValidationTestProbeResult.KILLED ->
        "test probe ${probe.testRef} is repaired but its result is ${probe.result.wireValue}."
      else -> null
    }
}
