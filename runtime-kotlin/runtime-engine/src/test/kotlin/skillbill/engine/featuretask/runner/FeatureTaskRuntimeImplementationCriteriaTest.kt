package skillbill.engine.featuretask.runner

import org.junit.jupiter.api.io.TempDir
import skillbill.contracts.JsonCodec
import skillbill.contracts.workflow.featuretask.FEATURE_TASK_RUNTIME_CONTRACT_VERSION
import skillbill.engine.RecordingWorkflowGitOperations
import skillbill.engine.featuretask.model.core.FeatureTaskRuntimeRunReport
import skillbill.engine.featuretask.slot.implementation.ImplementationPromptSections
import skillbill.engine.featuretask.slot.implementationCheckOutput
import skillbill.engine.featuretask.slot.isImplementationCheckPrompt
import skillbill.engine.featuretask.slot.validJsonOutput
import skillbill.ports.workflow.model.WorkflowStateRecord
import skillbill.workflow.engine.model.DurableWorkflowArtifactFamily
import skillbill.workflow.taskruntime.artifact.asWorkflowArtifactEntry
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationCheckDecision
import skillbill.workflow.taskruntime.model.persistence.FeatureTaskRuntimeImplementationTurnRole
import skillbill.workflow.taskruntime.model.persistence.featureTaskRuntimeImplementationTurnRecords
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimeFailureDisposition
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerAction
import skillbill.workflow.taskruntime.model.phase.FeatureTaskRuntimePhaseLedgerEntry
import skillbill.workflow.taskruntime.model.repair.FeatureTaskRuntimeOperatorBlockRetry
import skillbill.workflow.taskruntime.phaseartifacts.asPendingForOperatorResume
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureTaskRuntimeImplementationCriteriaTest {
  @TempDir
  lateinit var repo: Path

  @Test
  fun `implement closes three criteria one fresh worker and check at a time before simplify`() {
    val root = Files.createTempDirectory("implementation-criteria")
    try {
      val script =
        CriterionScript(
          checkOutput = { id, ordinal ->
            if (id == "AC-002" && ordinal == 1) implementationCheckOutput("unsatisfied", GAP) else SATISFIED_CHECK
          },
          onWorker = { turn ->
            if (turn.criterionId != "AC-001") assertTrue(Files.exists(root.resolve("AC-001.kt")))
            Files.writeString(root.resolve("${turn.criterionId}.kt"), "worker ${turn.worker}")
          },
        )
      val harness = criterionHarness(script, THREE_CRITERIA, root)

      assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

      assertEquals(
        listOf("W1:AC-001", "C:AC-001", "W1:AC-002", "C:AC-002", "W2:AC-002", "C:AC-002", "W1:AC-003", "C:AC-003"),
        script.labels,
      )
      assertEquals(
        List(script.labels.size) { "implement" } + "simplify",
        harness.launchedPromptPhaseOrder().take(script.labels.size + 1),
      )
      assertPromptsCarryOnlyTheirTurn(script)
      assertEquals(THREE_CRITERIA.map { it.substringBefore('.') }, progressOf(harness).closedIds)
      assertEquals(script.labels.size, progressOf(harness).launchCount)
      assertEquals(1, implementCompletions(harness))
      val receipt = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("implement")?.outputArtifact)
      assertContains(receipt, "Implement closed 3 acceptance criteria one at a time.")
      assertContains(receipt, "SENTINEL-AC-002-W2")
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  @Test
  fun `a check without a recognized verdict cannot close the criterion`() {
    listOf(validJsonOutput("implement"), implementationCheckOutput(verdict = "approved")).forEach { rejected ->
      val script = CriterionScript(checkOutput = { _, ordinal -> if (ordinal == 1) rejected else SATISFIED_CHECK })
      val harness = criterionHarness(script, ONE_CRITERION)

      val report = harness.runner.run(harness.request())

      assertIs<FeatureTaskRuntimeRunReport.Completed>(report, report.toString())
      assertEquals(listOf("W1:AC-001", "C:AC-001", "C:AC-001"), script.labels)
      assertEquals(listOf(FeatureTaskRuntimeImplementationCheckDecision.SATISFIED), checkDecisions(harness))
    }
  }

  @Test
  fun `a check that edits an already dirty source file cannot close the criterion`() {
    val git = RecordingWorkflowGitOperations()
    val script =
      CriterionScript(
        checkOutput = { _, ordinal ->
          if (ordinal == 1) git.contentIdentities["src/Foo.kt"] = "edited-by-check"
          SATISFIED_CHECK
        },
      )
    val harness = criterionHarness(script, ONE_CRITERION, git = git)

    assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

    assertEquals(listOf("W1:AC-001", "C:AC-001", "W2:AC-001", "C:AC-001"), script.labels)
    assertContains(script.prompts[2], "The check of AC-001 edited [src/Foo.kt]")
    assertEquals(
      listOf(
        FeatureTaskRuntimeImplementationCheckDecision.UNSATISFIED,
        FeatureTaskRuntimeImplementationCheckDecision.SATISFIED,
      ),
      checkDecisions(harness),
    )
  }

  @Test
  fun `the worker cap blocks the active criterion until an explicit resume grants three workers once`() {
    val script =
      CriterionScript(
        checkOutput = { id, ordinal ->
          if (id == "AC-001") SATISFIED_CHECK else implementationCheckOutput("unsatisfied", "GAP-$id-$ordinal")
        },
      )
    val first = criterionHarness(script, TWO_CRITERIA)
    assertCapBlocked(first.runner.run(first.request()), "GAP-AC-002-3")
    assertEquals(listOf("W1:AC-001", "C:AC-001") + exhaustedTurns("AC-002"), script.labels)
    assertEquals(
      FeatureTaskRuntimeFailureDisposition.NEEDS_USER_ACTION,
      first.recorder.loadPhaseRecords(WORKFLOW_ID)?.get("implement")?.failureDisposition,
    )
    val exhaustedLaunches = script.labels.size

    val restarted = criterionHarness(script, TWO_CRITERIA, repository = first.repository)
    assertCapBlocked(restarted.runner.run(restarted.request()), "GAP-AC-002-3")
    assertEquals(exhaustedLaunches, script.labels.size)

    reopenImplement(restarted)
    val resumed = criterionHarness(script, TWO_CRITERIA, repository = first.repository)
    assertCapBlocked(resumed.runner.run(resumed.request()), "GAP-AC-002-6")
    assertEquals(exhaustedTurns("AC-002"), script.labels.drop(exhaustedLaunches))
    assertContains(script.prompts[exhaustedLaunches], "GAP-AC-002-3")
    assertEquals(1, turnRecords(resumed).count { it.marker.role == FeatureTaskRuntimeImplementationTurnRole.REOPEN })
    val progress = progressOf(resumed)
    assertEquals(listOf("AC-001"), progress.closedIds)
    assertEquals(3, progress.workerCount)
    val grantedLaunches = script.labels.size

    val afterGrant = criterionHarness(script, TWO_CRITERIA, repository = first.repository)
    assertIs<FeatureTaskRuntimeRunReport.Blocked>(afterGrant.runner.run(afterGrant.request()))
    assertEquals(grantedLaunches, script.labels.size)
  }

  @Test
  fun `a reconstructed runner replays the pending check without spending another worker`() {
    val kills = mutableSetOf("C:AC-001", "W1:AC-002")
    val script = CriterionScript(kill = { label -> kills.remove(label) })
    val first = criterionHarness(script, TWO_CRITERIA)
    runCatching { first.runner.run(first.request()) }
    assertEquals(1, progressOf(first).workerCount)

    val second = criterionHarness(script, TWO_CRITERIA, repository = first.repository)
    runCatching { second.runner.run(second.request()) }
    val afterSecond = progressOf(second)
    assertEquals(listOf("AC-001"), afterSecond.closedIds)
    assertEquals(1, afterSecond.workerCount)

    val third = criterionHarness(script, TWO_CRITERIA, repository = first.repository)
    assertIs<FeatureTaskRuntimeRunReport.Completed>(third.runner.run(third.request()))

    assertEquals(listOf("W1:AC-001", "C:AC-001", "C:AC-001", "W1:AC-002", "C:AC-002"), script.labels)
    assertEquals(listOf("AC-001", "AC-002"), progressOf(third).closedIds)
    assertEquals(1, implementCompletions(third))
  }

  @Test
  fun `a rejected worker reservation launches no further worker`() {
    val script = CriterionScript()
    val harness = criterionHarness(script, TWO_CRITERIA)
    harness.repository.failSaveWhen = { row ->
      progressPayloadOf(row)?.let { (it["current_index"] as? Number)?.toInt() == 1 && reserved(it) } == true
    }

    val report = runCatching { harness.runner.run(harness.request()) }.getOrNull()

    assertFalse(report is FeatureTaskRuntimeRunReport.Completed)
    assertEquals(listOf("W1:AC-001", "C:AC-001"), script.labels)
    val progress = progressOf(harness)
    assertEquals(listOf("AC-001"), progress.closedIds)
    assertEquals(0, progress.workerCount)
  }

  @Test
  fun `corrupt present progress fails loudly without launching work or resetting closure`() {
    val script = CriterionScript()
    val harness = criterionHarness(script, TWO_CRITERIA)
    val corrupt =
      linkedMapOf(
        "contract_version" to "0.1",
        "criterion_ids" to listOf("AC-001", "AC-002"),
        "current_index" to 1,
        "worker_count" to 0,
        "launch_count" to 2,
        "closed_ids" to emptyList<String>(),
      )
    harness.repository.replaceTaskRuntimeArtifacts(
      WORKFLOW_ID,
      harness.repository.taskRuntimeArtifacts(WORKFLOW_ID) + (PROGRESS_KEY to corrupt),
    )

    val report = runCatching { harness.runner.run(harness.request()) }.getOrNull()

    assertFalse(report is FeatureTaskRuntimeRunReport.Completed)
    assertEquals(emptyList<String>(), script.labels)
    val retained = criterionProgressMap(harness.repository.taskRuntimeArtifacts(WORKFLOW_ID)[PROGRESS_KEY])
    assertEquals(emptyList<Any?>(), retained?.get("closed_ids"))
    assertEquals(1, (retained?.get("current_index") as? Number)?.toInt())
  }

  @Test
  fun `a spec without a parseable acceptance section keeps whole-spec implement`() {
    val root = Files.createTempDirectory("implementation-criteria-legacy")
    try {
      val spec = root.resolve(SPEC_REFERENCE)
      Files.createDirectories(spec.parent)
      Files.writeString(spec, "# Legacy spec\n\n## Intended Outcome\nKeep whole-spec implement.\n")
      val harness = runnerHarness(RuntimeHarnessConfig(repoRoot = root, launcher = defaultPhaseAwareLauncher()))

      assertIs<FeatureTaskRuntimeRunReport.Completed>(harness.runner.run(harness.request()))

      assertEquals(listOf("implement", "simplify"), harness.launchedPromptPhaseOrder().take(2))
      val implementPrompt = requireNotNull(harness.launcher.requests.first().skillRunRequest.promptOverride)
      assertContains(implementPrompt, ImplementationPromptSections.IMPLEMENT_DIRECTIVE)
      assertFalse(WORKER_LINE.containsMatchIn(implementPrompt))
      assertNull(harness.recorder.loadImplementationProgress(WORKFLOW_ID))
    } finally {
      root.toFile().deleteRecursively()
    }
  }

  private fun assertPromptsCarryOnlyTheirTurn(script: CriterionScript) {
    script.prompts.forEachIndexed { index, prompt ->
      assertContains(prompt, SPEC_REFERENCE)
      assertEquals(THREE_CRITERIA.first { it.startsWith(script.turns[index].criterionId) }, script.turns[index].text)
      script.sentinels.forEach { assertFalse(it in prompt, "Turn $index carried $it.") }
      assertEquals(index == RETRY_WORKER_INDEX, GAP in prompt, "Turn $index gap delivery.")
      if (!script.turns[index].check) assertContains(prompt, "current working tree")
    }
  }

  private fun assertCapBlocked(
    report: FeatureTaskRuntimeRunReport,
    latestGap: String,
  ) {
    val blocked = assertIs<FeatureTaskRuntimeRunReport.Blocked>(report, report.toString())
    assertEquals("implement", blocked.lastIncompletePhase)
    assertContains(blocked.blockedReason, "Acceptance criterion AC-002 used all 3 workers")
    assertContains(blocked.blockedReason, latestGap)
  }

  private fun reopenImplement(harness: RunnerHarness) {
    val records = requireNotNull(harness.recorder.loadPhaseRecords(WORKFLOW_ID)).toMutableMap()
    val blocked = requireNotNull(records["implement"])
    records["implement"] = blocked.asPendingForOperatorResume()
    val ledger = harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty()
    val timestamp = Instant.parse("2026-10-21T12:00:00Z")
    val retry =
      FeatureTaskRuntimePhaseLedgerEntry(
        action = FeatureTaskRuntimePhaseLedgerAction.RETRY,
        sequenceNumber = (ledger.maxOfOrNull { it.sequenceNumber } ?: 0) + 1,
        timestamp = timestamp,
        phaseId = "implement",
        attemptCount = blocked.attemptCount,
      )
    val artifacts = harness.repository.taskRuntimeArtifacts(WORKFLOW_ID).toMutableMap()
    artifacts[DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_RECORDS.label()] =
      records.mapValues { it.value.asWorkflowArtifactEntry() }
    artifacts[DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_PHASE_LEDGER.label()] =
      (ledger + retry).map { it.asWorkflowArtifactEntry() }
    artifacts[DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_OPERATOR_BLOCK_RETRY.label()] =
      FeatureTaskRuntimeOperatorBlockRetry("implement", "Give AC-002 new workers.", timestamp.toString())
        .asWorkflowArtifactEntry(requireNotNull(blocked.blockedReason), listOf("implement"))
    harness.repository.replaceTaskRuntimeArtifacts(WORKFLOW_ID, artifacts)
  }

  private fun criterionHarness(
    script: CriterionScript,
    criteria: List<String>,
    root: Path = repo,
    git: RecordingWorkflowGitOperations = RecordingWorkflowGitOperations(),
    repository: InMemoryRuntimeWorkflowRepository = InMemoryRuntimeWorkflowRepository(),
  ): RunnerHarness =
    runnerHarness(
      RuntimeHarnessConfig(
        repoRoot = root,
        acceptanceCriteria = criteria,
        launcher = script.launcher,
        branchSetup = BranchSetupTestConfig(gitOperations = git),
      ),
      repository = repository,
    )

  private fun progressOf(harness: RunnerHarness) =
    requireNotNull(harness.recorder.loadImplementationProgress(WORKFLOW_ID))

  private fun turnRecords(harness: RunnerHarness) =
    featureTaskRuntimeImplementationTurnRecords(harness.recorder.loadImplementationAttempts(WORKFLOW_ID).orEmpty())

  private fun checkDecisions(harness: RunnerHarness): List<FeatureTaskRuntimeImplementationCheckDecision?> =
    turnRecords(harness)
      .filter { it.marker.role == FeatureTaskRuntimeImplementationTurnRole.CHECK_SETTLED }
      .map { it.marker.decision }

  private fun implementCompletions(harness: RunnerHarness): Int =
    harness.recorder.loadPhaseLedger(WORKFLOW_ID).orEmpty().count {
      it.phaseId == "implement" && it.action == FeatureTaskRuntimePhaseLedgerAction.COMPLETE
    }

  private fun exhaustedTurns(criterionId: String): List<String> =
    (1..3).flatMap { listOf("W$it:$criterionId", "C:$criterionId") }

  private fun reserved(progress: Map<*, *>): Boolean = (progress["worker_count"] as? Number)?.toInt() == 1

  private fun progressPayloadOf(row: WorkflowStateRecord): Map<*, *>? =
    JsonCodec.parseObjectOrNull(row.artifactsJson)
      ?.let(JsonCodec::jsonElementToValue)
      ?.let(JsonCodec::anyToStringAnyMap)
      ?.get(PROGRESS_KEY)
      ?.let(::criterionProgressMap)

  private fun criterionProgressMap(value: Any?): Map<*, *>? =
    when (value) {
      is Map<*, *> -> if ("criterion_ids" in value) value else value.values.firstNotNullOfOrNull(::criterionProgressMap)
      else -> null
    }

  private companion object {
    const val GAP = "GAP-AC-002 blank headers are still accepted"
    const val RETRY_WORKER_INDEX = 4
    val PROGRESS_KEY = DurableWorkflowArtifactFamily.FEATURE_TASK_RUNTIME_IMPLEMENTATION_PROGRESS.label()
    val SATISFIED_CHECK = implementationCheckOutput()
    val WORKER_LINE = Regex("You are worker (\\d+) of at most")
    val ONE_CRITERION = listOf("AC-001. The parser reads the header line.")
    val TWO_CRITERIA = ONE_CRITERION + "AC-002. The parser rejects a blank header."
    val THREE_CRITERIA = TWO_CRITERIA + "AC-003. The formatter writes the header back."
  }
}

private data class CriterionTurn(
  val check: Boolean,
  val criterionId: String,
  val text: String,
  val worker: Int?,
) {
  val label: String get() = if (check) "C:$criterionId" else "W$worker:$criterionId"
}

private class CriterionScript(
  private val checkOutput: (criterionId: String, ordinal: Int) -> String = { _, _ -> implementationCheckOutput() },
  private val onWorker: (CriterionTurn) -> Unit = {},
  private val kill: (label: String) -> Boolean = { false },
) {
  val prompts = mutableListOf<String>()
  val sentinels = mutableListOf<String>()
  private val checks = mutableMapOf<String, Int>()

  val turns: List<CriterionTurn> get() = prompts.map(::criterionTurnOf)
  val labels: List<String> get() = turns.map(CriterionTurn::label)

  val launcher =
    RuntimeRecordingLauncher { request ->
      val prompt = requireNotNull(request.skillRunRequest.promptOverride)
      if (phaseIdFromPrompt(prompt) != "implement") return@RuntimeRecordingLauncher facts(defaultPhaseOutput(request))
      prompts += prompt
      val turn = criterionTurnOf(prompt)
      check(!kill(turn.label)) { "simulated process kill during ${turn.label}" }
      if (turn.check) {
        val ordinal = (checks[turn.criterionId] ?: 0) + 1
        checks[turn.criterionId] = ordinal
        facts(checkOutput(turn.criterionId, ordinal))
      } else {
        onWorker(turn)
        val sentinel = "SENTINEL-${turn.criterionId}-W${turn.worker}"
        sentinels += sentinel
        facts(workerOutput("$sentinel changed the source for ${turn.criterionId}."))
      }
    }
}

private val ASSIGNED_CRITERION = Regex("only this criterion[^\\n]*\\n\\n([^\\n]+)")
private val WORKER_NUMBER = Regex("You are worker (\\d+) of at most")

private fun criterionTurnOf(prompt: String): CriterionTurn {
  val text = requireNotNull(ASSIGNED_CRITERION.find(prompt)?.groupValues?.get(1)) { "No assigned criterion: $prompt" }
  return CriterionTurn(
    check = isImplementationCheckPrompt(prompt),
    criterionId = text.substringBefore('.'),
    text = text,
    worker = WORKER_NUMBER.find(prompt)?.groupValues?.get(1)?.toInt(),
  )
}

private fun workerOutput(value: String): String =
  """
  {
    "contract_version": "$FEATURE_TASK_RUNTIME_CONTRACT_VERSION",
    "phase_id": "implement",
    "status": "completed",
    "summary": "Worker settled its criterion.",
    "produced_outputs": {"value": "$value"}
  }
  """.trimIndent()
