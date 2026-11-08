package skillbill.infrastructure.sqlite

import skillbill.ports.idestatus.AgentBuildRunRepository
import skillbill.ports.idestatus.model.AgentBuildHistory
import skillbill.ports.idestatus.model.AgentBuildOutcome
import skillbill.ports.idestatus.model.AgentBuildPrevious
import skillbill.ports.idestatus.model.AgentBuildRunStart
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AgentBuildRunStoreTest {
  private val base = Instant.parse("2026-01-01T00:00:00Z")
  private val factory =
    sqliteDatabaseSessionFactory(userHome = Files.createTempDirectory("agent-build-runs"), environment = emptyMap())

  private fun hour(index: Int): Instant = base.plus(Duration.ofHours(index.toLong()))

  private fun startRow(
    buildId: String,
    startedAt: Instant,
    runId: String = "run-$buildId",
    parentRunId: String? = null,
    command: String = "./gradlew build",
  ) = AgentBuildRunStart(
    buildId = buildId,
    runId = runId,
    parentRunId = parentRunId,
    repositoryIdentity = "repo",
    command = command,
    shortCommand = "build",
    tool = "gradle",
    projectKind = "gradle",
    pid = 4242L,
    startedAt = startedAt,
  )

  private fun AgentBuildRunRepository.settled(
    row: AgentBuildRunStart,
    durationMs: Long,
    outcome: AgentBuildOutcome = AgentBuildOutcome.PASSED,
  ) {
    start(row)
    settle(row.buildId, row.startedAt.plusMillis(durationMs), outcome, 0)
  }

  private fun <T> write(block: (AgentBuildRunRepository) -> T): T =
    factory.selfManagedWrite { unitOfWork -> block(unitOfWork.agentBuildRuns) }

  private fun <T> read(block: (AgentBuildRunRepository) -> T): T =
    factory.read { unitOfWork -> block(unitOfWork.agentBuildRuns) }

  @Test
  fun `usual is the median of the five newest settled builds and floors an even pair`() {
    write { store ->
      (0..5).forEach { store.settled(startRow("old-$it", hour(it)), 10_000) }
      listOf(100L, 500L, 300L, 900L, 700L).forEachIndexed { index, ms ->
        store.settled(startRow("new-$index", hour(10 + index)), ms)
      }
      listOf(100L, 201L, 300L, 999L).forEachIndexed { index, ms ->
        store.settled(startRow("even-$index", hour(index), command = "./gradlew check"), ms)
      }
    }

    assertEquals(500L, read { it.history("repo", "./gradlew build", hour(100)).usualMs })
    assertEquals(250L, read { it.history("repo", "./gradlew check", hour(100)).usualMs })
  }

  @Test
  fun `history ignores other scopes, open, abandoned and later builds`() {
    write { store ->
      store.settled(startRow("a", hour(1)), 1_000)
      store.settled(startRow("b", hour(2)), 2_000, AgentBuildOutcome.FAILED)
      store.settled(startRow("other-repo", hour(3)).copy(repositoryIdentity = "repo-2"), 99_999)
      store.settled(startRow("other-command", hour(3), command = "./gradlew test"), 99_999)
      store.start(startRow("open", hour(3)))
      store.start(startRow("abandoned", hour(4), runId = "restarted"))
      store.abandonOpen("restarted")
      store.settled(startRow("later", hour(5)), 99_999)
    }

    assertEquals(
      AgentBuildHistory(
        usualMs = 1_500L,
        previous = AgentBuildPrevious(AgentBuildOutcome.FAILED, 2_000L, hour(2).plusMillis(2_000)),
      ),
      read { it.history("repo", "./gradlew build", hour(5)) },
    )
    assertEquals(AgentBuildHistory(null, null), read { it.history("repo", "./gradlew build", hour(1)) })
  }

  @Test
  fun `settle keeps the newest twenty settled builds and never prunes open ones`() {
    write { store ->
      (0..19).forEach { store.settled(startRow("b$it", hour(it)), 1_000) }
      store.start(startRow("open", hour(0)))
      store.settled(startRow("b20", hour(20)), 1_000)
    }

    val remaining = read { store -> (0..20).filter { store.latestForRun(setOf("run-b$it")) != null } }
    assertEquals((1..20).toList(), remaining)
    val open = read { it.latestForRun(setOf("run-open")) }
    assertEquals("open", open?.buildId)
    assertNull(open?.endedAt)

    assertFalse(write { it.settle("b20", hour(30), AgentBuildOutcome.FAILED, 1) })
    val kept = read { it.latestForRun(setOf("run-b20")) }
    assertEquals(hour(20).plusMillis(1_000), kept?.endedAt)
    assertEquals(AgentBuildOutcome.PASSED, kept?.outcome)
  }

  @Test
  fun `latest for run prefers a running build and leaves abandoned rows without an end`() {
    write { store ->
      store.start(startRow("child", hour(1), runId = "sub", parentRunId = "goal"))
      store.settled(startRow("own", hour(2), runId = "goal"), 3_000)
    }
    assertEquals("child", read { it.latestForRun(setOf("goal")) }?.buildId)

    write { store -> assertEquals(1, store.abandonOpen("sub")) }

    val abandoned = read { it.latestForRun(setOf("sub")) }
    assertEquals(AgentBuildOutcome.UNKNOWN, abandoned?.outcome)
    assertNull(abandoned?.endedAt)
    assertEquals("own", read { it.latestForRun(setOf("goal")) }?.buildId)
    assertEquals(3_000L, read { it.history("repo", "./gradlew build", hour(10)).usualMs })
  }

  @Test
  fun `latest for run selects a failed retry after abandonment for direct and parent identities`() {
    write { store ->
      store.start(startRow("abandoned", hour(1), runId = "sub", parentRunId = "goal"))
      store.abandonOpen("sub")
      store.settled(
        startRow("retry", hour(2), runId = "sub", parentRunId = "goal").copy(repositoryIdentity = "retry-repo"),
        3_000,
        AgentBuildOutcome.FAILED,
      )
      store.start(startRow("abandoned-only", hour(3), runId = "only").copy(repositoryIdentity = "retry-repo"))
      store.abandonOpen("only")
    }

    val retry = read { it.latestForRun(setOf("sub")) }
    assertEquals("retry", retry?.buildId)
    assertEquals(AgentBuildOutcome.FAILED, retry?.outcome)
    assertEquals(retry, read { it.latestForRun(setOf("goal")) })

    val abandoned = read { it.latestForRun(setOf("only")) }
    assertEquals("abandoned-only", abandoned?.buildId)
    assertEquals(AgentBuildOutcome.UNKNOWN, abandoned?.outcome)
    assertNull(abandoned?.endedAt)
    assertEquals(3_000L, read { it.history("retry-repo", "./gradlew build", hour(10)).usualMs })
  }
}
