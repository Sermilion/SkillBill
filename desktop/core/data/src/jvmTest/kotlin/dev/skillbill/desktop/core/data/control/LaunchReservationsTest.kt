package dev.skillbill.desktop.core.data.control

import dev.skillbill.desktop.core.data.repository.workItem
import dev.skillbill.desktop.core.data.runtime.BoardPage
import dev.skillbill.desktop.core.data.session.LaunchBoardTarget
import dev.skillbill.desktop.core.data.session.LaunchReservations
import dev.skillbill.desktop.core.data.session.ReservationAdmission
import dev.skillbill.desktop.core.domain.model.StandaloneRelaunch
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.WorkKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

private const val KEY = "SKILL-1"
private const val SESSION = 1L

class LaunchReservationsTest {
  private val paused = workItem("wf").copy(issueKey = KEY, lifecycle = Lifecycle.Paused, running = false)

  private fun reservations() = LaunchReservations()

  private fun page(item: WorkItem) =
    BoardPage(listOf(item), null, Instant.fromEpochMilliseconds(0))

  private fun reserve(launches: LaunchReservations) = launches.reserve(
    KEY, SESSION, LaunchBoardTarget.Item(paused.id, paused.kind, paused.control.repositoryIdentity), paused.lifecycle, page(paused),
  )

  @Test
  fun `a standalone relaunch releases its reservation when a new invocation registers`() = runTest {
    val source = paused.copy(standaloneExecution = true, control = paused.control.copy(invocationId = paused.id, phaseId = "plan"))
    val launches = reservations()
    val handle = assertIs<ReservationAdmission.Reserved>(launches.reserve(
      KEY, SESSION, LaunchBoardTarget.Item(source.id, source.kind, source.control.repositoryIdentity), source.lifecycle, page(source),
    )).handle
    launches.settle(handle, succeeded = true)
    val successor = source.copy(id = "new-invocation", running = true, lifecycle = Lifecycle.Active)

    launches.onBoard(SESSION, BoardPage(listOf(source, successor), null, Instant.fromEpochMilliseconds(1)))

    assertEquals(emptySet(), launches.keys.value)
  }

  @Test
  fun `a standalone relaunch refuses an ambiguous new invocation`() {
    val source = paused.copy(standaloneExecution = true, control = paused.control.copy(phaseId = "plan"))
    val target = StandaloneRelaunch(source, setOf(source.id))

    assertEquals(null, target.successor(listOf(source.copy(id = "new-a"), source.copy(id = "new-b"))))
  }

  @Test
  fun `a run that exits before showing on the board releases its reservation`() = runTest {
    val launches = reservations()
    val handle = assertIs<ReservationAdmission.Reserved>(reserve(launches)).handle
    val exit = CompletableFuture<String?>()
    launches.releaseOnExit(handle, exit)
    launches.settle(handle, succeeded = true)
    assertEquals(setOf(KEY), launches.keys.value)

    exit.complete("blocked")

    assertEquals(emptySet(), launches.keys.value)
    assertIs<ReservationAdmission.Reserved>(reserve(launches))
  }

  @Test
  fun `a run that exits before settling stays released`() = runTest {
    val launches = reservations()
    val handle = assertIs<ReservationAdmission.Reserved>(reserve(launches)).handle
    launches.releaseOnExit(handle, CompletableFuture.completedFuture("blocked"))
    launches.settle(handle, succeeded = true)
    assertEquals(emptySet(), launches.keys.value)
  }

  @Test
  fun `an old run's exit leaves a newer reservation alone`() = runTest {
    val launches = reservations()
    val old = assertIs<ReservationAdmission.Reserved>(reserve(launches)).handle
    val exit = CompletableFuture<String?>()
    launches.releaseOnExit(old, exit)
    launches.release(old)
    assertIs<ReservationAdmission.Reserved>(reserve(launches))

    exit.complete(null)

    assertEquals(setOf(KEY), launches.keys.value)
  }

  @Test
  fun `a refused launch releases its key`() = runTest {
    val launches = reservations()
    val handle = assertIs<ReservationAdmission.Reserved>(reserve(launches)).handle
    launches.settle(handle, succeeded = false)
    runCurrent()
    assertEquals(emptySet(), launches.keys.value)
  }

  @Test
  fun `an accepted launch stays reserved until the board shows it running`() = runTest {
    val launches = reservations()
    val handle = assertIs<ReservationAdmission.Reserved>(reserve(launches)).handle
    launches.settle(handle, succeeded = true)
    launches.onBoard(SESSION, page(paused))
    runCurrent()
    assertEquals(setOf(KEY), launches.keys.value)

    launches.onBoard(SESSION, page(paused.copy(lifecycle = Lifecycle.Active, running = true)))
    runCurrent()
    assertEquals(emptySet(), launches.keys.value)
  }

  @Test
  fun `an accepted resume with no board change becomes available again after ten seconds`() = runTest {
    val launches = reservations()
    val handle = assertIs<ReservationAdmission.Reserved>(reserve(launches)).handle
    launches.settle(handle, succeeded = true)
    var refreshed = false
    val confirmation = async {
      launches.awaitStart(handle) {
        refreshed = true
        page(paused)
      }
    }
    runCurrent()

    advanceTimeBy(9_999)
    runCurrent()
    assertFalse(refreshed)
    assertEquals(setOf(KEY), launches.keys.value)

    advanceTimeBy(1)
    runCurrent()
    assertTrue(refreshed)
    assertTrue(confirmation.await())
    assertEquals(emptySet(), launches.keys.value)
    assertIs<ReservationAdmission.Reserved>(reserve(launches))
  }

  @Test
  fun `the ten second check confirms a running execution rather than reporting a failed resume`() = runTest {
    val launches = reservations()
    val handle = assertIs<ReservationAdmission.Reserved>(reserve(launches)).handle
    launches.settle(handle, succeeded = true)
    val confirmation = async {
      launches.awaitStart(handle) { page(paused.copy(lifecycle = Lifecycle.Active, running = true)) }
    }
    runCurrent()

    advanceTimeBy(10_000)
    runCurrent()

    assertFalse(confirmation.await())
    assertEquals(emptySet(), launches.keys.value)
  }

  @Test
  fun `a failed status read keeps admission reserved and retries at the next ten second check`() = runTest {
    val launches = reservations()
    val handle = assertIs<ReservationAdmission.Reserved>(reserve(launches)).handle
    launches.settle(handle, succeeded = true)
    var available = false
    val confirmation = async { launches.awaitStart(handle) { if (available) page(paused) else null } }
    runCurrent()

    advanceTimeBy(10_000)
    runCurrent()
    assertFalse(confirmation.isCompleted)
    assertEquals(setOf(KEY), launches.keys.value)

    available = true
    advanceTimeBy(10_000)
    runCurrent()
    assertTrue(confirmation.await())
    assertEquals(emptySet(), launches.keys.value)
  }

  @Test
  fun `a delayed confirmation cannot release a newer resume reservation`() = runTest {
    val launches = reservations()
    val old = assertIs<ReservationAdmission.Reserved>(reserve(launches)).handle
    launches.settle(old, succeeded = true)
    val read = CompletableDeferred<BoardPage>()
    val confirmation = async { launches.awaitStart(old) { read.await() } }
    runCurrent()
    advanceTimeBy(10_000)
    runCurrent()

    launches.release(old)
    assertIs<ReservationAdmission.Reserved>(reserve(launches))
    read.complete(page(paused))
    runCurrent()

    assertFalse(confirmation.await())
    assertEquals(setOf(KEY), launches.keys.value)
  }

  @Test
  fun `a resumed goal that blocks before the next board read releases its reservation`() {
    val source = paused.copy(lifecycle = Lifecycle.Blocked, control = paused.control.copy(executionId = "prior", runSequence = "1"))
    val launches = reservations()
    val handle = assertIs<ReservationAdmission.Reserved>(launches.reserve(
      KEY, SESSION, LaunchBoardTarget.Item(source.id, source.kind, source.control.repositoryIdentity), source.lifecycle, page(source),
    )).handle
    launches.settle(handle, succeeded = true)
    launches.onBoard(SESSION, page(source))
    assertEquals(setOf(KEY), launches.keys.value)

    val blockedAgain = source.copy(control = source.control.copy(executionId = "resumed", runSequence = "2"))
    launches.onBoard(SESSION, page(blockedAgain))

    assertEquals(emptySet(), launches.keys.value)
  }

  @Test
  fun `the board never releases a launch still in flight`() = runTest {
    val launches = reservations()
    reserve(launches)
    launches.onBoard(SESSION, page(paused.copy(lifecycle = Lifecycle.Active, running = true)))
    runCurrent()
    assertEquals(setOf(KEY), launches.keys.value)
  }

  @Test
  fun `a second launch for a held key is refused`() = runTest {
    val launches = reservations()
    reserve(launches)
    assertIs<ReservationAdmission.AlreadyReserved>(reserve(launches))
  }

  @Test
  fun `a plan only reservation releases when its plan phase row starts`() = runTest {
    val plan = paused.copy(
      kind = WorkKind.Phase,
      lifecycle = Lifecycle.Idle,
      running = false,
      control = paused.control.copy(phaseId = "plan"),
    )
    val launches = reservations()
    val handle = assertIs<ReservationAdmission.Reserved>(
      launches.reserve(KEY, SESSION, LaunchBoardTarget.PlanPhase(KEY, plan.control.repositoryIdentity), null, page(plan)),
    ).handle
    launches.settle(handle, succeeded = true)
    launches.onBoard(SESSION, page(plan))

    launches.onBoard(SESSION, page(plan.copy(lifecycle = Lifecycle.Active, running = true)))

    assertEquals(emptySet(), launches.keys.value)
  }
}
