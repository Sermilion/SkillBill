
package skillbill.review.parallel

import skillbill.review.model.ParallelReviewFindingRejectionReason
import skillbill.review.model.ReviewClaimVerdict
import skillbill.review.model.ReviewFindingCitation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParallelReviewFindingParserTest {
  @Test
  fun `specialist ahead of unquoted path still admits the finding`() {
    val parsed =
      ParallelReviewFindingParser.parse(
        "[F-001] Major | High | specialist=bill-kotlin-code-review-architecture | " +
          "path=src/Auth.kt | line=12 | missing check",
      )
    val finding = parsed.findings.single()

    assertEquals("bill-kotlin-code-review-architecture", finding.specialistSkillName)
    assertEquals("src/Auth.kt", finding.repositoryPath)
    assertEquals(12, finding.line)
    assertEquals("missing check", finding.description)
    assertEquals(emptyList(), parsed.rejections)
  }

  @Test
  fun `parser retains explicit inline specialist identity`() {
    val finding =
      ParallelReviewFindingParser.parse(
        "[F-001] Major | High | specialist=bill-kotlin-code-review-security | " +
          "path=\"src/Auth.kt\" | line=12 | missing check",
      ).findings.single()

    assertEquals("bill-kotlin-code-review-security", finding.specialistSkillName)
    assertEquals("src/Auth.kt:12", finding.location)
    assertEquals("src/Auth.kt", finding.repositoryPath)
    assertEquals("missing check", finding.description)
  }

  @Test
  fun `structured delegated finding remains parseable without specialist identity`() {
    val finding =
      ParallelReviewFindingParser.parse(
        "[F-001] Minor | Medium | path=\"src/Main.kt\" | line=2 | stale branch",
      ).findings.single()

    assertEquals(null, finding.specialistSkillName)
  }

  @Test
  fun `documented file line finding remains parseable`() {
    val finding =
      ParallelReviewFindingParser.parse(
        "- [F-001] Blocker | High | src/main/App.kt:42 | compliant worker output",
      ).findings.single()

    assertEquals("src/main/App.kt:42", finding.location)
    assertEquals("src/main/App.kt", finding.repositoryPath)
    assertEquals(42, finding.line)
    assertEquals("compliant worker output", finding.description)
  }

  @Test
  fun `documented file line finding retains specialist identity`() {
    val finding =
      ParallelReviewFindingParser.parse(
        "[F-001] Major | Medium | specialist=bill-kotlin-code-review-testing | src/Test.kt:9 | weak assertion",
      ).findings.single()

    assertEquals("bill-kotlin-code-review-testing", finding.specialistSkillName)
    assertEquals("src/Test.kt", finding.repositoryPath)
  }

  @Test
  fun `structured path round trips punctuation backslash and controls`() {
    val finding =
      ParallelReviewFindingParser.parse(
        "[F-001] Minor | High | path=\"A|b\\\\c\\t.kt\" | line=7 | exact owner",
      ).findings.single()
    assertEquals("A|b\\c\t.kt", finding.repositoryPath)
  }

  @Test
  fun `parser accepts only positive representable line numbers`() {
    val inputs = listOf("0", "-1", "1", "2147483648")
    val parsed =
      inputs.map { line ->
        ParallelReviewFindingParser.parse(
          "[F-001] Major | High | path=\"src/Auth.kt\" | line=$line | bounded line",
        )
      }

    assertTrue(parsed[0].findings.isEmpty())
    assertTrue(parsed[1].findings.isEmpty())
    assertEquals(1, parsed[2].findings.single().line)
    assertTrue(parsed[3].findings.isEmpty())
  }

  @Test
  fun `documented file line format accepts only positive representable line numbers`() {
    val inputs = listOf("0", "-1", "1", "2147483648")
    val parsed =
      inputs.map { line ->
        ParallelReviewFindingParser.parse(
          "[F-001] Major | High | src/Auth.kt:$line | bounded line",
        )
      }

    assertTrue(parsed[0].findings.isEmpty())
    assertTrue(parsed[1].findings.isEmpty())
    assertEquals(1, parsed[2].findings.single().line)
    assertTrue(parsed[3].findings.isEmpty())
  }

  @Test
  fun `finding naming two commits retains both shas and structured fields`() {
    val finding =
      ParallelReviewFindingParser.parse(
        "[F-001] Major | High | commits=c1,head | path=\"src/Contract.kt\" | line=12 | cross-commit drift",
      ).findings.single()

    assertEquals("cross-commit drift", finding.description)
    assertEquals(listOf("c1", "head"), finding.commitShas)
    assertEquals("src/Contract.kt", finding.repositoryPath)
    assertEquals(12, finding.line)
    assertEquals("Major", finding.severity.displayName)
    assertEquals("High", finding.confidence)
  }

  @Test
  fun `finding with no commit attribution still parses`() {
    val finding =
      ParallelReviewFindingParser.parse(
        "[F-002] Minor | Medium | path=\"src/Main.kt\" | line=4 | no commit attribution",
      ).findings.single()

    assertEquals(emptyList(), finding.commitShas)
    assertEquals("src/Main.kt", finding.repositoryPath)
    assertEquals(4, finding.line)
    assertEquals("no commit attribution", finding.description)
  }

  @Test
  fun `parser preserves finding order across structured and documented formats`() {
    val findings =
      ParallelReviewFindingParser.parse(
        """
        - [F-001] Major | High | src/Legacy.kt:3 | legacy contract
        - [F-002] Minor | Low | path="src/Structured.kt" | line=4 | structured contract
        """.trimIndent(),
      ).findings

    assertEquals(listOf("src/Legacy.kt", "src/Structured.kt"), findings.map { it.repositoryPath })
  }

  @Test
  fun `invalid path still parses and does not abort later findings`() {
    val result =
      ParallelReviewFindingParser.parse(
        """
        - [F-001] Major | High | path="/tmp/outside.kt" | line=3 | outside the packet
        - [F-002] Minor | Low | path="src\u12" | line=5 | malformed escape
        - [F-003] Minor | Low | path="src/Ok.kt" | line=4 | inside the packet
        """.trimIndent(),
      )

    assertEquals(listOf("src/Ok.kt"), result.findings.map { it.repositoryPath })
    assertEquals(
      listOf(
        ParallelReviewFindingRejectionReason.NO_ADMISSIBLE_LOCATION,
        ParallelReviewFindingRejectionReason.UNPARSEABLE_STRUCTURED_PATH,
      ),
      result.rejections.map { it.reason },
    )
    assertEquals(listOf(1, 2), result.rejections.map { it.linePosition })
  }

  @Test
  fun `traversing structured path is rejected and later findings still parse`() {
    val result =
      ParallelReviewFindingParser.parse(
        """
        - [F-001] Major | High | path="../outside.kt" | line=3 | outside the repository
        - [F-002] Minor | Low | path="src/Ok.kt" | line=4 | inside the repository
        """.trimIndent(),
      )

    assertEquals(listOf("src/Ok.kt"), result.findings.map { it.repositoryPath })
    assertEquals(
      listOf(ParallelReviewFindingRejectionReason.NO_ADMISSIBLE_LOCATION),
      result.rejections.map { it.reason },
    )
  }

  @Test
  fun `one garbled register line is reported as a rejection without discarding its siblings`() {
    val result =
      ParallelReviewFindingParser.parse(
        """
        - [F-001] Major | High | src/A.kt:1 | first
        - [F-002] Bogus | High | src/B.kt:2 | garbled severity
        - [F-003] Minor | Low | src/C.kt:3 | third
        """.trimIndent(),
      )

    assertEquals(listOf("src/A.kt", "src/C.kt"), result.findings.map { it.repositoryPath })
    val rejection = result.rejections.single()
    assertEquals("- [F-002] Bogus | High | src/B.kt:2 | garbled severity", rejection.lineText)
    assertEquals(2, rejection.linePosition)
    assertEquals(ParallelReviewFindingRejectionReason.UNRECOGNIZED_SEVERITY, rejection.reason)
    assertEquals(3, result.candidateCount)
  }

  @Test
  fun `well formed mixed register admits every finding and rejects nothing`() {
    val result =
      ParallelReviewFindingParser.parse(
        """
        [F-001] Major | High | path="src/Structured.kt" | line=4 | structured | claim_verdict=confirmed
        - [F-002] Minor | Low | src/Legacy.kt:9 | legacy prefixed
        [F-003] Blocker | Medium | specialist=bill-kotlin-code-review-security | src/Auth.kt:1 | legacy plain
        """.trimIndent(),
      )

    assertEquals(
      listOf("src/Structured.kt:4", "src/Legacy.kt:9", "src/Auth.kt:1"),
      result.findings.map { it.location },
    )
    assertEquals(listOf("Major", "Minor", "Blocker"), result.findings.map { it.severity.displayName })
    assertEquals(listOf("structured", "legacy prefixed", "legacy plain"), result.findings.map { it.description })
    assertEquals(ReviewClaimVerdict.CONFIRMED, result.findings.first().claimVerdict)
    assertEquals(emptyList(), result.rejections)
    assertEquals(3, result.candidateCount)
  }

  @Test
  fun `routed rubric annotation after specialist skill name is peeled and the finding admits`() {
    val finding =
      ParallelReviewFindingParser.parse(
        "[F-001] Minor | High | specialist=bill-generic-code-review-api-contracts" +
          "[paths=\"src/Auth.kt\";add-ons=none;origins=generic] | " +
          "commits=synthetic:synthetic_supplied_diff | path=\"src/Auth.kt\" | line=65 | " +
          "fixture cannot distinguish omitted from null",
      ).findings.single()

    assertEquals("bill-generic-code-review-api-contracts", finding.specialistSkillName)
    assertEquals("src/Auth.kt", finding.repositoryPath)
    assertEquals(65, finding.line)
    assertEquals("fixture cannot distinguish omitted from null", finding.description)
  }

  @Test
  fun `near miss finding ids are padded and unwrapped so the finding admits`() {
    val cases =
      listOf(
        "- [F-1] Major | High | src/A.kt:1 | short id" to "src/A.kt",
        "- [F-0001] Major | High | src/A.kt:1 | long id" to "src/A.kt",
        "**[F-001]** Major | High | src/A.kt:1 | bolded id" to "src/A.kt",
        "| [F-001] | Major | High | src/A.kt:1 | table wrapped id" to "src/A.kt",
      )

    cases.forEach { (line, path) ->
      val result = ParallelReviewFindingParser.parse("Preamble prose.\n\n$line")

      assertEquals(listOf(path), result.findings.map { it.repositoryPath }, line)
      assertEquals(emptyList(), result.rejections, line)
      assertEquals(1, result.candidateCount, line)
    }
  }

  @Test
  fun `normalizeRegisterLine pads short and long F-ids and strips bold or table wrappers`() {
    assertEquals(
      "[F-001] Major | High | src/A.kt:1 | short",
      ParallelReviewFindingParser.normalizeRegisterLine("[F-1] Major | High | src/A.kt:1 | short"),
    )
    assertEquals(
      "- [F-001] Major | High | src/A.kt:1 | long",
      ParallelReviewFindingParser.normalizeRegisterLine("- [F-0001] Major | High | src/A.kt:1 | long"),
    )
    assertEquals(
      "[F-001] Major | High | src/A.kt:1 | bolded",
      ParallelReviewFindingParser.normalizeRegisterLine("**[F-001]** Major | High | src/A.kt:1 | bolded"),
    )
    assertEquals(
      "[F-001] Major | High | src/A.kt:1 | table",
      ParallelReviewFindingParser.normalizeRegisterLine("| [F-001] | Major | High | src/A.kt:1 | table"),
    )
  }

  @Test
  fun `a finding body without an F-token gets the next sequential id in the lane`() {
    val result =
      ParallelReviewFindingParser.parse(
        """
        [F-001] Major | High | src/A.kt:1 | first
        Major | High | src/B.kt:2 | missing token
        - Minor | Low | src/C.kt:3 | bullet missing token
        """.trimIndent(),
      )

    assertEquals(
      listOf("src/A.kt", "src/B.kt", "src/C.kt"),
      result.findings.map { it.repositoryPath },
    )
    assertEquals(emptyList(), result.rejections)
    assertEquals(
      listOf(
        "[F-001] Major | High | src/A.kt:1 | first",
        "[F-002] Major | High | src/B.kt:2 | missing token",
        "- [F-003] Minor | Low | src/C.kt:3 | bullet missing token",
      ),
      ParallelReviewFindingParser.normalizeRegisterText(
        """
        [F-001] Major | High | src/A.kt:1 | first
        Major | High | src/B.kt:2 | missing token
        - Minor | Low | src/C.kt:3 | bullet missing token
        """.trimIndent(),
      ).lines(),
    )
  }

  @Test
  fun `trailing citation tokens coerce zero and diagnose malformed lines`() {
    val parsed =
      ParallelReviewFindingParser.parse(
        "[F-001] Major | High | path=src/A.kt | line=12 | stale check | " +
          "citations=src/Valid.kt:5,src/Bad.kt:0,src/C.kt:abc",
      )
    val finding = parsed.findings.single()
    assertEquals(
      listOf(
        ReviewFindingCitation("src/Valid.kt", 5),
        ReviewFindingCitation("src/Bad.kt", 1),
      ),
      finding.citations,
    )
    assertEquals(1, parsed.citationDiagnostics.size)
    assertTrue(parsed.citationDiagnostics.all { it.findingRef == "F-001" })
    assertEquals(
      listOf("non_numeric_line"),
      parsed.citationDiagnostics.map { it.diagnostic.reason },
    )
  }

  @Test
  fun `prose without any finding token yields no candidates and no rejections`() {
    val result =
      ParallelReviewFindingParser.parse(
        "I reviewed the diff and found nothing worth reporting. No F- register applies here.",
      )

    assertEquals(emptyList(), result.findings)
    assertEquals(emptyList(), result.rejections)
    assertEquals(0, result.candidateCount)
  }
}
