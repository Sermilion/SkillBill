package skillbill.engine.featuretask.slot.pullrequest

import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.phaserun.PhaseRunResult
import skillbill.engine.featuretask.phaserun.phaseRunDatabase
import skillbill.ports.goalrunner.runner.PullRequestIdentityLookup
import skillbill.ports.goalrunner.runner.model.PullRequestIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PrDescriptionTemplateRunTest {
  private val root: Path = Files.createTempDirectory("skillbill-pr-template-run")
  private val home: Path = Files.createTempDirectory("skillbill-pr-template-run-home")
  private val clock: Clock = Clock.systemUTC()
  private val database = phaseRunDatabase(home, clock)
  private val absent = PullRequestIdentityLookup { _, _ -> PullRequestIdentity.Absent }

  @AfterTest
  fun cleanUp() {
    root.toFile().deleteRecursively()
    home.toFile().deleteRecursively()
  }

  @Test
  fun `the standalone pr prompt carries the found template without its checklist`() {
    val repoRoot = Files.createDirectories(root.resolve("standalone"))
    writeTemplate(repoRoot, TEMPLATE_PATH, TEMPLATE)

    val run = standalonePrRun(repoRoot, database, absent)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(run.report, run.report.toString())
    assertTemplatePrompt(run.prPrompts.single())
  }

  @Test
  fun `the phase pr prompt carries the found template without its checklist`() {
    val repository = featureRepository()
    writeTemplate(repository.repoRoot, TEMPLATE_PATH, TEMPLATE)

    val run = phasePrRun(repository.repoRoot, database, absent, clock)

    assertIs<PhaseRunResult.Completed>(run.result, run.result.toString())
    assertTemplatePrompt(run.prPrompts.single())
  }

  @Test
  fun `the standalone pr prompt falls back to the coded template when none is found`() {
    val repoRoot = Files.createDirectories(root.resolve("standalone"))

    val run = standalonePrRun(repoRoot, database, absent)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(run.report, run.report.toString())
    assertFallbackPrompt(run.prPrompts.single())
  }

  @Test
  fun `the phase pr prompt falls back to the coded template when none is found`() {
    val repository = featureRepository()

    val run = phasePrRun(repository.repoRoot, database, absent, clock)

    assertIs<PhaseRunResult.Completed>(run.result, run.result.toString())
    assertFallbackPrompt(run.prPrompts.single())
  }

  @Test
  fun `the standalone run blocks at pr when two templates have no default`() {
    val repoRoot = Files.createDirectories(root.resolve("standalone"))
    writeAmbiguousTemplates(repoRoot)

    val run = standalonePrRun(repoRoot, database, absent)

    val report = assertIs<FeatureTaskRuntimeRunReport.Blocked>(run.report, run.report.toString())
    assertEquals(PR_STEP, report.lastIncompletePhase)
    assertNamesBothTemplates(report.blockedReason)
    assertEquals(emptyList(), run.prPrompts, "no pr agent may launch")
  }

  @Test
  fun `phase pr blocks when two templates have no default`() {
    val repository = featureRepository()
    writeAmbiguousTemplates(repository.repoRoot)

    val run = phasePrRun(repository.repoRoot, database, absent, clock)

    val result = assertIs<PhaseRunResult.Blocked>(run.result, run.result.toString())
    assertEquals(PR_STEP, result.stepId)
    assertNamesBothTemplates(result.reason)
    assertEquals(emptyList(), run.launcher.requests, "no agent may launch")
  }

  private fun featureRepository(): PrRunRepository =
    PrRunRepository(Files.createDirectories(root.resolve("phase"))).also { repository ->
      repository.initOnFeatureBranch()
      repository.commit("Feature.kt")
    }

  private fun assertTemplatePrompt(prompt: String) {
    assertRuntimeOwnedRules(prompt)
    assertTrue("`$TEMPLATE_PATH`" in prompt, prompt)
    assertTrue(prompt.indexOf("## What") < prompt.indexOf("## Verification"), prompt)
    assertTrue("Explain the change." in prompt, prompt)
    assertFalse("Ran the linter" in prompt, "checklist items must be dropped: $prompt")
    assertFalse("## Checklist" in prompt, "a checklist-only heading must be dropped: $prompt")
  }

  private fun assertFallbackPrompt(prompt: String) {
    assertRuntimeOwnedRules(prompt)
    listOf("# Summary", "# Feature Flags", "# Media", "# How Has This Been Tested?").forEach { heading ->
      assertTrue(heading in prompt, "the fallback template must carry '$heading': $prompt")
    }
    assertTrue("found no repo-native template. Use the built-in fallback template." in prompt, prompt)
  }

  private fun assertRuntimeOwnedRules(prompt: String) {
    assertTrue("## Repo-Native PR Template Search (mandatory)" in prompt, prompt)
    assertTrue("## Pull request template search result" in prompt, prompt)
    assertFalse("Invoke " in prompt, "the pr prompt must invoke no skill: $prompt")
  }

  private fun assertNamesBothTemplates(reason: String) {
    assertTrue(".github/PULL_REQUEST_TEMPLATE/bugfix.md" in reason, reason)
    assertTrue(".github/PULL_REQUEST_TEMPLATE/feature.md" in reason, reason)
  }

  private fun writeAmbiguousTemplates(repoRoot: Path) {
    writeTemplate(repoRoot, ".github/PULL_REQUEST_TEMPLATE/bugfix.md", "## Bug\n")
    writeTemplate(repoRoot, ".github/PULL_REQUEST_TEMPLATE/feature.md", "## Feature\n")
  }

  private fun writeTemplate(
    repoRoot: Path,
    relative: String,
    content: String,
  ) {
    val target = repoRoot.resolve(relative)
    Files.createDirectories(target.parent)
    Files.writeString(target, content)
  }

  private companion object {
    const val TEMPLATE_PATH = ".github/pull_request_template.md"
    val TEMPLATE =
      """
      ## What
      Explain the change.

      ## Checklist
      - [ ] Ran the linter
      - [x] Updated docs

      ## Verification
      Describe how it was verified.
      """.trimIndent()
  }
}
