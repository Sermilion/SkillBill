package dev.skillbill.desktop.core.data.control

import dev.skillbill.desktop.core.data.repository.workItem
import dev.skillbill.desktop.core.data.runtime.BoardPage
import dev.skillbill.desktop.core.data.session.LaunchBoardTarget
import dev.skillbill.desktop.core.data.session.LaunchReservations
import dev.skillbill.desktop.core.data.session.ReservationAdmission
import dev.skillbill.desktop.core.domain.model.StandaloneRelaunch
import dev.skillbill.desktop.core.domain.model.Lifecycle
import dev.skillbill.desktop.core.domain.model.WorkItem
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
}
