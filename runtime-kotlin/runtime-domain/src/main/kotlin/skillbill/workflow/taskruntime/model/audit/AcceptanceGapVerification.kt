package skillbill.workflow.taskruntime.model.audit

data class AcceptanceGapVerification(
  val parsedStatuses: List<AcceptanceCriterionGapStatusReportEntry>,
  val effectiveStatuses: Map<String, AcceptanceCriterionGapStatusReportEntry>,
  val missingGapIds: List<String>,
  val gaps: List<AcceptanceCriterionGap>,
  val lateFindings: List<AcceptanceCriterionLateFinding>,
  val droppedLateFindingCount: Int,
  val openGapsBefore: Int,
  val openGapsAfter: Int,
  val outcomeKind: AcceptanceCriterionRepairOutcomeKind,
)

private data class AcceptanceGapFindingContext(
  val state: AcceptanceCriterionRepairState,
  val criterion: AcceptanceCriterionRepairCriterion,
  val changedPaths: Set<String>,
  val updatedGaps: MutableList<AcceptanceCriterionGap>,
  val findings: MutableList<AcceptanceCriterionLateFinding>,
)

object AcceptanceGapVerificationResolver {
  fun resolve(
    state: AcceptanceCriterionRepairState,
    criterion: AcceptanceCriterionRepairCriterion,
    prose: String,
    treeMatches: Boolean,
    legacySatisfied: Boolean = false,
  ): AcceptanceGapVerification {
    val parsed = AcceptanceCriterionGapStatusReportParser.parse(prose)
    val openIds = criterion.gaps.filter { it.status == AcceptanceCriterionGapStatus.OPEN }.map { it.id }
    val effective = parsed.filter { it.gapId in openIds }.associateBy { it.gapId }
    val missing = openIds.filter { it !in effective }
    val before = openIds.size
    val updatedGaps =
      criterion.gaps.map { gap ->
        if (treeMatches && gap.status == AcceptanceCriterionGapStatus.OPEN && effective[gap.id]?.closed == true) {
          gap.copy(status = AcceptanceCriterionGapStatus.CLOSED, closedRevision = state.revision + 1)
        } else {
          gap
        }
      }.toMutableList()
    val findings =
      verificationFindings(
        AcceptanceGapFindingContext(
          state,
          criterion,
          if (treeMatches) state.repairChangedPaths.map { it.removePrefix("./") }.toSet() else emptySet(),
          updatedGaps,
          mutableListOf(),
        ),
        prose,
        openIds,
      )
    val availableFindings =
      (AcceptanceCriterionGapLimits.MAX_LATE_FINDINGS - state.lateFindings.size).coerceAtLeast(0)
    val retainedFindings = findings.take(availableFindings)
    val openAfter = updatedGaps.count { it.status == AcceptanceCriterionGapStatus.OPEN }
    val sourceVerified = criterion.gaps.isNotEmpty() || legacySatisfied
    val kind =
      if (treeMatches && sourceVerified && openAfter == 0) {
        AcceptanceCriterionRepairOutcomeKind.TARGETED_SUCCESS
      } else {
        AcceptanceCriterionRepairOutcomeKind.TARGETED_FAILURE
      }
    return AcceptanceGapVerification(
      parsed,
      effective,
      missing,
      updatedGaps,
      retainedFindings,
      findings.size - retainedFindings.size,
      before,
      openAfter,
      kind,
    )
  }

  fun missingGapIds(
    prose: String,
    listedGapIds: List<String>,
  ): List<String> {
    val reported = AcceptanceCriterionGapStatusReportParser.parse(prose).map { it.gapId }.toSet()
    return listedGapIds.filter { it !in reported }
  }

  private fun verificationFindings(
    context: AcceptanceGapFindingContext,
    prose: String,
    openIds: List<String>,
  ): List<AcceptanceCriterionLateFinding> {
    val state = context.state
    val criterion = context.criterion
    val findings = context.findings
    prose.lineSequence().map(::normalizeListLine).forEach { line ->
      val status = AcceptanceCriterionGapStatusReportParser.parse(line).firstOrNull()
      when {
        line.startsWith("New gap:") ->
          line.removePrefix("New gap:").trim().takeIf(String::isNotEmpty)?.let { appendNewGapFinding(context, it) }
        line.startsWith("Late finding:") ->
          line.removePrefix("Late finding:").trim().takeIf(String::isNotEmpty)?.let {
            findings += lateFinding(state, criterion, it)
          }
        status != null && status.gapId !in openIds ->
          findings += lateFinding(state, criterion, line, status.citation)
      }
    }
    return findings
  }

  private fun appendNewGapFinding(
    context: AcceptanceGapFindingContext,
    observation: String,
  ) {
    val (text, citation) = splitCitation(observation)
    val citationMatch = citation?.let(LINE_CITATION::matchEntire)
    val path = citationMatch?.groupValues?.get(1)?.removePrefix("./")
    val changedFile = path != null && path in context.changedPaths
    val validText = text.isNotBlank() && text.length <= AcceptanceCriterionGapLimits.MAX_TEXT_LENGTH
    val validCitation = citation != null && citation.length <= AcceptanceCriterionGapLimits.MAX_CITATION_LENGTH
    val capacity = context.updatedGaps.size < AcceptanceCriterionGapLimits.MAX_GAPS_PER_CRITERION
    val admissible = changedFile && validText && validCitation
    if (admissible && capacity) {
      val number = context.updatedGaps.size + 1
      context.updatedGaps +=
        AcceptanceCriterionGap(
          "${context.criterion.canonicalRef}.G$number",
          text,
          checkNotNull(citation),
          AcceptanceCriterionGapStatus.OPEN,
          AcceptanceCriterionGapOrigin.REPAIR,
          null,
        )
    } else {
      context.findings += lateFinding(context.state, context.criterion, text, citation)
    }
  }

  private fun lateFinding(
    state: AcceptanceCriterionRepairState,
    criterion: AcceptanceCriterionRepairCriterion,
    observation: String,
    citation: String? = null,
  ): AcceptanceCriterionLateFinding {
    val (text, extractedCitation) = splitCitation(observation)
    return AcceptanceCriterionLateFinding(
      criterion.canonicalRef,
      text.take(AcceptanceCriterionGapLimits.MAX_TEXT_LENGTH),
      (citation ?: extractedCitation)?.take(AcceptanceCriterionGapLimits.MAX_CITATION_LENGTH),
      state.revision + 1,
    )
  }

  private fun splitCitation(observation: String): Pair<String, String?> {
    val match = TRAILING_REFERENCE.matchEntire(observation) ?: return observation to null
    val text = match.groupValues[1].trimEnd()
    return if (text.isBlank()) observation to null else text to match.groupValues[2]
  }

  private val TRAILING_REFERENCE = Regex("""^(.*) @ (\S+)$""")
  private val LINE_CITATION = Regex("""^(\S+):[0-9]+(?:-[0-9]+)?$""")
}

data class AcceptanceCriterionGap(
  val id: String,
  val text: String,
  val citation: String,
  val status: AcceptanceCriterionGapStatus,
  val origin: AcceptanceCriterionGapOrigin,
  val closedRevision: Long?,
)

enum class AcceptanceCriterionGapStatus(val wireValue: String) {
  OPEN("open"),
  CLOSED("closed"),
}

enum class AcceptanceCriterionGapOrigin(val wireValue: String) {
  DISCOVERY("discovery"),
  REPAIR("repair"),
}

data class AcceptanceCriterionLateFinding(
  val criterionRef: String,
  val text: String,
  val citation: String?,
  val revision: Long,
)

object AcceptanceCriterionGapLimits {
  const val MAX_GAPS_PER_CRITERION: Int = 32
  const val MAX_TEXT_LENGTH: Int = 2048
  const val MAX_CITATION_LENGTH: Int = 512
  const val MAX_LATE_FINDINGS: Int = 256
  const val MAX_CHANGED_PATHS: Int = 512
  const val MAX_CHANGED_PATH_LENGTH: Int = 1024
  const val MIN_PLAN_GENERATION: Int = 0
  const val MAX_PLAN_GENERATION: Int = 2
}

internal fun AcceptanceCriterionRepairCriterion.closeOpenGaps(revision: Long): AcceptanceCriterionRepairCriterion =
  copy(
    gaps =
      gaps.map { gap ->
        if (gap.status == AcceptanceCriterionGapStatus.OPEN) {
          gap.copy(status = AcceptanceCriterionGapStatus.CLOSED, closedRevision = revision)
        } else {
          gap
        }
      },
  )

data class AcceptanceCriterionGapReportEntry(
  val criterionRef: String,
  val number: Int,
  val text: String,
  val citation: String,
)

data class AcceptanceCriterionGapReport(
  val entries: List<AcceptanceCriterionGapReportEntry>,
  val malformedCriteria: Set<String>,
  val strayCriteria: Set<String>,
  val malformedLines: List<String>,
  val malformed: String?,
)

object AcceptanceCriterionGapReportParser {
  const val FALLBACK_GAP_TEXT =
    "Criterion reported unmet without a parseable gap list. Satisfy the authored criterion."

  private val GAP_LINE =
    Regex(
      """^(?<ref>AC-[0-9]{3})\.G(?<number>[1-9][0-9]?): (?<text>.+) @ (?<path>\S+):(?<start>[0-9]+)(?<end>-[0-9]+)?$""",
    )

  fun openCriteria(prose: String): Set<String>? =
    prose.lineSequence()
      .mapNotNull { OPEN_CRITERIA_LINE.find(it.trim())?.groupValues?.get(1) }
      .lastOrNull()
      ?.let { line -> CRITERION_ID.findAll(line).map { it.value.uppercase() }.toSet() }

  fun parse(
    prose: String,
    openCriteria: Set<String>,
  ): AcceptanceCriterionGapReport {
    val entries = mutableListOf<AcceptanceCriterionGapReportEntry>()
    val counts = mutableMapOf<String, Int>()
    val malformedCriteria = linkedSetOf<String>()
    val strayCriteria = linkedSetOf<String>()
    val malformedLines = mutableListOf<String>()
    val criterionPrefix = Regex("""^(AC-[0-9]{3})\.G""")
    prose.lineSequence().map(::normalizeListLine).filter { it.startsWith("AC-") && ".G" in it }.forEach { line ->
      val entry = parseEntry(line)
      if (entry == null) {
        val ref = criterionPrefix.find(line)?.groupValues?.get(1)
        when {
          ref == null -> malformedLines += line
          ref in openCriteria -> malformedCriteria += ref
          else -> strayCriteria += ref
        }
      } else {
        when {
          entry.criterionRef !in openCriteria -> strayCriteria += entry.criterionRef
          !entry.isValidAfter(counts.getOrDefault(entry.criterionRef, 0)) -> malformedCriteria += entry.criterionRef
          else -> {
            counts[entry.criterionRef] = entry.number
            entries += entry
          }
        }
      }
    }
    malformedCriteria += openCriteria.filter { counts.getOrDefault(it, 0) == 0 }
    val malformed =
      when {
        malformedCriteria.isNotEmpty() -> "Malformed discovery gap list for ${malformedCriteria.joinToString()}."
        strayCriteria.isNotEmpty() -> "Gap lines name criteria outside the open set: ${strayCriteria.joinToString()}."
        malformedLines.isNotEmpty() -> "Malformed gap line: ${malformedLines.first()}"
        else -> null
      }
    return AcceptanceCriterionGapReport(entries, malformedCriteria, strayCriteria, malformedLines, malformed)
  }

  private fun parseEntry(line: String): AcceptanceCriterionGapReportEntry? {
    val match = GAP_LINE.matchEntire(line) ?: return null
    return AcceptanceCriterionGapReportEntry(
      match.capture("ref"),
      match.capture("number").toInt(),
      match.capture("text"),
      match.lineCitation(),
    )
  }

  private fun AcceptanceCriterionGapReportEntry.isValidAfter(previous: Int): Boolean =
    number == previous + 1 && number <= AcceptanceCriterionGapLimits.MAX_GAPS_PER_CRITERION &&
      text.length <= AcceptanceCriterionGapLimits.MAX_TEXT_LENGTH &&
      citation.length <= AcceptanceCriterionGapLimits.MAX_CITATION_LENGTH

  private val OPEN_CRITERIA_LINE = Regex("""^\W*open criteria\W*:(.*)$""", RegexOption.IGNORE_CASE)
  private val CRITERION_ID = Regex("""\bAC-[0-9]{3}\b""", RegexOption.IGNORE_CASE)
}

data class AcceptanceCriterionGapStatusReportEntry(
  val gapId: String,
  val closed: Boolean,
  val citation: String,
)

object AcceptanceCriterionGapStatusReportParser {
  private val STATUS_LINE =
    Regex(
      """^(?<id>AC-[0-9]{3}\.G[1-9][0-9]*): (?<status>closed|open) @ (?<path>\S+):(?<start>[0-9]+)(?<end>-[0-9]+)?$""",
    )

  fun parse(prose: String): List<AcceptanceCriterionGapStatusReportEntry> =
    prose.lineSequence().map(::normalizeListLine).mapNotNull { line ->
      val match = STATUS_LINE.matchEntire(line) ?: return@mapNotNull null
      AcceptanceCriterionGapStatusReportEntry(
        match.capture("id"),
        match.capture("status") == "closed",
        match.lineCitation(),
      )
    }.toList()
}

private fun MatchResult.capture(name: String): String = checkNotNull(groups[name]).value

private fun MatchResult.lineCitation(): String =
  "${capture("path")}:${capture("start")}${groups["end"]?.value.orEmpty()}"

private fun normalizeListLine(line: String): String {
  val trimmed = line.trim()
  return if (trimmed.length > 1 && trimmed[0] in "-*+" && trimmed[1].isWhitespace()) {
    trimmed.drop(1).trimStart()
  } else {
    trimmed
  }
}
