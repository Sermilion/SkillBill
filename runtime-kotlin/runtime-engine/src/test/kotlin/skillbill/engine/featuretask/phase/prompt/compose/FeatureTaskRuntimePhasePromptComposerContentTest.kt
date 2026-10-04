
package skillbill.engine.featuretask.phase.prompt.compose

import skillbill.engine.featuretask.slot.audit.AcceptanceAuditPromptSections
import skillbill.engine.featuretask.slot.pullrequest.PullRequestTemplateSearch
import skillbill.infrastructure.contracts.workflow.decomposition.DecompositionManifestSchemaValidator
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.phase.task.FeatureTaskRuntimePhaseWorkflowDefinition
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeatureTaskRuntimePhasePromptComposerContentTest {
  @Test
  fun `implementation and audit may inspect new checkout contracts without replacing their settlement contract`() {
    listOf("implement", "audit").forEach { phaseId ->
      val prompt = composePromptForPhase(phaseId)

      assertContains(
        prompt,
        "Read and edit checkout schemas, Kotlin contract constants, test fixtures, and skill sources",
      )
      assertContains(prompt, "New implementation contracts may be absent from the installed runtime.")
      assertContains(prompt, "block repository work or require permission to inspect the checkout.")
      assertContains(prompt, "Keep this phase's output and settlement on the contract supplied by this briefing")
      assertFalse(prompt.contains("never this checkout"))
    }
  }

  @Test
  fun `phase workers and retries cannot reopen the dispatcher update confirmation`() {
    listOf("preplan", "plan", "implement", "audit", "review", "validate", "pr").forEach { phaseId ->
      val prompt = composePromptForPhase(phaseId)

      assertContains(prompt, "The initial user-facing goal invocation owns update checks and launch confirmation.")
      assertContains(
        prompt,
        "Do not call `mcp__skill-bill__update_check`, ask whether to update, or repeat dispatcher",
      )
      assertContains(prompt, "including on retries and continuation.")
      assertContains(prompt, "Reading the installed skill-bill skill does not make this phase a new invocation.")
    }
  }

  @Test
  fun `each phase carries its own task directive`() {
    val preplanPrompt = composePromptForPhase("preplan")
    val planPrompt = composePromptForPhase("plan")
    val implementPrompt = composePromptForPhase("implement")
    val historyPrompt = composePromptForPhase("write_history")
    val commitPrompt = composePromptForPhase("commit_push")
    val prPrompt = composePromptForPhase("pr")

    assertContains(preplanPrompt, "scaled pre-planning digest")
    assertContains(preplanPrompt, "full preplan covering boundaries")
    assertContains(preplanPrompt, "Do not modify repository files during this phase.")
    assertContains(preplanPrompt, "as prose")
    assertContains(planPrompt, "Do not modify repository files during this phase.")
    assertContains(planPrompt, "upstream preplan digest")
    assertContains(implementPrompt, "Reconcile the repository to the intended state")
    assertContains(implementPrompt, "read the file named by spec_reference")
    assertFalse(implementPrompt.contains("executable_plan"))
    assertTrue(
      !implementPrompt.contains("Do not modify repository files during this phase."),
      "implement must not carry the plan directive",
    )
    assertContains(implementPrompt, "Mutating-phase idempotency contract")
    assertFalse(implementPrompt.contains("implementation_receipt"))
    assertFalse(implementPrompt.contains("Carry this JSON object as the value text"))
    assertTrue(
      !implementPrompt.contains("reconciliation report missing or \"reconciled\" not true fails the schema gate"),
      "implement must not keep the sibling reconciled_state schema-gate prompt",
    )
    assertTrue(
      !planPrompt.contains("Mutating-phase idempotency contract"),
      "non-mutating plan phase must not carry the idempotency directive",
    )
    assertTrue(
      !historyPrompt.contains("Mutating-phase idempotency contract"),
      "non-mutating write_history phase must not carry the idempotency directive",
    )
    mapOf("write_history" to historyPrompt, "pr" to prPrompt).forEach { (phaseId, prompt) ->
      assertFalse(prompt.contains("Invoke "), "the $phaseId prompt must invoke no skill")
    }
    assertContains(historyPrompt, "Always write for `MEDIUM` and `LARGE` features.")
    assertContains(historyPrompt, "## Write/Skip Rules")
    assertContains(historyPrompt, "### Supersession and delete")
    assertContains(historyPrompt, "Acceptance criteria: <count>/<count> implemented")
    assertContains(historyPrompt, "Reason: <why this approach over alternatives — 1-3 lines>")
    assertFalse(historyPrompt.contains("history_result"), "write_history must not ask the agent for history_result")
    assertContains(commitPrompt, "does not launch an agent")
    assertContains(commitPrompt, "records commit_sha")
    assertContains(prPrompt, PullRequestTemplateSearch.SEARCH_ORDER.joinToString(", ") { "`$it`" })
    assertContains(prPrompt, "## Repo-Native PR Template Search (mandatory)")
    assertContains(prPrompt, "`[<ISSUE_KEY>] <descriptive title>`")
    assertContains(prPrompt, "# How Has This Been Tested?")
    assertContains(prPrompt, "create or reuse the open")
    assertFalse(prPrompt.contains("pr_result"), "pr must not ask the agent for pr_result")
  }

  @Test
  fun `test-value discipline renders for plan implement and implement_fix with six element anchors`() {
    val presentPhases =
      listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
      )
    presentPhases.forEach { phaseId ->
      val prompt = composePromptForPhase(phaseId)
      assertContains(prompt, TEST_VALUE_DISCIPLINE_TITLE, false, "title for $phaseId")
      assertContains(prompt, "name the realistic bug", false, "nameable-bug element for $phaseId")
      assertContains(prompt, "critical paths", false, "critical-path element for $phaseId")
      assertContains(
        prompt,
        "observable behavior at boundaries",
        false,
        "boundaries / no structure-coupling element for $phaseId",
      )
      assertContains(prompt, "One strong test per rule", false, "one-test-per-rule element for $phaseId")
      assertContains(
        prompt,
        "empty test_obligations list is a valid",
        false,
        "empty test_obligations guidance for $phaseId",
      )
      assertContains(
        prompt,
        "parity tests or validator-backed rules",
        false,
        "regression / governed carve-out for $phaseId",
      )
    }
  }

  @Test
  fun `test-value discipline is absent from evaluator and non-producer phases`() {
    val absentPhases =
      listOf(
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_REVIEW,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_AUDIT,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_VALIDATE,
        FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_WRITE_HISTORY,
      )
    absentPhases.forEach { phaseId ->
      val prompt = composePromptForPhase(phaseId)
      assertFalse(
        prompt.contains(TEST_VALUE_DISCIPLINE_TITLE),
        "phase $phaseId must not carry the test-value discipline section",
      )
    }
  }

  @Test
  fun `test-value discipline sits immediately after minimalism on mutating phases`() {
    listOf(
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT,
      FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_IMPLEMENT_FIX,
    ).forEach { phaseId ->
      val prompt = composePromptForPhase(phaseId)
      val minimalismIdx = prompt.indexOf("## Minimalism discipline")
      val testValueIdx = prompt.indexOf(TEST_VALUE_DISCIPLINE_TITLE)
      assertTrue(minimalismIdx >= 0, "minimalism present for $phaseId")
      assertTrue(testValueIdx > minimalismIdx, "test-value follows minimalism for $phaseId")
      val between = prompt.substring(minimalismIdx, testValueIdx)
      assertFalse(
        between.indexOf("\n## ", startIndex = 1) >= 0,
        "no other titled section between minimalism and test-value for $phaseId",
      )
    }

    val planPrompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PLAN),
      )
    assertContains(planPrompt, TEST_VALUE_DISCIPLINE_TITLE)
    assertFalse(
      planPrompt.contains("## Minimalism discipline"),
      "plan must not render minimalism; test-value uses its own phase predicate",
    )

    val preplanPrompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor(FeatureTaskRuntimePhaseWorkflowDefinition.PHASE_PREPLAN),
      )
    val ceremonyIdx = preplanPrompt.indexOf("## Runtime ceremony scaling")
    val briefingIdx = preplanPrompt.indexOf("# Feature-task-runtime phase briefing")
    assertTrue(ceremonyIdx >= 0 && briefingIdx > ceremonyIdx)
    assertFalse(preplanPrompt.contains(TEST_VALUE_DISCIPLINE_TITLE))
  }

  @Test
  fun `small prompts encode lighter ceremony and current unit review scope without skipping gates`() {
    val preplanPrompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("preplan", PromptComposerBriefingOptions(FeatureTaskRuntimeFeatureSize.SMALL)),
      )
    val reviewPrompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("review", PromptComposerBriefingOptions(FeatureTaskRuntimeFeatureSize.SMALL)),
      )
    val auditPrompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("audit", PromptComposerBriefingOptions(FeatureTaskRuntimeFeatureSize.SMALL)),
      )

    assertContains(preplanPrompt, "feature_size: SMALL")
    assertContains(preplanPrompt, "preplan_ceremony: light")
    assertContains(reviewPrompt, "review_scope: current_unit_of_work")
    assertContains(reviewPrompt, "current-unit-of-work review scope")
    assertContains(auditPrompt, "audit_ceremony: light")
    assertContains(auditPrompt, "must not skip or weaken review, audit, validation")
  }

  @Test
  fun `upstream outputs flow into the prompt through the briefing text`() {
    val prompt = composePromptForPhase("audit")

    assertContains(prompt, "### from: plan")
    assertContains(prompt, "Fixture plan prose for downstream implement and audit.")
    assertTrue(!prompt.contains("Phase produced a validated output."))
  }

  @Test
  fun `does not instruct the goal-continuation activation flow`() {
    val prompt = composePromptForPhase("plan")

    assertTrue(!prompt.contains("goal-continuation mode"))
    assertTrue(!prompt.contains("First execute this exact command"))
    assertContains(prompt, "do not call `skill-bill workflow continue`")
  }

  @Test
  fun `goal-continuation plan does not treat future acceptance work as a prerequisite`() {
    val prompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("plan"),
      ) { copy(suppressDecomposition = true) }

    assertContains(prompt, "Goal-continuation planning constraint")
    assertContains(prompt, "Never include installer, uninstall, or")
    assertContains(prompt, "install-sync commands in the plan")
    assertContains(prompt, "`./install.sh`")
    assertContains(prompt, "it does not require that work to have already")
    assertContains(prompt, "Never block planning merely because a later implementation or validation action")
    assertContains(prompt, "genuinely missing input or an irreconcilable constraint")
    assertTrue(!prompt.contains("return a blocked plan"))
    assertFalse(prompt.contains("## Subtask Sizing"), "goal-child plan omits the spec directive")
    assertFalse(prompt.contains("## Spec Format Contract"), "goal-child plan omits the spec directive")
    assertContains(prompt, "plain prose")
  }

  @Test
  fun `spec bundle plan carries the feature-spec directive and allows a single subtask`() {
    val prompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("plan"),
      ) { copy(specBundleRequired = true) }

    assertContains(prompt, "## Subtask Sizing")
    assertContains(prompt, "## Spec Format Contract")
    assertContains(prompt, "Spec bundle planning requirement")
    assertContains(prompt, "Write nothing outside that")
    assertFalse(prompt.contains("decomposition_package"))
    assertFalse(prompt.contains("stamps the contract version and phase id itself"))
    assertFalse(prompt.contains("\"mode\": \"direct\""))
    assertFalse(prompt.contains("Do not forward the complete plan envelope"))
    assertFalse(prompt.contains("at least two"), "a spec bundle may hold one subtask")
  }

  @Test
  fun `spec bundle plan carries a manifest template the schema accepts`() {
    val prompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("plan"),
      ) { copy(specBundleRequired = true) }
    val template = prompt.substringAfter("```yaml\n").substringBefore("\n```")
    val manifest =
      template
        .replace("<issue key>", "SKILL-1")
        .replace("<slug>", "feature")
        .replace("<subtask slug>", "part")
        .replace("<subtask name>", "Part")
        .replace("<repository default branch>", "main")

    DecompositionManifestSchemaValidator().validateYamlText(manifest, "decomposition-manifest.yaml")
  }

  @Test
  fun `plan works only from the preplan digest and preplan carries the evidence for it`() {
    listOf(false, true).forEach { bundle ->
      val plan =
        composePhasePrompt(
          PROMPT_COMPOSER_ISSUE_KEY,
          promptComposerBriefingFor("plan"),
        ) { copy(specBundleRequired = bundle) }
      assertContains(plan, "The preplan digest is this phase's only repository knowledge")
      assertContains(plan, "do not re-verify the digest")
    }
    val preplan = composePromptForPhase("preplan")
    assertContains(preplan, "never reads the repository")
    assertContains(preplan, "Settle every question the repository can answer here")
  }

  @Test
  fun `commit_push prompt forbids amending foreign commits and includes every dirty path`() {
    val prompt = composePromptForPhase("commit_push")

    assertContains(prompt, "Commit ownership")
    assertContains(prompt, "Never amend, reset, or restage a commit this runtime does not own")
    assertContains(prompt, "including `.feature-specs/`")
    assertTrue(!prompt.contains("Never list any `.feature-specs/`"))
    assertTrue(!prompt.contains("Feature-spec commit exclusion"))
    assertTrue(
      !prompt.contains("do not add, amend,"),
      "the blanket amend prohibition is replaced by a scope bound to runtime-owned commits",
    )
    assertTrue(!prompt.contains("The committed tree must contain no feature spec"))
  }

  @Test
  fun `commit ownership directive is absent on non-commit phases`() {
    val implementPrompt =
      composePhasePrompt(
        PROMPT_COMPOSER_ISSUE_KEY,
        promptComposerBriefingFor("implement"),
      )

    assertTrue(!implementPrompt.contains("Commit ownership"))
    assertTrue(!implementPrompt.contains("Feature-spec commit exclusion"))
  }

  @Test
  fun `a blank issue key loud-fails`() {
    assertFailsWith<IllegalArgumentException> {
      composePhasePrompt(" ", promptComposerBriefingFor("plan"))
    }
  }

  @Test
  fun `review names its prose findings and verdict and audit names its remaining-criteria value`() {
    val reviewPrompt = composePromptForPhase("review")
    val auditPrompt = composePromptForPhase("audit")

    assertContains(reviewPrompt, "severity (blocker, major, minor, or nit)", false, "review names finding severity")
    assertContains(reviewPrompt, "approved or requested changes", false, "review names the verdict in prose")
    assertFalse(reviewPrompt.contains("VERIFYING phase"), "review carries no envelope verifying-signal addendum")
    assertFalse(auditPrompt.contains("VERIFYING phase"), "audit carries no envelope verifying-signal addendum")
    assertAuditPromptNamesSignal(
      auditPrompt,
      "Report the remaining acceptance criteria in prose",
      "the audit prose signal",
    )
    assertAuditPromptNamesSignal(
      auditPrompt,
      "the whole value is the single line",
      "the remaining-criteria completion contract",
    )
    assertAuditPromptNamesSignal(
      auditPrompt,
      "Only a report that no criteria remain allows downstream review",
      "the audit-specific advance rule",
    )
    assertFalse(auditPrompt.contains("exactly `[]`"), "audit must not demand a literal empty list")
  }

  @Test
  fun `audit excludes explicit and mixed test requirements while inspecting production behavior`() {
    val prompt = composePromptForPhase("audit")

    assertContains(prompt, "Exclude all test requirements from audit, even when the plan or a criterion")
    assertContains(prompt, "For a mixed criterion, evaluate only its production behavior")
    assertContains(prompt, "Omit test-only criteria from the remaining list")
    assertContains(prompt, "including when only test requirements remain")
    val repairPrompt = composePromptForPhase("audit_implement_fix")
    assertContains(repairPrompt, "even when a persisted audit finding or the plan explicitly requests tests")
    assertContains(repairPrompt, "record test-only findings as excluded from audit")
    assertContains(prompt, "Audit is read-only: do not edit files or repair gaps")
    assertContains(prompt, "inspect only the unresolved criteria in the last accepted audit report")
    assertContains(prompt, "Do not spawn subagents or invoke repair skills")
    assertContains(prompt, "validate owns tests and failures")
    assertTrue(!prompt.contains("TEST EXCLUSION"))
    assertTrue(!prompt.contains("free-form note prose"))
  }

  @Test
  fun `audit remaining-criteria briefing is identical across shipped platform pack gates`() {
    val briefing = promptComposerBriefingFor("audit")
    val baseline = composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, briefing)
    val slugs = shippedPlatformPackSlugs()
    assertTrue(slugs.isNotEmpty(), "expected shipped platform packs under platform-packs/")
    (slugs + "unshipped-pack").forEach { slug ->
      val prompt =
        composePhasePrompt(PROMPT_COMPOSER_ISSUE_KEY, briefing) {
          copy(
            packCollectAllCommand = "collect-all-$slug",
            packConfirmationGateCommand = "confirm-$slug",
            packBuildCommand = "build-$slug",
          )
        }
      assertEquals(baseline, prompt, "audit remaining-criteria contract forked for pack $slug")
      assertContains(prompt, AcceptanceAuditPromptSections.DIRECTIVE)
      assertTrue(!prompt.contains("collect-all-$slug"))
      assertTrue(!prompt.contains("confirm-$slug"))
      assertTrue(!prompt.contains("build-$slug"))
    }
  }

  @Test
  fun `commit-focused accounting is runtime-owned and never asked of a phase agent`() {
    listOf("review", "audit").forEach { phaseId ->
      val prompt = composePromptForPhase(phaseId)
      assertFalse(prompt.contains("commit_focused_accounting"), "$phaseId must not ask for the accounting record")
      assertFalse(prompt.contains("commit_sequence_digest"), "$phaseId must not ask for the sequence identity")
    }
  }

  @Test
  fun `non-verifying phases carry no verifying-signal addendum`() {
    listOf("preplan", "plan", "implement", "validate", "write_history", "commit_push", "pr").forEach { phaseId ->
      val prompt = composePromptForPhase(phaseId)
      assertTrue(!prompt.contains("VERIFYING phase"), "$phaseId must not carry the verifying-signal addendum")
    }
  }
}
