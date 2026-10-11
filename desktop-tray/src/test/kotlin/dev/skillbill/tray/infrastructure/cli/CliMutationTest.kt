package dev.skillbill.tray.infrastructure.cli

import dev.skillbill.tray.application.Outcome
import dev.skillbill.tray.domain.*
import dev.skillbill.tray.domain.Retention
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import java.nio.file.Files
import java.time.Instant

class CliMutationTest {
  @Test
  fun `goal controls use exact argv and hide child output after a successful poll`() = runBlocking {
    assumeFalse(System.getProperty("os.name").startsWith("Windows", true))
    val temp = Files.createTempDirectory("skillbill-cli-mutation")
    val capture = temp.resolve("args.txt")
    val script = temp.resolve("skill-bill")
    Files.writeString(script, "#!/bin/sh\nprintf '%s\\n' \"\$@\" >> '${capture}'\nif [ \"\$1\" = work ]; then printf '%s' '{\"contract_version\":\"1.0\",\"items\":[],\"observed_at\":\"2026-10-11T10:00:00Z\",\"finished_within\":\"24h\",\"diagnostics\":[]}'; exit 0; fi\necho PRIVATE_STDOUT\necho PRIVATE_STDERR >&2\nexit 7\n")
    script.toFile().setExecutable(true)
    val source = CliBoardSource(resolver = CliResolver())
    source.setExecutableOverride(script.toString())
    assertTrue(source.board(Retention.DAY) is Outcome.Value)
    val item = item()
    source.acceptBoard(Board(Instant.parse("2026-10-11T10:00:00Z"), Retention.DAY, listOf(item)))
    val result = source.pause(item)
    assertTrue(result is Outcome.Failure)
    val failure = result as Outcome.Failure
    assertEquals("command_failed", failure.problem.code)
    assertFalse(failure.problem.summary.contains("PRIVATE"))
    source.stop(item)
    assertEquals(listOf("work", "board", "--format", "json", "--finished-within", "24h", "goal", "pause", "SKILL-418", "--repo-root", "/repo with spaces", "goal", "stop", "SKILL-418", "--repo-root", "/repo with spaces"), Files.readAllLines(capture))
  }

  private fun item() = WorkItem(
    identity = WorkIdentity("workflow", "repo"),
    workflowId = "workflow",
    invocationId = null,
    repositoryRoot = "/repo with spaces",
    branch = "main",
    issueKey = "SKILL-418",
    family = WorkFamily.FEATURE_GOAL,
    scope = ExecutionScope.WORKFLOW,
    phaseId = null,
    activity = null,
    lifecycle = Lifecycle.ACTIVE,
    freshness = Freshness.FRESH,
    summary = "summary",
    currentStepId = "implement",
    currentStepLabel = "Implement",
    progressCompleted = null,
    progressTotal = null,
    activeDurationMs = null,
    activeDurationAsOf = null,
    subtaskActiveDurationMs = null,
    subtaskActiveDurationAsOf = null,
    pauseRequested = false,
    pauseReasonCode = null,
    pauseReason = null,
    planningState = null,
    plannedSubtasks = null,
    totalSubtasks = null,
    planningWaveSubtaskIds = emptyList(),
    executionCount = null,
    executionTotal = null,
    updatedAt = Instant.parse("2026-10-11T10:00:00Z"),
    finishedAt = null,
    finishedAtSource = null,
  )
}
