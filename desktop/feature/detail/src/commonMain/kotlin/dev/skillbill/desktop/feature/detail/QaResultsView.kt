package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.ExecutionInstance
import dev.skillbill.desktop.core.domain.model.GoalQaResult
import dev.skillbill.desktop.core.domain.model.QaAssertionOutcome
import dev.skillbill.desktop.core.domain.model.QaRunAssertion
import dev.skillbill.desktop.core.domain.model.QaRunCoverage
import dev.skillbill.desktop.core.domain.model.QaRunDetails
import dev.skillbill.desktop.core.domain.model.QaRunRetainedResource
import dev.skillbill.desktop.core.domain.model.QaRunTemporaryEdit
import dev.skillbill.desktop.core.domain.model.QaVerdict
import dev.skillbill.desktop.core.domain.model.WorkItem

internal enum class QaResultsTone { Failed, Blocked, Done, Faint }
internal enum class QaEvidenceMode { Invocation, VerifyNamesOnly }

internal data class QaResultsFinding(
  val id: String,
  val title: String,
  val fixed: Boolean = false,
  val observed: String? = null,
  val expected: String? = null,
  val steps: String? = null,
  val evidencePaths: List<String> = emptyList(),
  val evidenceDirectory: String? = null,
)

internal data class QaResultsInput(
  val runKey: String,
  val outcome: QaVerdict?,
  val running: Boolean = false,
  val fixLoop: Boolean = false,
  val fixAttempts: Int = 0,
  val reason: String? = null,
  val failureKind: String? = null,
  val reportText: String? = null,
  val findings: List<QaResultsFinding> = emptyList(),
  val details: QaRunDetails? = null,
  val fallbackInvocationDirectory: String? = null,
  val fallbackReportPath: String? = null,
  val evidenceMode: QaEvidenceMode = QaEvidenceMode.Invocation,
)

internal data class QaFindingRow(
  val id: String,
  val title: String,
  val state: String,
  val observed: String?,
  val expected: String?,
  val steps: String?,
  val copySteps: String?,
  val evidenceDirectory: String?,
  val evidencePaths: List<String>,
)

/** A Not tested row, or one row of a list fold. */
internal data class QaDetailRow(val title: String, val detail: String, val evidenceDirectory: String?, val evidencePaths: List<String> = emptyList())

internal data class QaLeftRow(val title: String, val detail: String, val copyLabel: String, val copyText: String?)

internal data class QaListFold(val label: String, val preview: String, val rows: List<QaDetailRow>)

internal data class QaReportFold(val text: String, val preview: String, val firstParagraph: String?)

internal data class QaResultsView(
  val runKey: String,
  val word: String,
  val tone: QaResultsTone,
  val sentence: String,
  val open: List<QaFindingRow>,
  val left: List<QaLeftRow>,
  val notTested: List<QaDetailRow>,
  val fixed: QaListFold?,
  val passed: QaListFold?,
  val runDetails: String?,
  val report: QaReportFold?,
  val targets: String?,
  val reportPath: String?,
  val evidenceMode: QaEvidenceMode,
  val unblock: List<QaUnblockEntry>,
)

internal fun goalQaInput(qa: GoalQaResult, running: Boolean): QaResultsInput {
  val reportPath = qa.findings.firstNotNullOfOrNull { finding -> finding.reportPath?.takeIf { it.isNotBlank() } }
  return QaResultsInput(
    runKey = qa.recordedAt.toString(),
    outcome = goalQaVerdict(qa.outcome),
    running = running,
    fixLoop = true,
    fixAttempts = qa.fixAttempts,
    reason = qa.reason,
    failureKind = qa.failureKind,
    reportText = qa.reportText,
    findings = qa.findings.map { finding ->
      val evidenceDirectory = finding.reportPath?.takeIf { it.isNotBlank() }?.let(::parentDirectory)
      QaResultsFinding(finding.id, finding.title, finding.fixed, finding.observed, finding.expected, finding.steps, finding.evidencePaths, evidenceDirectory)
    },
    details = qa.details,
    fallbackInvocationDirectory = qa.details?.invocationDirectory
      ?: qa.details?.reportPath?.takeIf { it.isNotBlank() }?.let(::parentDirectory),
    fallbackReportPath = reportPath,
  )
}

internal fun standaloneQaInput(item: WorkItem, run: ExecutionInstance): QaResultsInput {
  val declared = run.qaDetails?.findings.orEmpty().associateBy { it.id }
  return QaResultsInput(
    runKey = run.invocationId,
    outcome = run.qaVerdict,
    running = qaRunLive(item, run),
    reason = run.result,
    reportText = run.qaReportText,
    findings = qaFindings(run).map { finding ->
      val detail = declared[finding.id]
      QaResultsFinding(finding.id, finding.title, false, detail?.observed, detail?.expected, detail?.steps, detail?.evidencePaths.orEmpty())
    },
    details = run.qaDetails,
    fallbackInvocationDirectory = run.qaChecklist?.invocationDirectory,
    fallbackReportPath = run.qaChecklist?.reportPath,
  )
}

private fun goalQaVerdict(outcome: String): QaVerdict = when (outcome) {
  "passed" -> QaVerdict.Passed
  "findings" -> QaVerdict.Findings
  "skipped" -> QaVerdict.Skipped
  "incomplete" -> QaVerdict.Incomplete
  else -> QaVerdict.Attempted
}

private fun parentDirectory(path: String): String? =
  path.substring(0, path.lastIndexOfAny(charArrayOf('/', '\\')).coerceAtLeast(0)).takeIf { it.isNotEmpty() }

private enum class QaWord(val label: String) {
  Running("Running"),
  Review("Needs your review"),
  Skipped("Skipped"),
  DidNotFinish("Did not finish"),
  Incomplete("Incomplete"),
  Cleanup("Passed, cleanup needed"),
  PassedAfterFixes("Passed after fixes"),
  Passed("Passed"),
}

private data class QaCounts(val open: Int, val fixed: Int, val untested: Int, val passed: Int, val left: Int, val tagged: Boolean)

internal fun qaResultsView(input: QaResultsInput): QaResultsView {
  val details = input.details
  val directory = (details?.invocationDirectory ?: input.fallbackInvocationDirectory)?.takeIf { it.isNotBlank() }
  val findings = input.findings.distinctBy { it.id }
  val open = findings.filterNot { it.fixed }
  val fixed = findings.filter { it.fixed }
  val assertions = details?.assertions.orEmpty()
  val passed = assertions.filter { it.outcome == QaAssertionOutcome.Passed }
  val leftEdits = details?.temporaryEdits.orEmpty().filter { it.notRestored }
  val retained = details?.retainedResources.orEmpty()
  val fixedFold = fixed.takeIf { it.isNotEmpty() }?.let { rows ->
    listFold(
      "Fixed by QA",
      rows.map { QaDetailRow(if (it.title.isBlank()) it.id else "${it.id} ${it.title}", capitalized(it.observed.orEmpty()), it.evidenceDirectory ?: directory, it.evidencePaths) },
      rows.map { shortenPaths(it.title.ifBlank { it.id }, it.evidenceDirectory ?: directory) },
    )
  }
  val passedFold = passed.takeIf { it.isNotEmpty() }?.let { rows ->
    listFold("Passed", rows.map { QaDetailRow(it.title, it.detail, directory, it.evidencePaths) }, rows.map { shortenPaths(it.title, directory) })
  }
  val notTested = notTestedRows(assertions, details?.coverage.orEmpty(), open.size, directory)
  val unfinished = input.outcome == QaVerdict.Incomplete || input.outcome == QaVerdict.Skipped || notTested.isNotEmpty()
  val counts = QaCounts(open.size, fixed.size, notTested.size, passed.size, retained.size + leftEdits.size, assertions.isNotEmpty())
  val skipToken = skipToken(input)
  val word = qaWord(input, counts)
  val leftList = joinedList(
    retained.groupBy { it.kind }.map { (kind, rows) -> leftPhrase(kind, rows.size) } +
      listOfNotNull(leftEdits.size.takeIf { it > 0 }?.let { counted(it, "temporary edit") }),
  )
  return QaResultsView(
    runKey = input.runKey,
    word = word.label,
    tone = qaTone(word, skipToken, counts.tagged),
    sentence = qaSentence(word, input, counts, skipToken, leftList),
    open = open.map { findingRow(it, input.fixLoop, directory) },
    left = leftRows(retained, leftEdits, directory),
    notTested = notTested,
    fixed = fixedFold,
    passed = passedFold,
    runDetails = runDetails(input)?.let { shortenPaths(it, directory) },
    report = reportFold(input, counts.tagged, directory),
    targets = targetsLine(details?.coverage.orEmpty()),
    reportPath = (details?.reportPath ?: input.fallbackReportPath)?.takeIf { it.isNotBlank() },
    evidenceMode = input.evidenceMode,
    unblock = if (unfinished) unblockEntries(input.reportText, directory) else emptyList(),
  )
}

private fun qaWord(input: QaResultsInput, counts: QaCounts): QaWord {
  val outcome = input.outcome
  val unsettled = outcome == null || outcome == QaVerdict.Attempted
  val unfinished = unsettled || outcome == QaVerdict.Incomplete
  return when {
    input.running && unsettled -> QaWord.Running
    counts.open > 0 || outcome == QaVerdict.Findings -> QaWord.Review
    outcome == QaVerdict.Skipped -> QaWord.Skipped
    unfinished && input.reportText.isNullOrBlank() -> QaWord.DidNotFinish
    counts.untested > 0 || unfinished -> QaWord.Incomplete
    counts.left > 0 -> QaWord.Cleanup
    counts.fixed > 0 -> QaWord.PassedAfterFixes
    else -> QaWord.Passed
  }
}

private fun qaTone(word: QaWord, skipToken: String?, tagged: Boolean): QaResultsTone = when (word) {
  QaWord.Running -> QaResultsTone.Faint
  QaWord.Review, QaWord.DidNotFinish -> QaResultsTone.Failed
  QaWord.Skipped -> if (skipToken == EMPTY_SCOPE) QaResultsTone.Faint else QaResultsTone.Blocked
  QaWord.Incomplete, QaWord.Cleanup -> QaResultsTone.Blocked
  QaWord.PassedAfterFixes, QaWord.Passed -> if (tagged) QaResultsTone.Done else QaResultsTone.Faint
}

private fun qaSentence(word: QaWord, input: QaResultsInput, counts: QaCounts, skipToken: String?, leftList: String): String {
  val checks = if (counts.tagged && counts.passed > 0) "${counted(counts.passed, "check")} passed." else null
  val problems = counted(counts.open, "problem")
  val things = counted(counts.untested, "thing")
  val passedLine = when {
    !counts.tagged -> "QA passed. This run has no tagged checks."
    counts.passed == 1 -> "The 1 check passed."
    else -> "All ${counts.passed} checks passed."
  }
  val body = when (word) {
    QaWord.Running -> listOfNotNull("QA is still running.", "It has ${counted(counts.open, "open problem")} so far.".takeIf { counts.open > 0 })
    QaWord.Review -> when {
      counts.open == 0 -> listOf("QA reported problems but listed none of them. Open the full report.")
      else -> {
        val lead = if (input.fixLoop) "QA could not fix $problems" else "QA found $problems"
        listOfNotNull(lead + (if (counts.untested > 0) " and could not test $things" else "") + ".", checks)
      }
    }
    QaWord.Skipped -> listOf(skippedSentence(skipToken))
    QaWord.DidNotFinish -> listOf("QA stopped before it wrote a report, so this change has no QA result.")
    QaWord.Incomplete -> when {
      counts.untested > 0 && counts.fixed > 0 -> listOfNotNull("QA fixed ${counted(counts.fixed, "problem")}, but could not test $things.", checks)
      counts.untested > 0 -> listOfNotNull("QA found no problems, but could not test $things.", checks)
      else -> listOfNotNull("QA did not finish testing this change.", checks)
    }
    QaWord.Cleanup -> if (counts.tagged) listOf(passedLine) else listOf("QA passed, but left $leftList.")
    QaWord.PassedAfterFixes -> {
      val after = if (input.fixAttempts > 0) " after ${counted(input.fixAttempts, "attempt")}" else ""
      val fixedThem = if (counts.fixed == 1) "fixed it" else "fixed them"
      listOfNotNull("QA found ${counted(counts.fixed, "problem")} and $fixedThem$after.", if (counts.tagged) checks else "This run has no tagged checks.")
    }
    QaWord.Passed -> listOf(passedLine)
  }
  val appendLeft = counts.left > 0 && !(word == QaWord.Cleanup && !counts.tagged)
  return (body + listOfNotNull("QA left $leftList.".takeIf { appendLeft })).joinToString(" ")
}

private fun skippedSentence(token: String?): String = when (token) {
  EMPTY_SCOPE -> "QA skipped this change. No acceptance criterion describes behavior it can exercise."
  null -> "QA skipped this change and did not say what was missing."
  else -> "QA skipped this change because ${skipPhrase(token)}."
}

private val BARE_TOKEN = Regex("""^([a-z_]+)(?:$|;| ·)""")
private val LEADING_TOKEN = Regex("""^[a-z_]+(?![a-z0-9_])""")
private val REASON_LINE = Regex("""^reason:[ \t]*(.*)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

/**
 * A stored reason can read "token · detail" or carry warnings after "; ", and any token counts there. A token followed by other
 * detail ("token. detail") counts only if it is a known skip reason, so a free-text sentence must not yield its first word.
 */
private fun reasonToken(text: String?): String? {
  val trimmed = text?.trim() ?: return null
  return BARE_TOKEN.find(trimmed)?.groupValues?.get(1)
    ?: LEADING_TOKEN.find(trimmed)?.value?.takeIf(::isKnownSkipReason)
}

private fun skipToken(input: QaResultsInput): String? =
  input.details?.skipReason?.takeIf { it.isNotBlank() }
    ?: input.reportText?.let { report -> REASON_LINE.find(report)?.groupValues?.get(1) }.let(::reasonToken)
    ?: reasonToken(input.reason?.removePrefix("QA verdict: skipped: "))

private fun leftPhrase(kind: String, count: Int): String = when {
  kind != "process" -> counted(count, humanized(kind))
  count == 1 -> "1 process running"
  else -> "$count processes running"
}

private fun findingRow(finding: QaResultsFinding, fixLoop: Boolean, runDirectory: String?): QaFindingRow {
  val directory = finding.evidenceDirectory ?: runDirectory
  fun shown(text: String?) = text?.takeIf { it.isNotBlank() }?.let { shortenPaths(it, directory) }
  return QaFindingRow(
    id = finding.id,
    title = shortenPaths(finding.title.ifBlank { finding.id }, directory),
    state = if (fixLoop) "Not fixed" else "Open",
    observed = shown(finding.observed)?.let(::capitalized),
    expected = shown(finding.expected),
    steps = shown(finding.steps),
    copySteps = finding.steps?.takeIf { it.isNotBlank() }?.replace("`", ""),
    evidenceDirectory = directory,
    evidencePaths = finding.evidencePaths,
  )
}

private fun notTestedRows(assertions: List<QaRunAssertion>, coverage: List<QaRunCoverage>, open: Int, directory: String?): List<QaDetailRow> {
  fun row(assertion: QaRunAssertion) =
    QaDetailRow(shortenPaths(assertion.title, directory), shortenPaths(assertion.detail, directory), directory, assertion.evidencePaths)
  val notExercised = assertions.filter { it.outcome == QaAssertionOutcome.NotExercised }.map(::row)
  val partial = if (notExercised.isNotEmpty()) emptyList() else coverage.filter { it.isPartial }.map { line ->
    QaDetailRow("${capitalized(humanized(line.kind))}, ${line.reason?.let(::shortReason) ?: humanized(line.verdict)}", "", null)
  }
  val failed = if (open > 0) emptyList() else assertions.filter { it.outcome == QaAssertionOutcome.Failed }.map(::row)
  return notExercised + partial + failed
}

private val QaRunCoverage.isPartial: Boolean
  get() = verdict == "incomplete" || (verdict == "skipped" && reason != EMPTY_SCOPE)

private fun leftRows(resources: List<QaRunRetainedResource>, edits: List<QaRunTemporaryEdit>, directory: String?): List<QaLeftRow> {
  val retained = resources.map { resource ->
    val identity = resource.identity
    when (resource.kind) {
      "process" -> QaLeftRow("Process still running", "pid $identity. Release reported it as retained.", "Copy pid", identity)
      "directory", "worktree" -> QaLeftRow("${capitalized(resource.kind)} left behind", fileName(identity.trimEnd('/', '\\')), "Copy path", identity)
      "device_lease" -> QaLeftRow("Device lease still held", shortenPaths(identity, directory), "Copy name", identity)
      else -> QaLeftRow("${capitalized(humanized(resource.kind))} left behind", shortenPaths(identity, directory), "Copy name", identity)
    }
  }
  return retained + edits.map { edit ->
    val detail = listOfNotNull(edit.path?.let { "`$it`" }, edit.detail.takeIf { it.isNotBlank() }).joinToString(": ")
    QaLeftRow("Temporary edit", shortenPaths(detail, directory), "Copy path", edit.path)
  }
}

private fun listFold(label: String, rows: List<QaDetailRow>, shortTitles: List<String>) = QaListFold(
  label,
  shortTitles.joinToString(", "),
  rows.map { it.copy(title = shortenPaths(it.title, it.evidenceDirectory), detail = shortenPaths(it.detail, it.evidenceDirectory)) },
)

private fun runDetails(input: QaResultsInput): String? = listOfNotNull(
  input.failureKind?.takeIf { it.isNotBlank() }?.let { capitalized(humanized(it)) },
  input.reason?.takeIf { it.isNotBlank() },
).joinToString("\n").ifEmpty { null }

private val REPORT_HEAD_LINE = Regex("""^(?:verdict|reason):.*$""", RegexOption.IGNORE_CASE)

private fun reportFold(input: QaResultsInput, tagged: Boolean, directory: String?): QaReportFold? {
  val text = input.reportText?.takeIf { it.isNotBlank() } ?: return null
  val noChecks = !tagged && input.outcome != QaVerdict.Skipped
  val firstParagraph = if (!noChecks) null else {
    val prose = text.trim().lines().dropWhile { it.isBlank() || REPORT_HEAD_LINE.matches(it.trim()) }.joinToString("\n")
    reportBlocks(prose).firstNotNullOfOrNull { (it as? ReportBlock.Paragraph)?.text }?.let { shortenPaths(it, directory) }
  }
  val preview = if (noChecks) "No tagged checks in this run" else "Isolation, cleanup and the agent's notes"
  return QaReportFold(shortenPaths(text, directory), preview, firstParagraph)
}

private fun targetsLine(coverage: List<QaRunCoverage>): String? {
  if (coverage.isEmpty()) return null
  val (outOfScope, inScope) = coverage.partition { it.verdict == "skipped" && it.reason == EMPTY_SCOPE }
  val lines = inScope.map { line ->
    val kind = capitalized(humanized(line.kind))
    val reason = line.reason?.let { ", ${shortReason(it)}" }.orEmpty()
    when (line.verdict) {
      "passed", "findings_open" -> "$kind tested"
      else -> "$kind ${humanized(line.verdict)}$reason"
    }
  }
  val scope = outOfScope.takeIf { it.isNotEmpty() }?.let { rows ->
    capitalized(rows.joinToString(", ") { humanized(it.kind) }) + " not in scope"
  }
  return (lines + listOfNotNull(scope)).joinToString(" · ")
}
