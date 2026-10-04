package skillbill.review.attribution

import skillbill.error.core.ReviewAttributionFailureCode
import skillbill.error.core.SkillBillRuntimeException
import skillbill.review.model.CanonicalScope
import skillbill.review.model.ReviewExecutionMode
import skillbill.review.model.UNRESOLVED_ATTRIBUTION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewAttributionCanonicalizationTest {
  private val packSkills =
    setOf(
      "bill-code-review",
      "bill-generic-code-review",
      "bill-ios-code-review",
      "bill-kmp-code-review",
      "bill-kotlin-code-review",
    )

  @Test
  fun `observed routed skill prose variants collapse to one canonical pack id`() {
    val observedVariants =
      listOf(
        "bill-kmp-code-review",
        "  bill-kmp-code-review  ",
        "bill-kmp-code-review (parallel)",
        "bill-kmp-code-review-persistence",
        "Routed to bill-kmp-code-review for the KMP pack",
        "skillbill:bill-kmp-code-review",
        "bill-kmp-code-review — kmp platform pack",
        "BILL-KMP-CODE-REVIEW",
        "the review ran under bill-kmp-code-review.",
        "bill-kmp-code-review [inline]",
        "`bill-kmp-code-review`",
        "bill-kmp-code-review-ui specialist",
      )

    val canonicals =
      observedVariants.map { raw ->
        val resolved = resolveCanonicalRoutedSkill(raw, packSkills)
        assertEquals(raw, resolved.raw, "The raw routed_skill text must be retained verbatim.")
        resolved.canonical
      }

    assertEquals(setOf("bill-kmp-code-review"), canonicals.toSet())
  }

  @Test
  fun `composite and unknown routed skills are marked unresolved rather than bucketed`() {
    val ambiguous =
      listOf(
        "bill-kmp-code-review, bill-kotlin-code-review",
        "`bill-kmp-code-review` and `bill-ios-code-review`",
        "unknown routing",
        "bill-rust-code-review",
      )

    ambiguous.forEach { raw ->
      val resolved = resolveCanonicalRoutedSkill(raw, packSkills)
      assertEquals(UNRESOLVED_ATTRIBUTION, resolved.canonical, "'$raw' must not resolve.")
      assertFalse(resolved.resolved)
      assertEquals(raw, resolved.raw)
    }
  }

  @Test
  fun `empty and null routed skills are unresolved with their raw value retained`() {
    assertEquals(UNRESOLVED_ATTRIBUTION, resolveCanonicalRoutedSkill(null, packSkills).canonical)
    assertNull(resolveCanonicalRoutedSkill(null, packSkills).raw)
    assertEquals(UNRESOLVED_ATTRIBUTION, resolveCanonicalRoutedSkill("   ", packSkills).canonical)
    assertEquals("   ", resolveCanonicalRoutedSkill("   ", packSkills).raw)
  }

  @Test
  fun `an unavailable pack catalog still resolves a structurally exact pack id`() {
    val resolved = resolveCanonicalRoutedSkill("bill-kmp-code-review", emptySet())

    assertEquals("bill-kmp-code-review", resolved.canonical)
    assertEquals("bill-kmp-code-review", resolved.raw)
  }

  @Test
  fun `a malformed vocabulary entry raises its coded resolver failure`() {
    val failure =
      assertFailsWith<SkillBillRuntimeException> {
        resolveCanonicalRoutedSkill("bill-kmp-code-review", setOf("Bill KMP Code Review"))
      }

    assertEquals(ReviewAttributionFailureCode.MALFORMED_VOCABULARY, failure.code)
    assertEquals(
      "Review attribution vocabulary 'pack_skill_names' contains the malformed entry 'Bill KMP Code Review' " +
        "while resolving 'bill-kmp-code-review'.",
      failure.message,
    )

    val stackFailure =
      assertFailsWith<SkillBillRuntimeException> {
        resolveCanonicalStack("kotlin", setOf("Kotlin/JVM"))
      }
    assertEquals(ReviewAttributionFailureCode.MALFORMED_VOCABULARY, stackFailure.code)
  }

  @Test
  fun `kotlin stack prose variants collapse to one canonical stack`() {
    listOf("Kotlin", "kotlin", "Kotlin/JVM", "kotlin (jvm backend)", "  KOTLIN  ").forEach { raw ->
      assertEquals("kotlin", resolveCanonicalStack(raw, canonicalPlatformSlugs).canonical, "raw='$raw'")
    }

    listOf("kmp", "KMP", "Kotlin Multiplatform", "kotlin multiplatform (shared)").forEach { raw ->
      assertEquals("kmp", resolveCanonicalStack(raw, canonicalPlatformSlugs).canonical, "raw='$raw'")
    }
  }

  @Test
  fun `ambiguous and unknown stacks are marked unresolved`() {
    listOf("kotlin, ios", "elixir", "", "   ", "mixed sources").forEach { raw ->
      assertEquals(UNRESOLVED_ATTRIBUTION, resolveCanonicalStack(raw, canonicalPlatformSlugs).canonical, "raw='$raw'")
    }
    assertEquals(UNRESOLVED_ATTRIBUTION, resolveCanonicalStack(null, canonicalPlatformSlugs).canonical)
  }

  @Test
  fun `each scope vocabulary member resolves from prose with the remainder kept as detail`() {
    val cases =
      mapOf(
        "working tree" to CanonicalScope.WORKING_TREE,
        "unstaged changes (3 files)" to CanonicalScope.WORKING_TREE,
        "staged changes (index)" to CanonicalScope.STAGED,
        "commit range (main..HEAD)" to CanonicalScope.COMMIT_RANGE,
        "branch diff (feat/x)" to CanonicalScope.COMMIT_RANGE,
        "pull request (#204)" to CanonicalScope.PULL_REQUEST,
        "repository (full sweep)" to CanonicalScope.OTHER,
      )

    cases.forEach { (raw, expected) ->
      val resolved = resolveCanonicalScope(raw)
      assertEquals(expected.wireValue, resolved.canonical, "raw='$raw'")
      assertEquals(raw, resolved.raw)
    }

    assertEquals("main..HEAD", resolveCanonicalScope("commit range (main..HEAD)").detail)
    assertNull(resolveCanonicalScope("working tree").detail)
  }

  @Test
  fun `every governed review-scope label resolves`() {
    val cases =
      mapOf(
        "staged changes" to CanonicalScope.STAGED,
        "unstaged changes" to CanonicalScope.WORKING_TREE,
        "working tree" to CanonicalScope.WORKING_TREE,
        "commit range" to CanonicalScope.COMMIT_RANGE,
        "PR diff" to CanonicalScope.PULL_REQUEST,
        "files" to CanonicalScope.OTHER,
        "PR diff (origin/main...HEAD)" to CanonicalScope.PULL_REQUEST,
        "PR diff (acme/acme-android#2981)" to CanonicalScope.PULL_REQUEST,
        "PR diff #237 (main...a5db414a)" to CanonicalScope.PULL_REQUEST,
      )

    cases.forEach { (raw, expected) ->
      assertEquals(expected.wireValue, resolveCanonicalScope(raw).canonical, "raw='$raw'")
    }
  }

  @Test
  fun `the scope vocabulary is exactly the five controlled values`() {
    assertEquals(
      listOf("working_tree", "staged", "commit_range", "pull_request", "other"),
      CanonicalScope.entries.map(CanonicalScope::wireValue),
    )
  }

  @Test
  fun `an unresolvable scope keeps its raw text and detail without falling back to other`() {
    val resolved = resolveCanonicalScope("whatever the agent felt like (some note)")

    assertEquals(UNRESOLVED_ATTRIBUTION, resolved.canonical)
    assertEquals("whatever the agent felt like (some note)", resolved.raw)
    assertEquals("some note", resolved.detail)
    assertEquals(UNRESOLVED_ATTRIBUTION, resolveCanonicalScope(null).canonical)
  }

  @Test
  fun `execution mode prefers the reported value then delegation evidence then unresolved`() {
    assertEquals(ReviewExecutionMode.INLINE, resolveExecutionMode(ReviewExecutionMode.INLINE, emptyList()))
    assertEquals(ReviewExecutionMode.INLINE, resolveExecutionMode(ReviewExecutionMode.INLINE, listOf("architecture")))
    assertEquals(ReviewExecutionMode.DELEGATED, resolveExecutionMode(null, listOf("architecture")))
    assertEquals(ReviewExecutionMode.UNRESOLVED, resolveExecutionMode(null, emptyList()))
    assertTrue(UNRESOLVED_ATTRIBUTION !in listOf("inline", EXECUTION_MODE_DELEGATED))
  }

  @Test
  fun `pack slug resolves from a canonical pack skill name and only from one`() {
    assertEquals("kmp", packSlugFromCanonicalPackSkillName("bill-kmp-code-review"))
    assertEquals("android-compose", packSlugFromCanonicalPackSkillName("bill-android-compose-code-review"))
    assertNull(packSlugFromCanonicalPackSkillName(UNRESOLVED_ATTRIBUTION))
    assertNull(packSlugFromCanonicalPackSkillName("bill-code-review"))
    assertNull(packSlugFromCanonicalPackSkillName("Routed to: bill-kmp-code-review"))
  }
}
