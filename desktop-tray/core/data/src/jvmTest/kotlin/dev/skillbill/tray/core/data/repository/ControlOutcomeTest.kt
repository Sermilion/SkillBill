package dev.skillbill.tray.core.data.repository

import dev.skillbill.tray.core.data.port.ControlRefusal
import dev.skillbill.tray.core.data.port.ControlResult
import dev.skillbill.tray.core.data.workStack
import dev.skillbill.tray.core.domain.model.Board
import dev.skillbill.tray.core.domain.model.ControlConfirmation
import dev.skillbill.tray.core.domain.model.ProblemCode
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.repository.Outcome
import dev.skillbill.tray.core.testing.TestClock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.toKotlinInstant

class ControlOutcomeTest {
  private val now = Instant.now()

  @Test
  fun `goal controls report only what the runtime confirmed and an unfinished stop is not a stop`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-control-outcomes").use { fixture ->
      val factory = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
      factory.transaction { unit ->
        unit.standalonePhaseStatuses.register(
          StandalonePhaseStatusRegistration(
            "repo-root-realpath-v1:${fixture.tempDir.toRealPath()}", "branch", "SKILL-1", null, "live", "review", "execution-live",
            "active", "review", now.minusSeconds(60), "owned-test", 1, now.plusSeconds(300),
          ),
        )
      }
      val scripted = AtomicReference<ControlResult?>(null)
      val stack = workStack(fixture.tempDir.toRealPath(), TestClock(now.toKotlinInstant()), scriptedControl = { scripted.get() })
      try {
        stack.selectDatabase(fixture.dbPath.toString())
        val board = stack.board(WorkSection.PHASES, Retention.DAY)
        val item = assertIs<Outcome.Value<Board>>(board, board.toString()).value.items.single()

        scripted.set(ControlResult.IntentRecorded)
        assertEquals(Outcome.Value(ControlConfirmation.INTENT_RECORDED), stack.pause(item))
        scripted.set(ControlResult.ExitConfirmed)
        assertEquals(Outcome.Value(ControlConfirmation.EXIT_CONFIRMED), stack.stop(item))
        scripted.set(ControlResult.Accepted)
        assertEquals(Outcome.Value(ControlConfirmation.REQUEST_ACCEPTED), stack.stop(item))

        scripted.set(ControlResult.Rejected("termination_incomplete", ControlRefusal.TERMINATION_INCOMPLETE))
        val incomplete = stack.stop(item)
        assertIs<Outcome.Failure>(incomplete, incomplete.toString())
        scripted.set(ControlResult.Rejected("changed_target", ControlRefusal.OTHER))
        val changed = stack.stop(item)
        assertIs<Outcome.Failure>(changed, changed.toString())

        assertEquals(ProblemCode.STALE_TARGET, incomplete.problem.code)
        assertTrue("has not exited yet" in incomplete.problem.summary, incomplete.problem.summary)
        assertNotEquals(changed.problem.summary, incomplete.problem.summary)
      } finally {
        stack.closeAndJoin()
      }
    }
  }
}
