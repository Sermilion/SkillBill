package skillbill.engine.featuretask.slot.standalonereview

import skillbill.application.review.model.ParallelCodeReviewResult
import skillbill.ports.review.model.ReviewIntegrationPassOutcome
import skillbill.review.context.model.accounting.ReviewIntegrationTerminalOutcome
import skillbill.review.model.ParallelReviewLaneResult
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.ReviewLaneReviewDisposition
import skillbill.review.parallel.ParallelReviewFindingParser
import skillbill.review.parallel.ParallelReviewMerger
import skillbill.review.parallel.isCodeQualityFinding
import skillbill.workflow.model.validation.FeatureTaskRuntimeVerdict

internal data class StandaloneReviewReport(
  val rawOutput: String,
  val registerOutput: String,
  val verdict: String?,
  val findings: List<ParallelReviewMergedFinding>,
  val census: List<StandaloneReviewFinding>,
  val findingGroups: List<StandaloneReviewFindingGroup>,
  val warnings: List<String>,
  val reviewWarnings: List<StandaloneReviewWarning>,
  val hasIncompleteReviewWarning: Boolean,
  val coverage: String?,
  val diagnostics: List<String>,
  val rejectionReasons: List<String>,
) {
  val admitted: Boolean get() = rejectionReasons.isEmpty()
}

internal data class StandaloneReviewFindingGroup(
  val identity: String,
  val finding: ParallelReviewMergedFinding?,
  val members: List<StandaloneReviewFinding>,
)

internal data class StandaloneReviewWarning(
  val identity: String,
  val text: String,
  val source: String,
  val alreadyIncomplete: Boolean,
)

internal data class StandaloneReviewFinding(
  val identity: String,
  val text: String,
  val finding: ParallelReviewMergedFinding?,
  val source: String,
  val requiresChangesWhenConfirmed: Boolean,
)

internal object StandaloneReviewReportAdmission {
  fun admit(
    rawOutput: String,
    result: ParallelCodeReviewResult,
    truncated: Boolean,
    requireDelegatedCoverage: Boolean = false,
  ): StandaloneReviewReport {
    val parent =
      StandaloneReviewReportSourceCensusBuilder.build(
        rawOutput,
        PARENT_REPORT,
        strictContext = false,
        producerId = result.lane1.agentId,
      )
    val integration = integrationCandidates(result.integration)
    val (candidates, reasons, mergedMemberships) = collectCandidates(rawOutput, result, parent, integration)

    val census =
      candidates.mapIndexed { index, candidate ->
        val merged = candidate.finding?.let(::mergeFinding)
        StandaloneReviewFinding(
          identity = "F-${(index + 1).toString().padStart(IDENTITY_DIGITS, '0')}",
          text = candidate.text,
          finding = merged,
          source = candidate.source,
          requiresChangesWhenConfirmed = requiresChangesWhenConfirmed(candidate, merged, candidates),
        )
      }
    if (census.size > MAX_CENSUS_FINDINGS) {
      reasons += "report contains more findings than the verifier identity format supports"
    }
    val findingGroups = findingGroups(census, result.mergeResult.findings, mergedMemberships)

    val (coverageLines, warnings, reviewWarnings) = collectWarnings(parent, integration, result, reasons)
    reasons += reportRejections(rawOutput, result, truncated, requireDelegatedCoverage, integration)
    val knownIncompleteReview =
      parent.hasIncompleteReviewWarning || integration.hasIncompleteReviewWarning ||
        result.coverage?.isCleanCoverage == false
    val finalVerdict = reportVerdict(rawOutput, census, parent, reasons, knownIncompleteReview)
    val register =
      renderRegister(registerBody(rawOutput, result, requireDelegatedCoverage), coverageLines, warnings, finalVerdict)
    return StandaloneReviewReport(
      rawOutput = rawOutput,
      registerOutput = register,
      verdict = finalVerdict,
      findings = result.mergeResult.findings,
      census = census,
      findingGroups = findingGroups,
      warnings = warnings,
      reviewWarnings = reviewWarnings,
      hasIncompleteReviewWarning = knownIncompleteReview,
      coverage = coverageLines.takeIf { it.isNotEmpty() }?.joinToString("\n"),
      diagnostics =
        ParallelReviewFindingParser.parse(rawOutput).let { parsed ->
          (
            parsed.citationDiagnostics.map { it.toString() } +
              parsed.rejections.map { it.toString() } +
              (parent.normalizations + integration.normalizations).map { it.toString() }
          )
        }
          .take(MAX_REPORT_DIAGNOSTICS)
          .map { it.take(MAX_DIAGNOSTIC_CHARS) },
      rejectionReasons = reasons.distinct(),
    )
  }

  private fun renderRegister(
    registerBody: String,
    coverageLines: List<String>,
    warnings: List<String>,
    finalVerdict: String?,
  ): String {
    return buildString {
      append(registerBody)
      coverageLines.filterNot { registerBody.contains(it) }.forEach { line ->
        appendLine()
        append(line)
      }
      warnings.filterNot { registerBody.contains(it) }.forEach { line ->
        appendLine()
        append(
          if (line.trimStart().startsWith("warning:", ignoreCase = true)) {
            line.trim()
          } else {
            "warning: ${line.trim()}"
          },
        )
      }
      if (finalVerdict != null) {
        appendLine()
        append("verdict: $finalVerdict")
      }
    }
  }

  private fun collectWarnings(
    parent: StandaloneReviewSourceCensus,
    integration: StandaloneReviewSourceCensus,
    result: ParallelCodeReviewResult,
    reasons: MutableList<String>,
  ): Triple<List<String>, List<String>, List<StandaloneReviewWarning>> {
    val coverageLines =
      (
        parent.coverage + integration.coverage +
          listOfNotNull(result.coverage?.render()?.trim()?.takeIf(String::isNotBlank))
      )
        .distinct()
    val typedCoverage = result.coverage?.render()?.trim()?.takeIf(String::isNotBlank)
    val coverageWarning = typedCoverage?.takeIf { result.coverage?.isCleanCoverage == false }
    val reviewEvidence =
      parent.reviewEvidence + integration.reviewEvidence +
        listOfNotNull(
          typedCoverage?.let {
            StandaloneReviewReviewEvidence(
              it,
              DELEGATED_COVERAGE,
              alreadyIncomplete = result.coverage?.isCleanCoverage == false,
            )
          },
        )
    val reviewWarnings =
      reviewEvidence.mapIndexed { index, evidence ->
        StandaloneReviewWarning(
          identity = "W-${(index + 1).toString().padStart(IDENTITY_DIGITS, '0')}",
          text = evidence.text,
          source = evidence.source,
          alreadyIncomplete = evidence.alreadyIncomplete,
        )
      }
    if (reviewWarnings.size > MAX_CENSUS_WARNINGS) {
      reasons += "report contains more warning blocks than the verifier identity format supports"
    }
    val warnings =
      (parent.warnings + integration.warnings + listOfNotNull(coverageWarning))
        .distinct()
    return Triple(coverageLines, warnings, reviewWarnings)
  }

  private fun reportVerdict(
    rawOutput: String,
    census: List<StandaloneReviewFinding>,
    parent: StandaloneReviewSourceCensus,
    reasons: List<String>,
    knownIncompleteReview: Boolean,
  ): String? {
    val token = declaredVerdict(rawOutput)
    val severe =
      census.any { candidate ->
        val finding = candidate.finding
        finding != null && !isCodeQualityFinding(finding.agentIds, finding.specialistSkillNames) &&
          finding.severity in setOf(ParallelReviewSeverity.BLOCKER, ParallelReviewSeverity.MAJOR)
      } ||
        parent.candidates.any { candidate ->
          (
            candidate.finding?.severity == ParallelReviewSeverity.BLOCKER ||
              candidate.finding?.severity == ParallelReviewSeverity.MAJOR
          ) &&
            candidate.finding.specialistSkillName?.let { isCodeQualityFinding(emptyList(), listOf(it)) } != true
        }
    val unparsedCandidates = reasons.any { "candidate" in it || "unclassified" in it || "malformed" in it }
    val requiresChanges = knownIncompleteReview || severe || unparsedCandidates || proseContradictsApproval(rawOutput)
    return if (token != null && requiresChanges) {
      FeatureTaskRuntimeVerdict.CHANGES_REQUESTED.wireValue
    } else {
      token
    }
  }

  private fun collectCandidates(
    rawOutput: String,
    result: ParallelCodeReviewResult,
    parent: StandaloneReviewSourceCensus,
    integration: StandaloneReviewSourceCensus,
  ): Triple<MutableList<StandaloneReviewSourceCandidate>, MutableList<String>, Map<String, List<Int>>> {
    val candidates = (parent.candidates + integration.candidates).toMutableList()
    val reasons = (parent.rejections + integration.rejections).toMutableList()
    if (
      candidates.filter { it.finding?.sourceFindingRef != null }
        .groupBy { it.producerId to it.finding?.sourceFindingRef }
        .values.any { it.size > 1 }
    ) {
      reasons += "a producer report assigns the same finding identity to multiple candidates"
    }
    val mergedTextGroups =
      result.mergeResult.formattedOutput.lineSequence()
        .mapNotNull { line ->
          MERGED_FINDING_LINE_PATTERN.matchEntire(line)?.let { match -> match.groupValues[1] to line }
        }.groupBy({ it.first }, { it.second })
    val mergedCandidateLineCount =
      result.mergeResult.formattedOutput.lineSequence().count { line ->
        line.trimStart().let { it.startsWith("- [F-") || it.startsWith("[F-") }
      }
    if (mergedCandidateLineCount != mergedTextGroups.values.sumOf { it.size }) {
      reasons += "merged review output contains an unrecognized finding row"
    }
    if (mergedTextGroups.values.any { it.size != 1 }) {
      reasons += "merged review output reuses a finding identity"
    }
    val mergedTexts =
      mergedTextGroups
        .mapNotNull { (identity, texts) -> texts.singleOrNull()?.let { identity to it } }
        .toMap()
    if (mergedTexts.keys.any { ref -> result.mergeResult.findings.none { it.fNumber == ref } }) {
      reasons += "merged review output contains findings missing from its typed merge result"
    }
    val memberships = reconcileMergedFindings(result.mergeResult.findings, mergedTexts, candidates, reasons)
    val explicitClean = StandaloneReviewReportSourceCensusBuilder.hasExplicitCleanDeclaration(rawOutput)
    if (parent.candidates.isEmpty() && !explicitClean) {
      reasons += "parent report does not explicitly declare an empty findings census"
    }
    if (parent.candidates.isNotEmpty() && explicitClean) {
      reasons += "parent report declares no findings alongside admitted candidates"
    }

    return Triple(candidates, reasons, memberships)
  }

  private fun findingGroups(
    census: List<StandaloneReviewFinding>,
    findings: List<ParallelReviewMergedFinding>,
    mergedMemberships: Map<String, List<Int>>,
  ): List<StandaloneReviewFindingGroup> {
    val claimed = mutableSetOf<Int>()
    val groups =
      mergedMemberships.mapNotNull { (identity, indices) ->
        val members = indices.distinct().mapNotNull(census::getOrNull)
        if (members.isEmpty()) {
          null
        } else {
          claimed += indices
          StandaloneReviewFindingGroup(
            identity,
            findings.firstOrNull { it.fNumber == identity },
            members,
          )
        }
      }
    val remaining = census.withIndex().filterNot { it.index in claimed }
    val identities = groups.mapTo(mutableSetOf(), StandaloneReviewFindingGroup::identity)
    return groups +
      remaining.map { (_, finding) ->
        var identity = finding.identity
        var next = 1
        while (identity in identities) {
          identity = "F-${next.toString().padStart(IDENTITY_DIGITS, '0')}"
          next += 1
        }
        identities += identity
        StandaloneReviewFindingGroup(identity, finding.finding, listOf(finding))
      }
  }

  private fun integrationCandidates(outcome: ReviewIntegrationPassOutcome?): StandaloneReviewSourceCensus {
    if (outcome == null || outcome.terminalOutcome == ReviewIntegrationTerminalOutcome.SKIPPED_NOT_APPLICABLE) {
      return StandaloneReviewSourceCensus(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), false)
    }
    if (outcome.rawOutput.isBlank()) {
      return StandaloneReviewSourceCensus(
        candidates = emptyList(),
        coverage = emptyList(),
        warnings = emptyList(),
        reviewEvidence = emptyList(),
        rejections =
          buildList {
            if (outcome.outputTruncated) add("delegated integration output was truncated")
            if (outcome.completed) add("completed delegated integration report output is blank")
            if (outcome.findings.isNotEmpty()) {
              add("delegated integration findings have no retained source text")
            }
          },
        hasIncompleteReviewWarning = false,
      )
    }
    val parsed =
      StandaloneReviewReportSourceCensusBuilder.build(
        outcome.rawOutput,
        INTEGRATION_REPORT,
        strictContext = true,
      )
    val matches = mutableListOf<StandaloneReviewSourceCandidate>()
    val remaining = parsed.candidates.toMutableList()
    outcome.findings.forEach { finding ->
      val matching =
        remaining.filter { source ->
          source.finding?.let { sameSourceFinding(it, finding) } == true
        }
      when (matching.size) {
        1 -> {
          val source = matching.single()
          remaining.remove(source)
          matches += source.copy(finding = finding, producerId = finding.specialistSkillName)
        }
        0 -> Unit
        else -> Unit
      }
    }
    val reasons = integrationRejections(outcome, parsed, matches, remaining)
    return parsed.copy(candidates = matches, rejections = reasons)
  }

  private fun integrationRejections(
    outcome: ReviewIntegrationPassOutcome,
    parsed: StandaloneReviewSourceCensus,
    matches: List<StandaloneReviewSourceCandidate>,
    remaining: List<StandaloneReviewSourceCandidate>,
  ): MutableList<String> {
    val reasons = parsed.rejections.toMutableList()
    val explicitClean = StandaloneReviewReportSourceCensusBuilder.hasExplicitCleanDeclaration(outcome.rawOutput)
    if (outcome.completed && matches.isEmpty() && !explicitClean) {
      reasons += "completed delegated integration report does not explicitly declare an empty findings census"
    }
    if (matches.isNotEmpty() && explicitClean) {
      reasons += "delegated integration report declares no findings alongside admitted candidates"
    }
    if (remaining.isNotEmpty() || matches.size != outcome.findings.size) {
      reasons += "delegated integration report contains findings outside its admitted cross-commit results"
    }
    if (outcome.outputTruncated) reasons += "delegated integration output was truncated"
    if (parsed.candidates.any { it.finding == null }) {
      reasons += "delegated integration report contains prose candidates without cross-commit identity"
    }
    return reasons
  }

  private fun reconcileMergedFindings(
    merged: List<ParallelReviewMergedFinding>,
    mergedTexts: Map<String, String>,
    candidates: MutableList<StandaloneReviewSourceCandidate>,
    reasons: MutableList<String>,
  ): Map<String, List<Int>> {
    val claimedCandidates = mutableSetOf<Int>()
    val memberships = linkedMapOf<String, List<Int>>()
    merged.forEach { finding ->
      val referenced = referencedCandidates(finding, candidates)
      val unresolvedRefs =
        finding.sourceFindingRefs.filter { ref -> referenced.none { it.value.finding?.sourceFindingRef == ref } }
      val referencedIndices = referenced.map { it.index }
      val reusedCandidate = referencedIndices.any { it in claimedCandidates }
      if (reusedCandidate) {
        reasons += "a source finding candidate is reused by multiple merged findings"
      }
      claimedCandidates.addAll(referencedIndices)
      when {
        reusedCandidate -> Unit
        unresolvedRefs.isNotEmpty() ->
          reasons += "merged finding has source references that cannot be reconciled to admitted report text"
        referenced.isEmpty() ->
          mergedTexts[finding.fNumber]?.takeIf { finding.sourceFindingRefs.isEmpty() }?.let { text ->
            candidates +=
              StandaloneReviewSourceCandidate(
                text,
                MERGED_REPORT,
                finding.asRawFinding(),
              )
            memberships[finding.fNumber] = listOf(candidates.lastIndex)
          } ?: run {
            reasons += "merged finding has no retained source presentation text"
          }
        referenced.size == 1 -> {
          val index = referenced.single().index
          candidates[index] = candidates[index].copy(finding = finding.asRawFinding())
          memberships[finding.fNumber] = listOf(index)
        }
        else -> memberships[finding.fNumber] = referencedIndices
      }
    }
    return memberships
  }

  private fun referencedCandidates(
    finding: ParallelReviewMergedFinding,
    candidates: List<StandaloneReviewSourceCandidate>,
  ): List<IndexedValue<StandaloneReviewSourceCandidate>> {
    return if (finding.sourceFindingRefs.isEmpty()) {
      candidates.withIndex().filter { (_, candidate) ->
        candidate.source != MERGED_REPORT &&
          candidate.finding?.let { sameSourceFinding(it, finding.asRawFinding()) } == true &&
          (candidate.producerId == null || candidate.producerId in finding.agentIds)
      }
    } else {
      candidates.withIndex().filter { (_, candidate) ->
        candidate.source != MERGED_REPORT &&
          candidate.finding?.sourceFindingRef?.let { it in finding.sourceFindingRefs } == true &&
          (candidate.producerId == null || candidate.producerId in finding.agentIds)
      }
    }
  }

  private fun reportRejections(
    rawOutput: String,
    result: ParallelCodeReviewResult,
    truncated: Boolean,
    requireDelegatedCoverage: Boolean,
    integration: StandaloneReviewSourceCensus,
  ): List<String> =
    buildList {
      if (rawOutput.isBlank()) add("report output is blank")
      if (truncated || result.reportTruncated) add("report output was truncated")
      if (declaredVerdict(rawOutput) == null) {
        add("report must declare one verdict: approved or verdict: changes_requested")
      }
      if (requireDelegatedCoverage) addAll(delegatedRejections(result, integration))
      if (hasMalformedFindingIdentifier(rawOutput)) add("report contains a malformed finding identity")
      if (result.rejectedCandidateCount > 0) add("report contains rejected finding candidates")
    }

  private fun delegatedRejections(
    result: ParallelCodeReviewResult,
    integrationCensus: StandaloneReviewSourceCensus,
  ): List<String> =
    buildList {
      result.analysisStageFailures.forEach { failure ->
        val finding = failure.findingRef?.let { "finding $it: " }.orEmpty()
        add(
          "delegated ${failure.stage.wireValue} analysis failed (${failure.reason.wireValue}): " +
            "$finding${failure.detail}",
        )
      }
      if (!result.lane1.success) add(result.lane1.failureReason ?: "delegated parent execution failed")
      if (result.lane1.reviewDisposition != ReviewLaneReviewDisposition.COMPLETE) {
        add("delegated parent report disposition is incomplete")
      }
      if (result.coverage == null) add("delegated review coverage is missing")
      val integration = result.integration
      integration?.let { addAll(integrationCensus.rejections) }
      when {
        integration == null -> add("delegated integration stage is missing")
        integration.completed -> Unit
        integration.terminalOutcome == ReviewIntegrationTerminalOutcome.SKIPPED_NOT_APPLICABLE &&
          !integration.skipReason.isNullOrBlank() -> Unit
        else -> add(integration.failureReason ?: "delegated integration stage is incomplete")
      }
    }

  private fun declaredVerdict(rawOutput: String): String? =
    rawOutput.lineSequence()
      .map { it.trim().lowercase() }
      .filter { it.startsWith(VERDICT_PREFIX) }
      .map { it.removePrefix(VERDICT_PREFIX).trim() }
      .toSet()
      .singleOrNull()
      ?.takeIf { it in CANONICAL_VERDICTS }

  private fun proseContradictsApproval(rawOutput: String): Boolean =
    rawOutput.lineSequence().any { line ->
      isIncompleteReviewWarning(line) || SEVERE_PROSE_FINDING_PATTERN.containsMatchIn(line)
    }

  private fun registerBody(
    rawOutput: String,
    result: ParallelCodeReviewResult,
    requireDelegatedCoverage: Boolean,
  ): String {
    if (!requireDelegatedCoverage && rawOutput.isNotBlank()) {
      return rawOutput.lineSequence()
        .filterNot { it.trim().lowercase().startsWith(VERDICT_PREFIX) }
        .joinToString("\n")
        .trim()
    }
    return ParallelReviewMerger.formattedOutput(result.mergeResult.findings).ifBlank { NO_FINDINGS }
  }

  private fun hasMalformedFindingIdentifier(output: String): Boolean =
    output.lineSequence().any { line ->
      val candidate = line.trimStart().removePrefix("-").trimStart()
      candidate.startsWith("[F-") && !ParallelReviewFindingParser.findingCandidatePattern.containsMatchIn(candidate)
    }
}

private fun requiresChangesWhenConfirmed(
  candidate: StandaloneReviewSourceCandidate,
  finding: ParallelReviewMergedFinding?,
  candidates: List<StandaloneReviewSourceCandidate>,
): Boolean {
  val nextMetadata = followingCandidateMetadata(candidate, candidates)
  val severity =
    finding?.severity ?: candidate.finding?.severity
      ?: explicitProseSeverity(listOfNotNull(candidate.text, nextMetadata).joinToString("\n"))
  if (severity == null) return true
  if (severity !in setOf(ParallelReviewSeverity.BLOCKER, ParallelReviewSeverity.MAJOR)) return false
  val isCodeQuality =
    finding?.let { isCodeQualityFinding(it.agentIds, it.specialistSkillNames) }
      ?: candidate.finding?.specialistSkillName?.let { isCodeQualityFinding(emptyList(), listOf(it)) }
      ?: explicitProseSpecialists(listOfNotNull(candidate.text, nextMetadata).joinToString("\n")).let { names ->
        names.size == 1 && isCodeQualityFinding(emptyList(), names)
      }
  return !isCodeQuality
}

private fun followingCandidateMetadata(
  candidate: StandaloneReviewSourceCandidate,
  candidates: List<StandaloneReviewSourceCandidate>,
): String? {
  val currentLine = candidate.linePosition ?: return null
  var nextLine = currentLine + 1
  val followingCandidates =
    candidates
      .filter {
        it.source == candidate.source && it.producerId == candidate.producerId &&
          it.linePosition?.let { line -> line >= nextLine } == true
      }
      .sortedBy { it.linePosition }
  val metadata =
    buildList {
      for (following in followingCandidates) {
        if (following.linePosition != nextLine || !isCandidateMetadata(following.text)) break
        add(following.text)
        nextLine++
      }
    }
  return metadata.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

private fun isCandidateMetadata(text: String): Boolean =
  EXPLICIT_SEVERITY_METADATA_PATTERN.containsMatchIn(text) ||
    EXPLICIT_SPECIALIST_METADATA_PATTERN.containsMatchIn(text)

private fun explicitProseSeverity(text: String): ParallelReviewSeverity? {
  val labels =
    text.lineSequence()
      .flatMap { line ->
        sequenceOf(
          EXPLICIT_SEVERITY_PATTERN.find(line),
          EXPLICIT_SEVERITY_LABEL_PATTERN.find(line),
          BRACKET_SEVERITY_PATTERN.find(line),
        )
          .mapNotNull { it?.groupValues?.get(1) }
      }
      .mapNotNull { label ->
        if (label.equals("low", ignoreCase = true)) {
          ParallelReviewSeverity.MINOR
        } else {
          ParallelReviewSeverity.entries.firstOrNull { it.displayName.equals(label, ignoreCase = true) }
        }
      }
      .distinct()
      .toList()
  return labels.singleOrNull()
}

private fun explicitProseSpecialists(text: String): List<String> =
  EXPLICIT_SPECIALIST_PATTERN.findAll(text).map { it.groupValues[1] }.distinct().toList()

private fun mergeFinding(finding: ParallelReviewRawFinding): ParallelReviewMergedFinding? =
  ParallelReviewMerger.merge(
    ParallelReviewLaneResult(finding.specialistSkillName ?: "standalone", listOf(finding)),
    ParallelReviewLaneResult(finding.specialistSkillName ?: "standalone", emptyList()),
  ).findings.singleOrNull()

private fun ParallelReviewMergedFinding.asRawFinding(): ParallelReviewRawFinding =
  ParallelReviewRawFinding(
    severity = severity,
    confidence = confidence,
    location = location,
    description = description,
    specialistSkillName = specialistSkillNames.firstOrNull(),
    originLayerChains = originLayerChains,
    repositoryPath = repositoryPath,
    line = line,
    commitShas = commitShas,
    claimVerdict = claimVerdict,
    scopeDisposition = scopeDisposition,
    citations = citations,
    severityAdjustment = severityAdjustment,
    sourceFindingRef = sourceFindingRefs.singleOrNull(),
  )

private fun sameSourceFinding(
  first: ParallelReviewRawFinding,
  second: ParallelReviewRawFinding,
): Boolean =
  first.severity == second.severity &&
    first.description == second.description &&
    (
      first.location == second.location ||
        (first.repositoryPath != null && first.repositoryPath == second.repositoryPath && first.line == second.line)
    )

private const val PARENT_REPORT = "parent raw report"
private const val INTEGRATION_REPORT = "delegated integration report"
private const val MERGED_REPORT = "merged review finding"
private const val VERDICT_PREFIX = "verdict:"
private const val NO_FINDINGS = "NO_FINDINGS"
private const val IDENTITY_DIGITS = 3
private const val MAX_CENSUS_FINDINGS = 999
private const val MAX_CENSUS_WARNINGS = 999
private const val DELEGATED_COVERAGE = "delegated coverage"
private const val MAX_REPORT_DIAGNOSTICS = 20
private const val MAX_DIAGNOSTIC_CHARS = 200
private val CANONICAL_VERDICTS =
  setOf(FeatureTaskRuntimeVerdict.APPROVED.wireValue, FeatureTaskRuntimeVerdict.CHANGES_REQUESTED.wireValue)
private val SEVERE_PROSE_FINDING_PATTERN =
  Regex(
    "\\[(?:blocker|critical|major|high)\\b|" +
      "(?<!\\bno )(?<!\\bwithout )\\b(?:blocker|critical|major|high)[- ]severity\\b|" +
      "\\bseverity\\s*[:=]?\\s*(?:blocker|critical|major|high)\\b",
    RegexOption.IGNORE_CASE,
  )
private val EXPLICIT_SEVERITY_PATTERN =
  Regex(
    "^\\s*(?:(?:[-*+]|\\d+[.)])\\s+)?(?:severity\\s*[:=]\\s*|\\[)?" +
      "(blocker|major|minor|nit|low)\\b",
    RegexOption.IGNORE_CASE,
  )
private val EXPLICIT_SEVERITY_LABEL_PATTERN =
  Regex("\\bseverity\\s*[:=]\\s*(blocker|major|minor|nit|low)\\b", RegexOption.IGNORE_CASE)
private val BRACKET_SEVERITY_PATTERN =
  Regex("\\[(blocker|major|minor|nit|low)(?:\\s*[,\\]])", RegexOption.IGNORE_CASE)
private val EXPLICIT_SEVERITY_METADATA_PATTERN =
  Regex("^\\s*severity\\s*[:=]\\s*(?:blocker|major|minor|nit|low)\\b", RegexOption.IGNORE_CASE)
private val EXPLICIT_SPECIALIST_METADATA_PATTERN =
  Regex("^\\s*specialist\\s*[:=]\\s*[a-z0-9-]+\\b", RegexOption.IGNORE_CASE)
private val EXPLICIT_SPECIALIST_PATTERN = Regex("\\bspecialist\\s*[:=]\\s*([a-z0-9-]+)\\b", RegexOption.IGNORE_CASE)
private val MERGED_FINDING_LINE_PATTERN = Regex("^\\s*(?:-\\s*)?\\[(F-\\d{3})]\\s+.+$")
