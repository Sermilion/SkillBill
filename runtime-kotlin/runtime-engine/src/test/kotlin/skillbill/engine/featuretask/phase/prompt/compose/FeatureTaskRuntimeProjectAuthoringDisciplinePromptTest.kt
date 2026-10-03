package skillbill.engine.featuretask.phase.prompt.compose

import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.lifecycle.remediation.featureTaskRuntimeRepairReceiptFromProse
import skillbill.engine.featuretask.runner.BranchSetupTestConfig
import skillbill.engine.featuretask.runner.RuntimeHarnessConfig
import skillbill.engine.featuretask.runner.RuntimeRecordingLauncher
import skillbill.engine.featuretask.runner.defaultPhaseOutput
import skillbill.engine.featuretask.runner.facts
import skillbill.engine.featuretask.runner.phaseIdFromPrompt
import skillbill.engine.featuretask.runner.runnerHarness
import skillbill.workflow.model.goalreview.GoalSubtaskReviewCompactFinding
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val HEADING = "## Project authoring discipline (discover before write)"
private val AUTHORING_PHASES = listOf("implement", "simplify", "audit_implement_fix", "implement_fix")
private val NON_AUTHORING_PHASES =
  listOf("preplan", "plan", "audit", "review", "verify_findings", "write_history", "commit_push", "pr", "validate")
private val CONCRETE_TOOLS = listOf("Spotless", "Detekt", "Gradle", "Kotlin", "npm", "pytest")

class FeatureTaskRuntimeProjectAuthoringDisciplinePromptTest {
  @Test
  fun `every authoring phase carries the substantive discipline`() {
    AUTHORING_PHASES.forEach { phaseId ->
      val prompt = composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, promptComposerBriefingFor(phaseId))

      assertSubstantiveDiscipline(prompt, phaseId)
    }
  }

  @Test
  fun `continuation composition keeps the discipline`() {
    val continued =
      composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, promptComposerBriefingFor("implement")) {
        copy(implementationContinuation = promptComposerImplementationContinuation())
      }

    assertSubstantiveDiscipline(continued, "implement continuation")
  }

  @Test
  fun `read-only and finalizing phases receive no authoring authority`() {
    NON_AUTHORING_PHASES.forEach { phaseId ->
      val prompt = composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, promptComposerBriefingFor(phaseId))

      assertFalse(prompt.contains(HEADING), "$phaseId must not carry the authoring discipline")
    }
  }

  @Test
  fun `guidance names no concrete tool or platform`() {
    val prompt = composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, promptComposerBriefingFor("implement"))
    val guidance = prompt.substringAfter(HEADING).substringBefore("\n## ")

    CONCRETE_TOOLS.forEach { tool -> assertFalse(guidance.contains(tool), "guidance must not name $tool") }
  }

  @Test
  fun `audit inspection has no compile exception and validate keeps independent discovery`() {
    val audit = composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, promptComposerBriefingFor("audit"))
    val validate = composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, promptComposerBriefingFor("validate"))

    assertFalse(audit.contains("application compiles"))
    assertFalse(audit.contains("validation_gate.build_command to check"))
    assertContains(audit, "Inspect production code without editing it")
    assertContains(validate, "not delivered here in full and never clear a build or validation gate")
  }

  @Test
  fun `mixed-language repository context drives the prompt without runtime discovery or tool execution`() {
    val repo = createMixedLanguageRepository()
    try {
      val sentinel = repo.resolve("unrelated.txt")
      val sentinelBefore = sentinel.readBytes()
      val launcher = RuntimeRecordingLauncher { request -> facts(defaultPhaseOutput(request)) }
      val harness =
        runnerHarness(
          RuntimeHarnessConfig(
            branchSetup = BranchSetupTestConfig(gitOperations = RecordingWorkflowGitOperations()),
            repoRoot = repo,
          ).copy(launcher = launcher),
        )

      harness.runner.run(harness.request())

      val implement =
        launcher.requests
          .mapNotNull { it.skillRunRequest.promptOverride }
          .first { phaseIdFromPrompt(it) == "implement" }
      assertSubstantiveDiscipline(implement, "implement")
      listOf("web-typecheck", "9.1.0", "svc-lint", "2.4.1", "missing-tool").forEach { synthetic ->
        assertFalse(implement.contains(synthetic), "the runtime must not discover $synthetic itself")
      }
      assertTrue(launcher.requests.all { it.skillRunRequest.promptOverride != null }, "only agent launches occur")
      assertContentEquals(sentinelBefore, sentinel.readBytes())
    } finally {
      repo.toFile().deleteRecursively()
    }
  }

  @Test
  fun `implement_fix report parsing ignores deferral notes and synthesizes nothing`() {
    val carried = listOf(GoalSubtaskReviewCompactFinding("major", "Policy", "stale comment", "F-001"))

    val receipt =
      featureTaskRuntimeRepairReceiptFromProse(
        "F-001 is fixed.\ndeferred: lint command not run, config ambiguous; F-009 remains",
        carried,
        "a".repeat(40),
        1,
      )

    assertEquals(listOf("F-001"), receipt.entries.map { it.findingId })
  }

  private fun assertSubstantiveDiscipline(
    prompt: String,
    label: String,
  ) {
    assertContains(prompt, HEADING, false, label)
    assertContains(prompt, "Before the first code write", false, label)
    assertContains(prompt, "whenever owned or repair scope changes", false, label)
    assertContains(prompt, "establish its actual input and rewrite scope", false, label)
    assertContains(prompt, "Never compile, build, execute tests, or run a full repository check", false, label)
    assertContains(prompt, "rerun the safe command before completion", false, label)
    assertContains(prompt, "never as passed", false, label)
  }

  private fun createMixedLanguageRepository(): Path {
    val repo = Files.createTempDirectory("mixed-language-authoring")
    repo.resolve("AGENTS.md").writeText("root rules: keep modules independent\n")
    repo.resolve("web").createDirectories()
    repo.resolve("web/AGENTS.md").writeText("web rules: typed lint is compilation-coupled\n")
    repo.resolve("web/package.json").writeText("{\"devDependencies\":{\"web-format\":\"9.1.0\"}}\n")
    repo.resolve("web/lint.config").writeText("tool=web-typecheck\n")
    repo.resolve("svc").createDirectories()
    repo.resolve("svc/lint.cfg").writeText("tool=svc-lint\nversion=2.4.1\n")
    repo.resolve(".tool-versions").writeText("missing-tool 1.0.0\n")
    repo.resolve("unrelated.txt").writeText("dirty sentinel\n")
    return repo
  }
}
