package skillbill.review.parallel

import skillbill.review.context.model.hunk.structuredString
import skillbill.review.model.ParallelReviewLaneResult
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.review.model.ParallelReviewMergedFinding
import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingRegisterOutcome
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewLaneFindingVerdict
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustment
import skillbill.review.parsing.ReviewFindingActionability

object ParallelReviewMerger {
  fun merge(
    lane1: ParallelReviewLaneResult,
    lane2: ParallelReviewLaneResult,
    integration: ParallelReviewLaneResult? = null,
  ): ParallelReviewMergeResult {
    val (failureCandidates, codeQualityCandidates) = mergeCandidates(lane1, lane2, integration)

    val sorted =
      failureCandidates.sortedWith(
        compareBy<MergedCandidate> { it.severity.ordinal }
          .thenBy { if (it.isCoalesced) 0 else 1 }
          .thenBy { it.firstAppearance },
      )
    val orderedCodeQuality =
      codeQualityCandidates
        .sortedWith(
          compareByDescending<MergedCandidate> { confidenceRank(it.confidence) }.thenBy { it.firstAppearance },
        )
        .take(CODE_QUALITY_FINDING_CAP)

    val mergedFindings =
      (sorted + orderedCodeQuality).mapIndexed { index, candidate ->
        ParallelReviewMergedFinding(
          fNumber = "F-%03d".format(index + 1),
          agentIds = candidate.agentIds,
          severity = candidate.severity,
          confidence = candidate.confidence,
          location = candidate.location,
          description = candidate.description,
          specialistSkillNames = candidate.specialistSkillNames,
          originLayerChains = candidate.originLayerChains,
          repositoryPath = candidate.repositoryPath,
          line = candidate.line,
          commitShas = candidate.commitShas,
          claimVerdict = candidate.claimVerdict,
          scopeDisposition = candidate.scopeDisposition,
          citations = candidate.citations,
          severityAdjustment = candidate.severityAdjustment,
          sourceVerdicts = candidate.sourceVerdicts,
          sourceFindingRefs = candidate.sourceFindingRefs,
        )
      }

    return ParallelReviewMergeResult(
      findings = mergedFindings,
      formattedOutput = formattedOutput(mergedFindings),
    )
  }

  fun withRecordedVerdicts(
    result: ParallelReviewMergeResult,
    verdicts: List<ReviewFindingVerdict>,
  ): ParallelReviewMergeResult {
    if (verdicts.isEmpty()) return result
    val byRef = verdicts.groupBy(ReviewFindingVerdict::findingRef)
    val findings =
      result.findings.map { finding ->
        val overlay =
          ReviewFindingActionability.recordedFields(byRef[finding.fNumber].orEmpty())
            ?: return@map finding
        finding.copy(
          claimVerdict = overlay.claimVerdict,
          scopeDisposition = overlay.scopeDisposition,
          citations = overlay.citations,
          severityAdjustment = overlay.severityAdjustment,
        )
      }
    return ParallelReviewMergeResult(findings, formattedOutput(findings))
  }

  fun formattedOutput(findings: List<ParallelReviewMergedFinding>): String {
    val (codeQualityFindings, failureFindings) =
      findings.partition { finding ->
        isCodeQualityFinding(finding.agentIds, finding.specialistSkillNames)
      }
    val failureOutput =
      if (failureFindings.none(ParallelReviewMergedFinding::hasRecordedVerdict)) {
        failureFindings.joinToString("\n") { finding -> formatFinding(finding) }
      } else {
        val grouped =
          failureFindings.groupBy { finding ->
            ReviewFindingActionability.registerOutcome(finding.claimVerdict, finding.scopeDisposition)
          }
        buildString {
          var first = true
          ReviewFindingRegisterOutcome.entries.forEach { outcome ->
            val items = grouped[outcome].orEmpty()
            if (items.isEmpty()) return@forEach
            if (!first) append('\n')
            first = false
            append(outcome.header)
            append('\n')
            append(items.joinToString("\n") { finding -> formatFinding(finding) })
          }
        }
      }
    if (codeQualityFindings.isEmpty()) return failureOutput
    val codeQualityOutput = codeQualityFindings.joinToString("\n") { formatFinding(it, codeQuality = true) }
    return listOf(failureOutput, CODE_QUALITY_HEADING, codeQualityOutput)
      .filter(String::isNotEmpty)
      .joinToString("\n")
  }

  private fun mergeCandidates(
    lane1: ParallelReviewLaneResult,
    lane2: ParallelReviewLaneResult,
    integration: ParallelReviewLaneResult?,
  ): Pair<List<MergedCandidate>, List<MergedCandidate>> {
    val allEntries = mutableListOf<FindingEntry>()
    var appearanceOrder = 0
    lane1.findings.forEach { f -> allEntries += FindingEntry(f, lane1.agentId, appearanceOrder++) }
    lane2.findings.forEach { f -> allEntries += FindingEntry(f, lane2.agentId, appearanceOrder++) }

    integration?.findings?.forEach { f -> allEntries += FindingEntry(f, integration.agentId, appearanceOrder++) }

    val (codeQualityEntries, failureEntries) =
      allEntries.partition { entry ->
        isCodeQualityFinding(listOf(entry.agentId), listOfNotNull(entry.finding.specialistSkillName))
      }
    val failureCandidates = mergeEntryGroup(failureEntries, codeQuality = false)
    val codeQualityCandidates =
      mergeEntryGroup(
        codeQualityEntries.map { entry ->
          entry.copy(finding = entry.finding.copy(severity = ParallelReviewSeverity.MINOR))
        },
        codeQuality = true,
      )
    return failureCandidates to codeQualityCandidates
  }

  private fun mergeEntryGroup(
    entries: List<FindingEntry>,
    codeQuality: Boolean,
  ): List<MergedCandidate> {
    val clusters = mutableListOf<ClusterHead>()
    entries.forEach { entry ->
      val entryFilePath = entry.finding.repositoryPath ?: filePathOf(entry.finding.location)
      val entryTokens = tokens(entry.finding.description)
      val cluster =
        clusters.firstOrNull { head ->
          head.representativeFilePath == entryFilePath &&
            jaccard(head.representativeTokens, entryTokens) > FUZZY_DEDUP_THRESHOLD
        }
      if (cluster != null) {
        cluster.entries += entry
      } else {
        clusters += ClusterHead(mutableListOf(entry), entryFilePath, entryTokens)
      }
    }

    return clusters.map { cluster -> toCandidate(cluster, codeQuality) }
  }

  private fun formatFinding(
    finding: ParallelReviewMergedFinding,
    codeQuality: Boolean = false,
  ): String {
    val agentLabel = finding.agentIds.joinToString(", ")
    val specialistAttribution = if (codeQuality) codeQualityAttribution(finding) else ""
    val provenance =
      buildList {
        if (finding.specialistSkillNames.isNotEmpty()) {
          add("specialists=${finding.specialistSkillNames.joinToString(",")}")
        }
        if (finding.originLayerChains.isNotEmpty()) {
          add("origins=${finding.originLayerChains.joinToString(",") { it.joinToString("->") }}")
        }
      }.joinToString("; ").let { if (it.isBlank()) "" else " | $it" }
    val structuredLocation =
      if (finding.repositoryPath != null && finding.line != null) {
        "path=${structuredString(finding.repositoryPath)} | line=${finding.line}"
      } else {
        finding.location
      }
    val commitAttribution =
      if (finding.commitShas.isNotEmpty()) {
        "commits=${finding.commitShas.joinToString(",")} | "
      } else {
        ""
      }
    val claimLine =
      "- [${finding.fNumber}] " +
        (if (codeQuality) "" else "[$agentLabel] ") +
        "${finding.severity.displayName} | ${finding.confidence} | " +
        "$specialistAttribution$commitAttribution$structuredLocation | ${finding.description}$provenance"
    val structuredFields = verdictFields(finding)
    return if (structuredFields.isEmpty()) claimLine else "$claimLine | ${structuredFields.joinToString(" | ")}"
  }

  private fun verdictFields(finding: ParallelReviewMergedFinding): List<String> =
    buildList {
      finding.claimVerdict?.let { add("claim_verdict=${it.wireValue}") }
      finding.scopeDisposition?.let { add("scope_disposition=${it.wireValue}") }
      if (finding.citations.isNotEmpty()) {
        add("citations=${finding.citations.joinToString(",") { "${it.path}:${it.line}" }}")
      }
      finding.severityAdjustment?.let { adjustment ->
        add("severity_adjustment=${adjustment.direction.wireValue}: ${adjustment.justification}")
      }
    }

  private fun codeQualityAttribution(finding: ParallelReviewMergedFinding): String {
    val specialist =
      finding.specialistSkillNames.firstOrNull { skillName ->
        isCodeQualityFinding(emptyList(), listOf(skillName))
      } ?: finding.agentIds.first { agentId -> isCodeQualityFinding(listOf(agentId), emptyList()) }
    return "specialist=$specialist | "
  }

  private fun ParallelReviewRawFinding.lacksVerdictOverlay(): Boolean =
    claimVerdict == null &&
      scopeDisposition == null &&
      severityAdjustment == null &&
      citations.isEmpty()

  private fun toCandidate(
    head: ClusterHead,
    codeQuality: Boolean,
  ): MergedCandidate {
    val entries = head.entries
    val coalesced = entries.map { it.agentId }.distinct().size > 1

    val primary =
      if (codeQuality) {
        entries.minWith(
          compareByDescending<FindingEntry> { confidenceRank(it.finding.confidence) }
            .thenBy { it.appearanceOrder },
        )
      } else {
        entries.minWith(compareBy({ it.finding.severity.ordinal }, { it.appearanceOrder }))
      }
    val firstEntry = entries.minByOrNull { it.appearanceOrder }!!
    val displayEntry = if (codeQuality) primary else firstEntry
    val sourceVerdicts =
      entries.mapNotNull { entry ->
        val finding = entry.finding
        if (finding.lacksVerdictOverlay()) {
          null
        } else {
          ReviewLaneFindingVerdict(
            laneId = entry.agentId,
            claimVerdict = finding.claimVerdict,
            scopeDisposition = finding.scopeDisposition,
            citations = finding.citations,
            severityAdjustment = finding.severityAdjustment,
          )
        }
      }
    val claimVerdict =
      sourceVerdicts.map { it.claimVerdict }.reduceOrNull(
        ReviewFindingActionability::conservativeClaimVerdict,
      )
    val scopeDisposition =
      sourceVerdicts.map { it.scopeDisposition }.reduceOrNull(
        ReviewFindingActionability::conservativeScopeDisposition,
      )
    return MergedCandidate(
      agentIds = entries.map { it.agentId }.distinct(),
      severity = primary.finding.severity,
      confidence = primary.finding.confidence,
      location = displayEntry.finding.location,
      description = displayEntry.finding.description,
      isCoalesced = coalesced,
      firstAppearance = displayEntry.appearanceOrder,
      specialistSkillNames =
        entries.flatMap { entry ->
          val specialist = entry.finding.specialistSkillName
          if (codeQuality && !isCodeQualityFinding(emptyList(), listOfNotNull(specialist))) {
            listOfNotNull(entry.agentId, specialist)
          } else {
            listOfNotNull(specialist)
          }
        }.distinct(),
      originLayerChains = entries.flatMap { it.finding.originLayerChains }.distinct(),
      repositoryPath = displayEntry.finding.repositoryPath,
      line = displayEntry.finding.line,
      commitShas = entries.sortedBy { it.appearanceOrder }.flatMap { it.finding.commitShas }.distinct(),
      claimVerdict = claimVerdict,
      scopeDisposition = scopeDisposition,
      citations = sourceVerdicts.flatMap { it.citations }.distinct(),
      severityAdjustment = sourceVerdicts.mapNotNull { it.severityAdjustment }.firstOrNull(),
      sourceVerdicts = sourceVerdicts,
      sourceFindingRefs = entries.mapNotNull { it.finding.sourceFindingRef }.distinct(),
    )
  }

  private const val FUZZY_DEDUP_THRESHOLD = 0.6
  private const val CODE_QUALITY_FINDING_CAP = 5
  private const val CODE_QUALITY_HEADING = "#### Code Quality (non-blocking)"
  private const val HIGH_CONFIDENCE_RANK = 3

  private fun confidenceRank(confidence: String): Int =
    when (confidence) {
      "High" -> HIGH_CONFIDENCE_RANK
      "Medium" -> 2
      "Low" -> 1
      else -> 0
    }

  private fun filePathOf(location: String): String = location.substringBeforeLast(":").trim()

  private val TOKEN_DELIMITER = Regex("[^a-z0-9]+")

  private fun tokens(description: String): Set<String> =
    description.lowercase().split(TOKEN_DELIMITER).filter { it.isNotEmpty() }.toSet()

  private fun jaccard(
    a: Set<String>,
    b: Set<String>,
  ): Double {
    val union = a union b
    if (union.isEmpty()) return 1.0
    return (a intersect b).size.toDouble() / union.size.toDouble()
  }

  private data class FindingEntry(
    val finding: ParallelReviewRawFinding,
    val agentId: String,
    val appearanceOrder: Int,
  )

  private data class ClusterHead(
    val entries: MutableList<FindingEntry>,
    val representativeFilePath: String,
    val representativeTokens: Set<String>,
  )

  private data class MergedCandidate(
    val agentIds: List<String>,
    val severity: ParallelReviewSeverity,
    val confidence: String,
    val location: String,
    val description: String,
    val isCoalesced: Boolean,
    val firstAppearance: Int,
    val specialistSkillNames: List<String>,
    val originLayerChains: List<List<String>>,
    val repositoryPath: String?,
    val line: Int?,
    val commitShas: List<String>,
    val claimVerdict: ReviewClaimVerdict?,
    val scopeDisposition: ReviewScopeDisposition?,
    val citations: List<ReviewFindingCitation>,
    val severityAdjustment: ReviewSeverityAdjustment?,
    val sourceVerdicts: List<ReviewLaneFindingVerdict>,
    val sourceFindingRefs: List<String>,
  )
}
