package dev.skillbill.tray.core.data.repository

import dev.skillbill.tray.core.data.durableStoreSnapshot
import dev.skillbill.tray.core.data.workStack
import dev.skillbill.tray.core.domain.model.Board
import dev.skillbill.tray.core.domain.model.Detail
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.testing.TestClock
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.toKotlinInstant

class ReadOnlyStoreTest {
  private val now = Instant.now()

  @Test
  fun `selecting, reading, observing and closing leave the durable schema, version and rows as the runtime wrote them`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-read-only-store").use { fixture ->
      sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap()).transaction { unit ->
        unit.standalonePhaseStatuses.register(
          StandalonePhaseStatusRegistration(
            "repo-root-realpath-v1:${fixture.tempDir.toRealPath()}", "main", "SKILL-423", null, "read-only", "review",
            "execution-read-only", "active", "review", now, "owned-test", 1, now.plusSeconds(300),
          ),
        )
      }
      val before = durableStoreSnapshot(fixture.connection)
      assertTrue(before.isNotEmpty())
      val stack = workStack(fixture.tempDir.toRealPath(), TestClock(now.toKotlinInstant()))
      try {
        stack.selectDatabase(fixture.dbPath.toString())
        val board = assertIs<Outcome.Value<Board>>(stack.board(WorkSection.PHASES, Retention.DAY)).value
        assertEquals(listOf("read-only"), board.items.map { it.identity.id })
        assertIs<Outcome.Value<Detail>>(stack.detail(board.items.single().identity))
        val observing = launch { stack.observe(WorkSection.PHASES, Retention.DAY).first() }
        withTimeout(10_000) { observing.join() }
        observing.cancelAndJoin()
        stack.refresh()
        assertIs<Outcome.Value<Board>>(stack.board(WorkSection.HISTORY, Retention.DAY))
      } finally {
        stack.closeAndJoin()
      }
      assertEquals(before, durableStoreSnapshot(fixture.connection))
    }
  }
}
