package skillbill.workflow.taskruntime.model.validation

import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode

sealed interface ValidationTestProbeParse {
  data class Parsed(val probes: List<ValidationTestProbe>) : ValidationTestProbeParse

  data class Malformed(
    val code: FeatureTaskRuntimeFailureCode,
    val reason: String,
  ) : ValidationTestProbeParse
}

object ValidationTestProbeParser {
  const val LINE_GRAMMAR =
    "For optional telemetry, use one line per test in the form " +
      "`Test probe <test_ref>: <killed|survived|not_run> guards <guarded_ref> repaired <yes|no> reason <text>`."

  fun parse(prose: String): ValidationTestProbeParse {
    val candidates = prose.lineSequence().map(String::trim).filter { PREFIX.containsMatchIn(it) }.toList()
    if (candidates.isEmpty()) return malformed("the report has no test probe lines.")
    val probes = mutableListOf<ValidationTestProbe>()
    for (line in candidates) {
      probes += probeOf(line) ?: return malformed("`$line` does not match the test probe line grammar.")
    }
    return ValidationTestProbeShape.issue(probes)?.let(::malformed) ?: ValidationTestProbeParse.Parsed(probes)
  }

  private fun malformed(reason: String) =
    ValidationTestProbeParse.Malformed(FeatureTaskRuntimeFailureCode.INVALID_VALIDATION_TEST_PROBES, reason)

  private fun probeOf(line: String): ValidationTestProbe? {
    val match = LINE.matchEntire(line) ?: return null
    val result = ValidationTestProbeResult.fromWire(match.groupValues[RESULT_GROUP].lowercase()) ?: return null
    return ValidationTestProbe(
      testRef = match.groupValues[TEST_GROUP].cleaned(),
      guardedRef = match.groupValues[GUARDED_GROUP].cleaned(),
      result = result,
      reason = match.groupValues[REASON_GROUP].cleaned(),
      repaired = match.groupValues[REPAIRED_GROUP].equals("yes", ignoreCase = true),
    )
  }

  private fun String.cleaned(): String = trim().trim('*', '`', ' ')

  private const val TEST_GROUP = 1
  private const val RESULT_GROUP = 2
  private const val GUARDED_GROUP = 3
  private const val REPAIRED_GROUP = 4
  private const val REASON_GROUP = 5
  private val PREFIX = Regex("""^\W*Test probe\b(?!\W*:)""", RegexOption.IGNORE_CASE)
  private val LINE =
    Regex(
      """^\W*Test probe\s+(.+?)\s*:\s*\W*(killed|survived|not_run)\b\W*\s+guards\s+(.+?)\s+""" +
        """repaired\s+(yes|no)\b\W*\s*reason\b[\s:]*(.*)$""",
      RegexOption.IGNORE_CASE,
    )
}
