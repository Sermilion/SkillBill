package dev.skillbill.tray.core.data.repository

import dev.skillbill.tray.core.data.workStack
import dev.skillbill.tray.core.domain.model.Board
import dev.skillbill.tray.core.domain.model.Detail
import dev.skillbill.tray.core.domain.model.Retention
import dev.skillbill.tray.core.domain.model.StorageFailure
import dev.skillbill.tray.core.domain.model.WorkSection
import dev.skillbill.tray.core.domain.repository.Outcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import skillbill.infrastructure.sqlite.TemporarySchemaDatabase
import skillbill.infrastructure.sqlite.establishTemporarySchemaReadiness
import skillbill.infrastructure.sqlite.sqliteSessionFactoryForTests
import skillbill.ports.idestatus.model.StandalonePhaseStatusRegistration
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ObservationLifecycleTest {

  @Test
  fun `a store that fails initialization leaves no connection or watch behind`() = runBlocking {
    establishTemporarySchemaReadiness("desktop-failed-initialization").use { fixture ->
      val stack = workStack(fixture.tempDir.toRealPath())
      try {
        fixture.connection.createStatement().use { it.execute("PRAGMA user_version = 51") }
        stack.selectDatabase(fixture.dbPath.toString())
        val outcome = stack.board(WorkSection.PHASES, Retention.DAY)
        assertIs<Outcome.Failure>(outcome, outcome.toString())
        assertEquals(StorageFailure.INCOMPATIBLE_SCHEMA, outcome.problem.storageFailure)
        awaitValue(stack.openConnections, 0)
        awaitValue(stack.ownerRegistrations, 0)
        awaitValue(stack.signalRegistrations, 0)
      } finally {
        stack.closeAndJoin()
      }
    }
  }

  private fun registerLivePhase(fixture: TemporarySchemaDatabase, branch: String) {
    val factory = sqliteSessionFactoryForTests(fixture.tempDir, fixture.dbPath.toString(), emptyMap())
    val now = Instant.now()
    factory.transaction { unit ->
      unit.standalonePhaseStatuses.register(
        StandalonePhaseStatusRegistration(
          "repo-root-realpath-v1:${fixture.tempDir.toRealPath()}", branch, "SKILL-1", null, "live-$branch", "review",
          "execution-$branch", "active", "review", now.minusSeconds(60), "owned-test", 1, now.plusSeconds(300),
        ),
      )
    }
  }

  private suspend fun awaitValue(counter: AtomicInteger, expected: Int) {
    withTimeout(10_000) { while (counter.get() != expected) delay(20) }
  }
}
