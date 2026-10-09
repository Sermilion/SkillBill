
package skillbill.review.parallel

import skillbill.review.model.ParallelReviewLaneResult
import skillbill.review.model.ParallelReviewMergeResult
import skillbill.review.model.ParallelReviewRawFinding
import skillbill.review.model.ParallelReviewSeverity
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import skillbill.review.model.ReviewFindingVerdict
import skillbill.review.model.ReviewScopeDisposition
import skillbill.review.model.ReviewSeverityAdjustment
import skillbill.review.model.ReviewSeverityAdjustmentDirection
import skillbill.review.model.ReviewStage
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParallelReviewMergerTest {
  @Test
  fun `code quality findings stay separate from failure dedup and verdict groups`() {
    val failure =
      ParallelReviewRawFinding(
        severity = ParallelReviewSeverity.MAJOR,
        confidence = "High",
        location = "Shared.kt:10",
        description = "Shared contract can lose the committed state during concurrent updates",
        repositoryPath = "Shared.kt",
        line = 10,
      )
    val specialistQuality =
      failure.copy(
        severity = ParallelReviewSeverity.BLOCKER,
        confidence = "Medium",
        description = "Shared contract can lose committed state during concurrent updates",
        specialistSkillName = "bill-generic-code-review-code-quality",
      )
    val laneIdentityQuality =
      ParallelReviewRawFinding(
        severity = ParallelReviewSeverity.MAJOR,
        confidence = "Low",
        location = "Other.kt:20",
        description = "Prefer a smaller helper for this conversion",
        repositoryPath = "Other.kt",
        line = 20,
      )
    val qualityLane = ParallelReviewLaneResult("generic-code-quality", listOf(laneIdentityQuality))
    val failureOnly =
      ParallelReviewMerger.merge(
        ParallelReviewLaneResult("failure-lane", listOf(failure)),
        ParallelReviewLaneResult("specialist-lane", emptyList()),
      )
    val merged =
      ParallelReviewMerger.merge(
        ParallelReviewLaneResult("failure-lane", listOf(failure)),
        ParallelReviewLaneResult("specialist-lane", listOf(specialistQuality)),
        qualityLane,
      )

    assertEquals(
      failureOnly.findings,
      merged.findings.filterNot { finding ->
        isCodeQualityFinding(finding.agentIds, finding.specialistSkillNames)
      },
    )
    assertEquals(failureOnly.formattedOutput, merged.formattedOutput.substringBefore("\n#### Code Quality"))
    assertEquals(3, merged.findings.size)
    assertContains(merged.formattedOutput, "#### Code Quality (non-blocking)")
    assertContains(merged.formattedOutput, "specialist=bill-generic-code-review-code-quality")
    assertContains(merged.formattedOutput, "specialist=generic-code-quality")
    assertEquals(listOf("generic-code-quality"), merged.findings.last().specialistSkillNames)
    val parsedQuality =
      ParallelReviewFindingParser.parse(merged.formattedOutput.substringAfter("#### Code Quality (non-blocking)"))
    assertTrue(parsedQuality.rejections.isEmpty())
    assertEquals(
      listOf("bill-generic-code-review-code-quality", "generic-code-quality"),
      parsedQuality.findings.map { it.specialistSkillName },
    )

    assertFailureVerdictGroupBeforeCodeQuality(merged)
  }

  private fun assertFailureVerdictGroupBeforeCodeQuality(merged: ParallelReviewMergeResult) {
    val withVerdict =
      ParallelReviewMerger.withRecordedVerdicts(
        merged,
        listOf(
          ReviewFindingVerdict(
            stage = ReviewStage.VERIFICATION,
            findingRef = "F-001",
            claimVerdict = ReviewClaimVerdict.CONFIRMED,
            recordedAt = "2026-10-09T00:00:00Z",
          ),
        ),
      )
    assertTrue(
      withVerdict.formattedOutput.indexOf("#### Code Quality (non-blocking)") >
        withVerdict.formattedOutput.indexOf("Actionable"),
    )
    assertContains(withVerdict.formattedOutput, "specialist=generic-code-quality")
  }

  @Test
  fun `code quality findings normalize severity and cap after confidence ordering`() {
    val failure =
      ParallelReviewRawFinding(
        severity = ParallelReviewSeverity.MINOR,
        confidence = "Low",
        location = "Failure.kt:1",
        description = "Failure finding",
        repositoryPath = "Failure.kt",
        line = 1,
      )
    val quality =
      (1..7).map { index ->
        ParallelReviewRawFinding(
          severity = if (index == 1) ParallelReviewSeverity.MAJOR else ParallelReviewSeverity.NIT,
          confidence = listOf("Low", "High", "Medium", "High", "Low", "Medium", "High")[index - 1],
          location = "Quality$index.kt:1",
          description = "Quality observation $index",
          specialistSkillName = "bill-generic-code-review-code-quality",
          repositoryPath = "Quality$index.kt",
          line = 1,
        )
      }
    val result =
      ParallelReviewMerger.merge(
        ParallelReviewLaneResult("failure-lane", listOf(failure)),
        ParallelReviewLaneResult("generic-code-review-code-quality", quality),
      )
    val qualityFindings =
      result.findings.filter { finding ->
        isCodeQualityFinding(finding.agentIds, finding.specialistSkillNames)
      }

    assertEquals(5, qualityFindings.size)
    assertTrue(qualityFindings.all { it.severity == ParallelReviewSeverity.MINOR })
    assertEquals(listOf("High", "High", "High", "Medium", "Medium"), qualityFindings.map { it.confidence })
    assertEquals(
      listOf("Quality2.kt", "Quality4.kt", "Quality7.kt", "Quality3.kt", "Quality6.kt"),
      qualityFindings.map { it.repositoryPath },
    )
    assertEquals("F-001", result.findings.first().fNumber)
    assertEquals("F-002", qualityFindings.first().fNumber)
    assertTrue(
      result.formattedOutput.indexOf("Failure finding") <
        result.formattedOutput.indexOf("#### Code Quality (non-blocking)"),
    )
    assertTrue(
      result.formattedOutput.indexOf("#### Code Quality (non-blocking)") <
        result.formattedOutput.indexOf("Quality2.kt"),
    )
  }

  @Test
  fun `code quality fuzzy dedup uses confidence representative after minor normalization`() {
    val lowerConfidenceMajor =
      ParallelReviewRawFinding(
        severity = ParallelReviewSeverity.MAJOR,
        confidence = "Low",
        location = "Quality.kt:1",
        description = "shared helper allocates duplicate temporary buffers during conversion",
        specialistSkillName = "bill-generic-code-review-code-quality",
        repositoryPath = "Quality.kt",
        line = 1,
      )
    val higherConfidenceMinor =
      lowerConfidenceMajor.copy(
        severity = ParallelReviewSeverity.MINOR,
        confidence = "High",
        description = "shared helper allocates duplicate temporary buffers in conversion",
      )
    val result =
      ParallelReviewMerger.merge(
        ParallelReviewLaneResult("generic-code-review-code-quality", listOf(lowerConfidenceMajor)),
        ParallelReviewLaneResult("another-lane", listOf(higherConfidenceMinor)),
      )

    assertEquals(1, result.findings.size)
    assertEquals(ParallelReviewSeverity.MINOR, result.findings.single().severity)
    assertEquals("High", result.findings.single().confidence)
    assertEquals(higherConfidenceMinor.description, result.findings.single().description)
    assertEquals(listOf("bill-generic-code-review-code-quality"), result.findings.single().specialistSkillNames)
  }

  @Test
  fun `quality cap breaks confidence ties by representative emission order`() {
    val earlyDuplicate =
      ParallelReviewRawFinding(
        severity = ParallelReviewSeverity.MINOR,
        confidence = "Low",
        location = "Duplicate.kt:1",
        description = "shared helper allocates duplicate temporary buffers during conversion",
        specialistSkillName = "bill-generic-code-review-code-quality",
      )
    val distinct =
      (1..5).map { index ->
        earlyDuplicate.copy(
          confidence = "High",
          location = "Distinct$index.kt:1",
          description = "Distinct quality observation $index",
        )
      }
    val laterRepresentative = earlyDuplicate.copy(confidence = "High", location = "Duplicate.kt:2")

    val result =
      ParallelReviewMerger.merge(
        ParallelReviewLaneResult("first-lane", listOf(earlyDuplicate) + distinct),
        ParallelReviewLaneResult("second-lane", listOf(laterRepresentative)),
      )

    assertEquals(distinct.map { it.location }, result.findings.map { it.location })
    assertEquals(distinct.map { it.description }, result.findings.map { it.description })
  }

  @Test
  fun `case distinct paths never deduplicate`() {
    val lower =
      ParallelReviewRawFinding(
        ParallelReviewSeverity.MAJOR,
        "High",
        "a.kt:1",
        "same exact issue",
        repositoryPath = "a.kt",
        line = 1,
      )
    val upper = lower.copy(location = "A.kt:1", repositoryPath = "A.kt")
    val result =
      ParallelReviewMerger.merge(
        ParallelReviewLaneResult("one", listOf(lower)),
        ParallelReviewLaneResult("two", listOf(upper)),
      )
    assertEquals(2, result.findings.size)
  }

  @Test
  fun `dedup retains specialist and composition provenance`() {
    val baseline =
      ParallelReviewRawFinding(
        ParallelReviewSeverity.MINOR,
        "Medium",
        "Shared.kt:42",
        "Common state mutation can race target lifecycle cancellation",
        "bill-kotlin-code-review-platform-correctness",
        listOf(listOf("kmp", "kotlin")),
      )
    val override =
      baseline.copy(
        severity = ParallelReviewSeverity.MAJOR,
        confidence = "High",
        specialistSkillName = "bill-kmp-code-review-platform-correctness",
        originLayerChains = listOf(listOf("kmp")),
      )

    val merged =
      ParallelReviewMerger.merge(
        ParallelReviewLaneResult("codex", listOf(baseline)),
        ParallelReviewLaneResult("claude", listOf(override)),
      ).findings.single()

    assertEquals(
      listOf("bill-kotlin-code-review-platform-correctness", "bill-kmp-code-review-platform-correctness"),
      merged.specialistSkillNames,
    )
    assertEquals(listOf(listOf("kmp", "kotlin"), listOf("kmp")), merged.originLayerChains)
    assertEquals(ParallelReviewSeverity.MAJOR, merged.severity)
    assertEquals("High", merged.confidence)
  }

  @Test
  fun `identical routing provenance stays stable across two aggregation runs`() {
    val finding =
      ParallelReviewRawFinding(
        ParallelReviewSeverity.MAJOR,
        "High",
        "Auth.kt:10",
        "Token logged",
        "bill-kotlin-code-review-security",
        listOf(listOf("kotlin")),
      )
    val first =
      ParallelReviewMerger.merge(
        ParallelReviewLaneResult("claude", listOf(finding)),
        ParallelReviewLaneResult("codex", listOf(finding)),
      )
    val second =
      ParallelReviewMerger.merge(
        ParallelReviewLaneResult("claude", listOf(finding)),
        ParallelReviewLaneResult("codex", listOf(finding)),
      )

    assertEquals(first.findings.single().specialistSkillNames, second.findings.single().specialistSkillNames)
    assertEquals(first.findings.single().originLayerChains, second.findings.single().originLayerChains)
    assertEquals(first.findings.single().agentIds, second.findings.single().agentIds)
    assertEquals(first.formattedOutput, second.formattedOutput)
  }

  private fun laneResult(
    agentId: String,
    rawOutput: String,
  ) = ParallelReviewLaneResult(agentId = agentId, findings = ParallelReviewFindingParser.parse(rawOutput).findings)

  private fun finding(
    id: String = "F-001",
    severity: String = "Major",
    confidence: String = "High",
    location: String = "Foo.kt:1",
    description: String = "A finding",
  ) = "- [$id] $severity | $confidence | path=\"${location.substringBeforeLast(
    ":",
  )}\" | line=${location.substringAfterLast(":")} | $description"

  @Test
  fun `root-cause dedup preserves surviving finding commit attribution`() {
    val sharedDescription = "Shared contract drift spans the earlier intro and later change"
    val baseline =
      ParallelReviewRawFinding(
        ParallelReviewSeverity.MINOR,
        "Medium",
        "src/contract/Api.yaml:1",
        sharedDescription,
        repositoryPath = "src/contract/Api.yaml",
        line = 1,
        commitShas = listOf("c4"),
      )
    val override =
      baseline.copy(
        severity = ParallelReviewSeverity.MAJOR,
        confidence = "High",
        commitShas = listOf("c4", "head"),
      )

    val merged =
      ParallelReviewMerger.merge(
        ParallelReviewLaneResult("security", listOf(baseline)),
        ParallelReviewLaneResult("security", listOf(override)),
      ).findings.single()

    assertEquals(listOf("c4", "head"), merged.commitShas)
    assertEquals(ParallelReviewSeverity.MAJOR, merged.severity)
    assertEquals("High", merged.confidence)
  }

  @Test
  fun `findings that differ only by commit attribution coalesce into a union attribution`() {
    val description = "Contract surface changed without updating downstream callers"
    val earlier =
      ParallelReviewRawFinding(
        ParallelReviewSeverity.MAJOR,
        "High",
        "src/contract/Api.yaml:1",
        description,
        repositoryPath = "src/contract/Api.yaml",
        line = 1,
        commitShas = listOf("c4"),
      )
    val later = earlier.copy(commitShas = listOf("head"))

    val merged =
      ParallelReviewMerger.merge(
        ParallelReviewLaneResult("claude", listOf(earlier)),
        ParallelReviewLaneResult("codex", listOf(later)),
      )

    val single = merged.findings.single()
    assertEquals(listOf("claude", "codex"), single.agentIds)
    assertEquals(listOf("c4", "head"), single.commitShas)
  }

  @Test
  fun `both lanes empty produces empty result`() {
    val result =
      ParallelReviewMerger.merge(
        laneResult("claude", ""),
        laneResult("codex", ""),
      )

    assertTrue(result.findings.isEmpty())
    assertEquals("", result.formattedOutput)
  }

  @Test
  fun `one lane only produces single-lane provenance label`() {
    val output = finding(severity = "Major")
    val result =
      ParallelReviewMerger.merge(
        laneResult("claude", output),
        laneResult("codex", ""),
      )

    assertEquals(1, result.findings.size)
    assertEquals(listOf("claude"), result.findings[0].agentIds)
    assertContains(result.formattedOutput, "[claude]")
  }

  @Test
  fun `no shared findings produces two independent lists ordered by severity`() {
    val lane1Output =
      """
      - [F-001] Minor | Low | path="Foo.kt" | line=10 | Minor issue
      """.trimIndent()
    val lane2Output =
      """
      - [F-001] Major | High | path="Bar.kt" | line=5 | Major issue
      """.trimIndent()
    val result =
      ParallelReviewMerger.merge(
        laneResult("claude", lane1Output),
        laneResult("codex", lane2Output),
      )

    assertEquals(2, result.findings.size)
    assertEquals(ParallelReviewSeverity.MAJOR, result.findings[0].severity)
    assertEquals(ParallelReviewSeverity.MINOR, result.findings[1].severity)
    assertEquals("F-001", result.findings[0].fNumber)
    assertEquals("F-002", result.findings[1].fNumber)
  }

  @Test
  fun `fully overlapping findings produces coalesced entries with both agent IDs first within tier`() {
    val sharedFinding = finding(severity = "Major", location = "Auth.kt:42", description = "Token exposed")
    val result =
      ParallelReviewMerger.merge(
        laneResult("claude", sharedFinding),
        laneResult("codex", sharedFinding),
      )

    assertEquals(1, result.findings.size)
    val f = result.findings[0]
    assertEquals(listOf("claude", "codex"), f.agentIds)
    assertEquals("F-001", f.fNumber)
    assertContains(result.formattedOutput, "[claude, codex]")
  }

  @Test
  fun `Kotlin baseline and KMP override preserve both lane attributions when deduplicated`() {
    val baseline =
      finding(
        severity = "Minor",
        confidence = "Medium",
        location = "Shared.kt:42",
        description = "Common state mutation can race target lifecycle cancellation",
      )
    val override =
      finding(
        severity = "Major",
        confidence = "High",
        location = "Shared.kt:51",
        description = "Common state mutation can race target lifecycle cancellation",
      )

    val result =
      ParallelReviewMerger.merge(
        laneResult("bill-kotlin-code-review", baseline),
        laneResult("bill-kmp-code-review-platform-correctness", override),
      )

    val merged = result.findings.single()
    assertEquals(
      listOf("bill-kotlin-code-review", "bill-kmp-code-review-platform-correctness"),
      merged.agentIds,
    )
    assertEquals(ParallelReviewSeverity.MAJOR, merged.severity)
    assertEquals("High", merged.confidence)
  }

  @Test
  fun `partial overlap has coalesced entries before single-lane entries within same severity tier`() {
    val shared = finding(id = "F-001", severity = "Major", location = "Auth.kt:1", description = "Shared issue")
    val onlyLane1 = finding(id = "F-002", severity = "Major", location = "Bar.kt:2", description = "Only in lane1")
    val onlyLane2 = finding(id = "F-003", severity = "Major", location = "Baz.kt:3", description = "Only in lane2")
    val lane1Output = "$shared\n$onlyLane1"
    val lane2Output = "$shared\n$onlyLane2"

    val result =
      ParallelReviewMerger.merge(
        laneResult("claude", lane1Output),
        laneResult("codex", lane2Output),
      )

    assertEquals(3, result.findings.size)
    assertEquals(listOf("claude", "codex"), result.findings[0].agentIds)
    assertEquals(1, result.findings[1].agentIds.size)
    assertEquals(1, result.findings[2].agentIds.size)
  }

  @Test
  fun `severity disagreement on same finding uses higher severity`() {
    val location = "Auth.kt:99"
    val description = "Same issue different severity"
    val lane1Output = "- [F-001] Minor | Low | path=\"${location.substringBeforeLast(
      ":",
    )}\" | line=${location.substringAfterLast(":")} | $description"
    val lane2Output = "- [F-001] Major | High | path=\"${location.substringBeforeLast(
      ":",
    )}\" | line=${location.substringAfterLast(":")} | $description"

    val result =
      ParallelReviewMerger.merge(
        laneResult("claude", lane1Output),
        laneResult("codex", lane2Output),
      )

    assertEquals(1, result.findings.size)
    assertEquals(ParallelReviewSeverity.MAJOR, result.findings[0].severity)
  }

  @Test
  fun `F-XXX renumbering is sequential from F-001 regardless of original IDs`() {
    val lane1Output =
      """
      - [F-999] Major | High | path="A.kt" | line=1 | Finding A
      - [F-042] Minor | Low | path="B.kt" | line=2 | Finding B
      """.trimIndent()

    val result =
      ParallelReviewMerger.merge(
        laneResult("claude", lane1Output),
        laneResult("codex", ""),
      )

    assertEquals("F-001", result.findings[0].fNumber)
    assertEquals("F-002", result.findings[1].fNumber)
  }

  @Test
  fun `Nit severity is parsed and sorted below Minor`() {
    val lane1Output =
      """
      - [F-001] Nit | Low | path="A.kt" | line=1 | Nit issue
      - [F-002] Minor | Medium | path="B.kt" | line=2 | Minor issue
      """.trimIndent()

    val result =
      ParallelReviewMerger.merge(
        laneResult("claude", lane1Output),
        laneResult("codex", ""),
      )

    assertEquals(2, result.findings.size)
    assertEquals(ParallelReviewSeverity.MINOR, result.findings[0].severity)
    assertEquals(ParallelReviewSeverity.NIT, result.findings[1].severity)
  }

  @Test
  fun `Critical maps to Blocker`() {
    val lane1Output = "- [F-001] Critical | High | path=\"A.kt\" | line=1 | Critical issue"

    val result =
      ParallelReviewMerger.merge(
        laneResult("claude", lane1Output),
        laneResult("codex", ""),
      )

    assertEquals(1, result.findings.size)
    assertEquals(ParallelReviewSeverity.BLOCKER, result.findings[0].severity)
  }

  @Test
  fun `formattedOutput format matches spec AC11`() {
    val lane1Output = "- [F-001] Major | High | path=\"Auth.kt\" | line=10 | Token logged"

    val result =
      ParallelReviewMerger.merge(
        laneResult("claude", lane1Output),
        laneResult("codex", ""),
      )

    assertEquals(
      "- [F-001] [claude] Major | High | path=\"Auth.kt\" | line=10 | Token logged",
      result.formattedOutput,
    )
  }

  @Test
  fun `fuzzy match on same file coalesces when token overlap above threshold`() {
    val lane1Output = "- [F-001] Major | High | path=\"Auth.kt\" | line=42 | token exposed in logs"
    val lane2Output = "- [F-001] Major | High | path=\"Auth.kt\" | line=42 | token exposed in logs here"

    val result =
      ParallelReviewMerger.merge(
        laneResult("claude", lane1Output),
        laneResult("codex", lane2Output),
      )

    assertEquals(1, result.findings.size)
    assertEquals(listOf("claude", "codex"), result.findings[0].agentIds)
    assertContains(result.formattedOutput, "[claude, codex]")
  }

  @Test
  fun `same description on different files is never coalesced`() {
    val lane1Output = "- [F-001] Major | High | path=\"A.kt\" | line=1 | token exposed in logs"
    val lane2Output = "- [F-001] Major | High | path=\"B.kt\" | line=1 | token exposed in logs"

    val result =
      ParallelReviewMerger.merge(
        laneResult("claude", lane1Output),
        laneResult("codex", lane2Output),
      )

    assertEquals(2, result.findings.size)
    assertEquals(1, result.findings[0].agentIds.size)
    assertEquals(1, result.findings[1].agentIds.size)
  }

  @Test
  fun `same file with disjoint descriptions below threshold is not coalesced`() {
    val lane1Output = "- [F-001] Major | High | path=\"Auth.kt\" | line=7 | token exposed in logs"
    val lane2Output = "- [F-001] Major | High | path=\"Auth.kt\" | line=7 | null pointer dereference here"

    val result =
      ParallelReviewMerger.merge(
        laneResult("claude", lane1Output),
        laneResult("codex", lane2Output),
      )

    assertEquals(2, result.findings.size)
    assertEquals(1, result.findings[0].agentIds.size)
    assertEquals(1, result.findings[1].agentIds.size)
  }

  @Test
  fun `same file with partial overlap below threshold is not coalesced`() {
    val lane1Output = "- [F-001] Major | High | path=\"Auth.kt\" | line=7 | token exposed in logs here"
    val lane2Output = "- [F-001] Major | High | path=\"Auth.kt\" | line=7 | token missing csrf header on post"

    val result =
      ParallelReviewMerger.merge(
        laneResult("claude", lane1Output),
        laneResult("codex", lane2Output),
      )

    assertEquals(2, result.findings.size)
  }

  @Test
  fun `severity disagreement on fuzzy-coalesced pair resolves to higher severity`() {
    val lane1Output = "- [F-001] Minor | Low | path=\"Auth.kt\" | line=42 | token exposed in logs"
    val lane2Output = "- [F-001] Major | High | path=\"Auth.kt\" | line=42 | token exposed in logs here"

    val result =
      ParallelReviewMerger.merge(
        laneResult("claude", lane1Output),
        laneResult("codex", lane2Output),
      )

    assertEquals(1, result.findings.size)
    assertEquals(ParallelReviewSeverity.MAJOR, result.findings[0].severity)
    assertEquals(listOf("claude", "codex"), result.findings[0].agentIds)
  }

  @Test
  fun `coalesced finding format matches spec AC12`() {
    val finding = "- [F-001] Major | High | path=\"Auth.kt\" | line=10 | Token logged"

    val result =
      ParallelReviewMerger.merge(
        laneResult("claude", finding),
        laneResult("codex", finding),
      )

    assertEquals(
      "- [F-001] [claude, codex] Major | High | path=\"Auth.kt\" | line=10 | Token logged",
      result.formattedOutput,
    )
  }

  @Test
  fun `coalesced confidence tracks the higher severity, not first appearance`() {
    val location = "Auth.kt:42"
    val description = "token exposed in logs"
    val lane1Output = "- [F-001] Minor | High | path=\"${location.substringBeforeLast(
      ":",
    )}\" | line=${location.substringAfterLast(":")} | $description"
    val lane2Output = "- [F-001] Major | Low | path=\"${location.substringBeforeLast(
      ":",
    )}\" | line=${location.substringAfterLast(":")} | $description"

    val result =
      ParallelReviewMerger.merge(
        laneResult("claude", lane1Output),
        laneResult("codex", lane2Output),
      )

    assertEquals(1, result.findings.size)
    assertEquals(ParallelReviewSeverity.MAJOR, result.findings[0].severity)
    assertEquals("Low", result.findings[0].confidence)
  }

  @Test
  fun `same file with token overlap exactly at threshold is not coalesced`() {
    val lane1Output = "- [F-001] Major | High | path=\"Auth.kt\" | line=7 | alpha beta gamma delta"
    val lane2Output = "- [F-001] Major | High | path=\"Auth.kt\" | line=7 | alpha beta gamma epsilon"

    val result =
      ParallelReviewMerger.merge(
        laneResult("claude", lane1Output),
        laneResult("codex", lane2Output),
      )

    assertEquals(2, result.findings.size)
    assertEquals(1, result.findings[0].agentIds.size, "finding at threshold must not be spuriously coalesced")
    assertEquals(1, result.findings[1].agentIds.size, "finding at threshold must not be spuriously coalesced")
  }

  @Test
  fun `findings without leading dash are parsed and coalesced with dash-prefixed findings`() {
    val withDash = "- [F-001] Major | High | path=\"Auth.kt\" | line=10 | Token logged"
    val withoutDash = "[F-001] Major | High | path=\"Auth.kt\" | line=10 | Token logged"

    val result =
      ParallelReviewMerger.merge(
        laneResult("claude", withDash),
        laneResult("codex", withoutDash),
      )

    assertEquals(1, result.findings.size)
    assertEquals(listOf("claude", "codex"), result.findings[0].agentIds)
  }

  @Test
  fun `integration findings merge alongside specialist findings without displacing them`() {
    val specialist =
      ParallelReviewRawFinding(
        ParallelReviewSeverity.MAJOR,
        "High",
        "Auth.kt:10",
        "Token is logged in the new branch",
        repositoryPath = "Auth.kt",
        line = 10,
      )
    val crossCommit =
      ParallelReviewRawFinding(
        ParallelReviewSeverity.MAJOR,
        "High",
        "Session.kt:4",
        "Commit two reverts the guard commit one added",
        repositoryPath = "Session.kt",
        line = 4,
      )

    val merged =
      ParallelReviewMerger.merge(
        ParallelReviewLaneResult("claude", listOf(specialist)),
        ParallelReviewLaneResult("codex", emptyList()),
        ParallelReviewLaneResult("review-integration", listOf(crossCommit)),
      )

    assertEquals(2, merged.findings.size)
    assertEquals("Auth.kt", merged.findings[0].repositoryPath)
    assertContains(merged.findings[1].agentIds, "review-integration")
  }

  @Test
  fun `a specialist lane stays the representative when integration reports the same root cause`() {
    val specialist =
      ParallelReviewRawFinding(
        ParallelReviewSeverity.MAJOR,
        "High",
        "Auth.kt:10",
        "Token is logged in the new branch",
        repositoryPath = "Auth.kt",
        line = 10,
      )

    val merged =
      ParallelReviewMerger.merge(
        ParallelReviewLaneResult("claude", listOf(specialist)),
        ParallelReviewLaneResult("codex", emptyList()),
        ParallelReviewLaneResult("review-integration", listOf(specialist.copy(confidence = "Medium"))),
      ).findings.single()

    assertEquals(listOf("claude", "review-integration"), merged.agentIds)
    assertEquals("High", merged.confidence)
  }

  @Test
  fun `severity adjustment appears beside the original severity on the preserved claim line`() {
    val finding =
      ParallelReviewRawFinding(
        ParallelReviewSeverity.MAJOR,
        "High",
        "Auth.kt:10",
        "Token logged",
        repositoryPath = "Auth.kt",
        line = 10,
        claimVerdict = ReviewClaimVerdict.CONFIRMED,
        scopeDisposition = ReviewScopeDisposition.IN_SCOPE,
        severityAdjustment =
          ReviewSeverityAdjustment(
            ReviewSeverityAdjustmentDirection.LOWER,
            "too noisy",
          ),
      )
    val result =
      ParallelReviewMerger.merge(
        ParallelReviewLaneResult("claude", listOf(finding)),
        ParallelReviewLaneResult("codex", emptyList()),
      )
    val line = result.formattedOutput.lineSequence().first { it.contains("[F-001]") }
    assertTrue(line.contains("Major |"), line)
    assertTrue(line.contains("severity_adjustment=lower: too noisy"), line)
    assertFalse(line.contains("Nit |"), line)
    assertFalse(line.contains("Major (lower"), line)
    assertEquals(
      "- [F-001] [claude] Major | High | path=\"Auth.kt\" | line=10 | Token logged | " +
        "claim_verdict=confirmed | scope_disposition=in_scope | severity_adjustment=lower: too noisy",
      line,
    )
  }

  @Test
  fun `coalesced disagreeing verdicts keep the conservative outcome and both lane sources`() {
    val description = "Token logged in the authentication path"
    val confirmed =
      ParallelReviewRawFinding(
        ParallelReviewSeverity.MAJOR,
        "High",
        "Auth.kt:10",
        description,
        repositoryPath = "Auth.kt",
        line = 10,
        claimVerdict = ReviewClaimVerdict.REFUTED,
        citations = listOf(ReviewFindingCitation("Auth.kt", 10)),
      )
    val unresolved =
      confirmed.copy(
        claimVerdict = ReviewClaimVerdict.UNRESOLVED,
        citations = emptyList(),
      )
    val result =
      ParallelReviewMerger.merge(
        ParallelReviewLaneResult("claude", listOf(confirmed)),
        ParallelReviewLaneResult("codex", listOf(unresolved)),
      )
    val merged = result.findings.single()
    assertEquals(ReviewClaimVerdict.UNRESOLVED, merged.claimVerdict)
    assertEquals(listOf("claude", "codex"), merged.sourceVerdicts.map { it.laneId })
    assertEquals(
      listOf(ReviewClaimVerdict.REFUTED, ReviewClaimVerdict.UNRESOLVED),
      merged.sourceVerdicts.map { it.claimVerdict },
    )
  }

  @Test
  fun `parser through merge peels structured verdicts and keeps conservative lane provenance`() {
    val description = "Token logged in the authentication path"
    val result =
      ParallelReviewMerger.merge(
        laneResult(
          "claude",
          finding(
            location = "Auth.kt:10",
            description = "$description | claim_verdict=refuted | citations=Auth.kt:10",
          ),
        ),
        laneResult(
          "codex",
          finding(
            location = "Auth.kt:10",
            description = "$description | claim_verdict=unresolved",
          ),
        ),
      )
    val merged = result.findings.single()
    assertEquals(description, merged.description)
    assertEquals(ReviewClaimVerdict.UNRESOLVED, merged.claimVerdict)
    assertEquals(listOf("claude", "codex"), merged.sourceVerdicts.map { it.laneId })
    assertEquals(
      listOf(ReviewClaimVerdict.REFUTED, ReviewClaimVerdict.UNRESOLVED),
      merged.sourceVerdicts.map { it.claimVerdict },
    )
    assertEquals(listOf(ReviewFindingCitation("Auth.kt", 10)), merged.citations)
  }
}
