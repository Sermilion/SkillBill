package skillbill.workflow.taskruntime.model.audit.interaction

import skillbill.error.shellcontent.FeatureTaskRuntimeFailureCode

sealed interface AcceptanceCriterionInteractionTraceParse {
  data class Parsed(
    val entries: List<AcceptanceCriterionInteractionEntry>,
    val ignoredRefs: List<String>,
    val rechecks: List<AcceptanceCriterionRecheckEntry> = emptyList(),
  ) : AcceptanceCriterionInteractionTraceParse

  data class Malformed(
    val code: FeatureTaskRuntimeFailureCode,
    val reason: String,
  ) : AcceptanceCriterionInteractionTraceParse
}

object AcceptanceCriterionInteractionTraceParser {
  fun parse(
    prose: String,
    targets: List<AcceptanceCriterionInteractionTarget>,
    recheckRefs: List<String> = emptyList(),
  ): AcceptanceCriterionInteractionTraceParse {
    val wanted = targets.map { it.scenarioRef }.toSet()
    val lines = prose.lineSequence().mapNotNull(::traceLine).toList()
    val ignored = lines.map { it.ref }.filter { it !in wanted }.distinct()
    val entries =
      when (val result = entriesFor(targets, lines.filter { it.ref in wanted })) {
        is Checked.Ok -> result.value
        is Checked.Bad -> return malformed(result.reason)
      }
    val rechecks =
      when (val result = AcceptanceCriterionRecheckParser.parse(prose, recheckRefs)) {
        is AcceptanceCriterionRecheckParse.Parsed -> result.entries
        is AcceptanceCriterionRecheckParse.Rejected -> return malformed(result.reason)
      }
    return AcceptanceCriterionInteractionTraceParse.Parsed(entries, ignored, rechecks)
  }

  private fun entriesFor(
    targets: List<AcceptanceCriterionInteractionTarget>,
    relevant: List<TraceLine>,
  ): Checked<List<AcceptanceCriterionInteractionEntry>> {
    val duplicated = relevant.groupingBy { it.ref }.eachCount().entries.firstOrNull { it.value > 1 }
    if (duplicated != null) return Checked.Bad("${duplicated.key} is traced more than once.")
    val byRef = relevant.associateBy { it.ref }
    val entries = mutableListOf<AcceptanceCriterionInteractionEntry>()
    for (target in targets) {
      val line = byRef[target.scenarioRef] ?: return Checked.Bad("${target.scenarioRef} has no trace line.")
      when (val result = entryFor(target, line)) {
        is Checked.Ok -> entries += result.value
        is Checked.Bad -> return result
      }
    }
    return Checked.Ok(entries)
  }

  private fun malformed(reason: String) =
    AcceptanceCriterionInteractionTraceParse.Malformed(
      FeatureTaskRuntimeFailureCode.INVALID_INTERACTION_TRACE_AUDIT_OUTPUT,
      reason,
    )

  private fun traceLine(line: String): TraceLine? {
    val match = LINE.find(line.trim()) ?: return null
    val verdict =
      AcceptanceCriterionInteractionVerdict.entries.first {
        it.wireValue.equals(match.groupValues[VERDICT_GROUP], ignoreCase = true)
      }
    val rest = match.groupValues[REST_GROUP].trim().trimEnd('*', '`', '_', ' ')
    return TraceLine(match.groupValues[REF_GROUP].uppercase(), verdict, rest)
  }

  private fun entryFor(
    target: AcceptanceCriterionInteractionTarget,
    line: TraceLine,
  ): Checked<AcceptanceCriterionInteractionEntry> {
    val ref = target.scenarioRef
    val trace = TRACE.matchEntire(line.rest)?.groupValues?.get(1)?.trim()
    return when (line.verdict) {
      AcceptanceCriterionInteractionVerdict.HOLDS ->
        if (trace == null || !CITATION.containsMatchIn(trace)) {
          Checked.Bad("$ref holds without a path:line citation.")
        } else {
          Checked.Ok(AcceptanceCriterionInteractionEntry(ref, line.verdict, null, trace))
        }
      AcceptanceCriterionInteractionVerdict.BROKEN -> brokenEntry(target, line)
      AcceptanceCriterionInteractionVerdict.UNKNOWN ->
        (trace ?: line.rest).takeIf { it.isNotBlank() }?.let {
          Checked.Ok(AcceptanceCriterionInteractionEntry(ref, line.verdict, null, it))
        } ?: Checked.Bad("$ref is unknown without a reason.")
    }
  }

  private fun brokenEntry(
    target: AcceptanceCriterionInteractionTarget,
    line: TraceLine,
  ): Checked<AcceptanceCriterionInteractionEntry> {
    val ref = target.scenarioRef
    val match =
      BROKEN_REST.matchEntire(line.rest)
        ?: return Checked.Bad("$ref broken must read `owner AC-NNN trace <citations>`.")
    val owner = match.groupValues[1].uppercase()
    val trace = match.groupValues[2].trim()
    return when {
      owner !in target.allowedOwners ->
        Checked.Bad("$ref broken owner $owner is not a reopenable criterion of that scenario.")
      !CITATION.containsMatchIn(trace) -> Checked.Bad("$ref broken without a path:line citation.")
      else -> Checked.Ok(AcceptanceCriterionInteractionEntry(ref, line.verdict, owner, trace))
    }
  }

  private data class TraceLine(
    val ref: String,
    val verdict: AcceptanceCriterionInteractionVerdict,
    val rest: String,
  )

  private sealed interface Checked<out T> {
    data class Ok<T>(val value: T) : Checked<T>

    data class Bad(val reason: String) : Checked<Nothing>
  }

  private const val REF_GROUP = 1
  private const val VERDICT_GROUP = 2
  private const val REST_GROUP = 3
  private val LINE =
    Regex(
      """^\W*Interaction\s+(IS-\d{3}|AC-\d{3}\+AC-\d{3})\s*:\s*\W*(holds|broken|unknown)\b\W*(.*)$""",
      RegexOption.IGNORE_CASE,
    )
  private val TRACE = Regex("""^trace\s+(.+)$""", RegexOption.IGNORE_CASE)
  private val BROKEN_REST = Regex("""^owner\s+(AC-\d{3})\s+trace\s+(.+)$""", RegexOption.IGNORE_CASE)
  private val CITATION = Regex("""[\w./\\-]+:\d+(?:-\d+)?""")
}
