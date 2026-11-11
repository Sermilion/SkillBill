package skillbill.engine.featuretask.phase.prompt.directives

import skillbill.engine.featuretask.phase.planning.PlanBundleFeasibility

internal class PriorAttemptCorrection private constructor(
  public val reason: String,
  public val kind: Kind,
) {
  internal enum class Kind { RETRYABLE_TERMINAL, FINDING_COVERAGE, PLAN_BUNDLE_REJECTION }

  val retryableTerminalReason: String? get() = reason.takeIf { kind == Kind.RETRYABLE_TERMINAL }
  val findingCoverageReason: String? get() = reason.takeIf { kind == Kind.FINDING_COVERAGE }
  val planBundleRejectionReason: String? get() = reason.takeIf { kind == Kind.PLAN_BUNDLE_REJECTION }

  companion object {
    fun retryableTerminal(reason: String): PriorAttemptCorrection =
      PriorAttemptCorrection(reason, Kind.RETRYABLE_TERMINAL)

    fun unaccountedFindings(reason: String): PriorAttemptCorrection =
      PriorAttemptCorrection(reason, Kind.FINDING_COVERAGE)

    fun planBundleRejected(reason: String): PriorAttemptCorrection =
      PriorAttemptCorrection(reason, Kind.PLAN_BUNDLE_REJECTION)
  }
}

internal fun findingCoverageDirective(priorFindingCoverage: String?): String {
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

internal fun planBundleRepairDirective(priorPlanBundleRejection: String?): String {
  if (priorPlanBundleRejection.isNullOrBlank()) return ""
  if (priorPlanBundleRejection.startsWith(PlanBundleFeasibility.REVIEW_REQUIRED)) {
    return "## Fresh bundle feasibility review required\n$priorPlanBundleRejection\n" +
      "Review the current bundle without editing files. The preceding planner cannot approve its own repair."
  }
  return """
    ## Spec bundle rejected: repair it in place
    The runtime rejected the spec bundle your previous attempt authored. That bundle is still on disk.
    Rejection:
    $priorPlanBundleRejection
    Edit the existing bundle files until they meet this rule, and check every spec for the same mistake.
    Do not re-plan the work or rewrite content the rejection does not concern. Then settle the phase again.
    """.trimIndent()
}

internal fun terminalRetryDirective(priorTerminalFailure: String?): String {
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
