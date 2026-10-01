package skillbill.engine.featuretask.phase.prompt.directives

class PriorAttemptCorrection private constructor(
  private val reason: String,
  private val kind: Kind,
) {
  internal enum class Kind { RETRYABLE_TERMINAL, FINDING_COVERAGE }

  val retryableTerminalReason: String? get() = reason.takeIf { kind == Kind.RETRYABLE_TERMINAL }
  val findingCoverageReason: String? get() = reason.takeIf { kind == Kind.FINDING_COVERAGE }

  companion object {
    fun retryableTerminal(reason: String): PriorAttemptCorrection =
      PriorAttemptCorrection(reason, Kind.RETRYABLE_TERMINAL)

    fun unaccountedFindings(reason: String): PriorAttemptCorrection =
      PriorAttemptCorrection(reason, Kind.FINDING_COVERAGE)
  }
}

fun findingCoverageDirective(priorFindingCoverage: String?): String {
  if (priorFindingCoverage.isNullOrBlank()) return ""
  return """
    ## Findings still owed — continue this round
    Your previous attempt at this phase emitted a VALID repair receipt. It was NOT rejected and its
    format was NOT wrong. It was incomplete:
    $priorFindingCoverage
    Keep the entries you already wrote and add the missing ones. Do the repair work first, then write
    the entry that describes it. Repeating the same receipt without accounting for the named findings
    blocks the run.
    """.trimIndent()
}

fun terminalRetryDirective(priorTerminalFailure: String?): String {
  if (priorTerminalFailure.isNullOrBlank()) return ""
  return """
    ## Previous attempt reported a retryable block — try again
    Your previous attempt at this phase reported that the phase could not finish. It was NOT rejected.
    Reported reason:
    $priorTerminalFailure
    Re-attempt the phase against the current repository state. If the same obstacle still stands and you
    cannot clear it, report it again with the disposition that matches it; a re-reported block with no new
    attempt behind it will exhaust this phase's budget.
    """.trimIndent()
}
