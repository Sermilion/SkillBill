package skillbill.engine.operation

import skillbill.engine.operation.featureguard.FeatureGuardPromptRules
import skillbill.engine.operation.featureguardcleanup.FeatureGuardCleanupPromptRules
import skillbill.engine.operation.unittestvalue.UnitTestValueCheckPromptRules
import skillbill.engine.operation.verify.VerifyPromptSections
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

class ChecklistOperationRulesParityTest {
  private val skillsRoot: Path = locateSkillsRoot()

  @Test
  fun `feature-guard carries the skill's principles, strategy, checklist, questions, and patterns`() {
    val skill = content("bill-feature-guard")

    assertEquals(section(skill, "## Core Principles"), FeatureGuardPromptRules.CORE_PRINCIPLES)
    assertEquals(section(skill, "## Implementation Strategy"), FeatureGuardPromptRules.IMPLEMENTATION_STRATEGY)
    assertEquals(section(skill, "## Checklist"), FeatureGuardPromptRules.CHECKLIST)
    assertEquals(section(skill, "## When to Ask User"), FeatureGuardPromptRules.WHEN_TO_ASK_USER)
    assertEquals(span(skill, "## Patterns", "## Example Session Flow"), FeatureGuardPromptRules.PATTERNS)
  }

  @Test
  fun `feature-guard-cleanup carries the skill's safety checks, remove order, checklist, and patterns`() {
    val skill = content("bill-feature-guard-cleanup")

    assertEquals(section(skill, "## When To Use"), FeatureGuardCleanupPromptRules.WHEN_TO_USE)
    assertEquals(
      span(skill, "### Step 1: Identify Scope", "### Step 4: Verify"),
      FeatureGuardCleanupPromptRules.CLEANUP_STEPS,
    )
    assertEquals(section(skill, "## Checklist"), FeatureGuardCleanupPromptRules.CHECKLIST)
    assertEquals(section(skill, "## When to Ask User"), FeatureGuardCleanupPromptRules.WHEN_TO_ASK_USER)
    assertEquals(span(skill, "## Cleanup Patterns", until = null), FeatureGuardCleanupPromptRules.CLEANUP_PATTERNS)
  }

  @Test
  fun `unit-test-value-check carries the skill's stance and its rubric from supported scope through output`() {
    val skill = content("bill-unit-test-value-check")
    val title = "# Unit Test Value Check Content"

    assertEquals(
      span(skill, title, "## Supported Scope").removePrefix(title).trim(),
      UnitTestValueCheckPromptRules.STANCE,
    )
    assertEquals(span(skill, "## Supported Scope", until = null), UnitTestValueCheckPromptRules.RUBRIC)
  }

  @Test
  fun `verify carries the skill's criteria extraction, audits, verdict up to the fix offer, and input boundary`() {
    val skill = content("bill-feature-verify")

    assertEquals(
      span(
        skill,
        "After reading the spec, produce in one pass:",
        "Then ask: **Confirm or adjust the criteria before I review the PR.**",
      ),
      VerifyPromptSections.CRITERIA_EXTRACTION,
    )
    assertEquals(section(skill, "## Feature Flag Audit"), VerifyPromptSections.FEATURE_FLAG_AUDIT)
    assertEquals(section(skill, "## Completeness Audit"), VerifyPromptSections.COMPLETENESS_AUDIT)
    assertEquals(
      span(skill, "## Consolidated Verdict", "After presenting the verdict, ask:"),
      VerifyPromptSections.CONSOLIDATED_VERDICT,
    )
    assertEquals(section(skill, "## Verification Input Boundary"), VerifyPromptSections.VERIFICATION_INPUT_BOUNDARY)
  }

  private fun content(skill: String): String = Files.readString(skillsRoot.resolve("$skill/content.md"))

  private fun section(
    content: String,
    heading: String,
  ): String {
    val level = heading.takeWhile { it == '#' }.length
    val lines = content.lines().dropWhile { it != heading }
    require(lines.isNotEmpty()) { "missing heading '$heading'" }
    val next = Regex("^#{1,$level} ")
    return (listOf(lines.first()) + lines.drop(1).takeWhile { !next.containsMatchIn(it) }).joinToString("\n").trim()
  }

  private fun span(
    content: String,
    from: String,
    until: String?,
  ): String {
    val lines = content.lines().dropWhile { it != from }
    require(lines.isNotEmpty()) { "missing heading '$from'" }
    return lines.takeWhile { it != until }.joinToString("\n").trim()
  }

  private fun locateSkillsRoot(): Path =
    generateSequence(Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()) { it.parent }
      .map { candidate -> candidate.resolve("skills") }
      .firstOrNull { skills -> Files.isDirectory(skills.resolve("bill-feature-guard")) }
      ?: error("could not locate skills/bill-feature-guard above ${System.getProperty("user.dir")}")
}
