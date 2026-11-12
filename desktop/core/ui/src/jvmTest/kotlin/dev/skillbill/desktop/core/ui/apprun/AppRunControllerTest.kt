package dev.skillbill.desktop.core.ui.apprun

import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.qa.QaDeviceDiscovery
import dev.skillbill.desktop.core.domain.qa.QaDevicePick
import dev.skillbill.desktop.core.domain.qa.QaDevicePickKind
import dev.skillbill.desktop.core.domain.repository.AppRunEvents
import dev.skillbill.desktop.core.domain.repository.AppRunRepository
import dev.skillbill.desktop.core.domain.repository.AppRunResult
import dev.skillbill.desktop.core.domain.repository.AppRunStart
import dev.skillbill.desktop.core.domain.repository.AppRunStep
import dev.skillbill.desktop.core.domain.repository.AppRunStepKind
import dev.skillbill.desktop.core.testing.RecordingDiagnostics
import dev.skillbill.desktop.core.testing.workItem
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AppRunControllerTest {
  @Test
  fun `contended outcome shows the holder issue key`() = runTest {
    val repository = FakeRepository { _, _ -> AppRunResult.Contended("inv-1") }
    val controller = AppRunController(repository, FakeHost(mapOf("inv-1" to "SKILL-514")), WindowCoroutineScope(backgroundScope), RecordingDiagnostics())

    controller.run(goal(), pick, null)
    runCurrent()

    assertEquals(AppRunState.Contended("Pixel 8", "SKILL-514"), controller.state("NEWS-168"))
  }

  @Test
  fun `output replays every line and reset clears the previous run`() {
    val output = AppRunOutput()
    repeat(10_001) { output.append("line-$it") }
    val received = mutableListOf<AppRunOutputEvent>()
    val attachment = output.attach { received += it }
    output.append("attached")
    attachment.close()

    assertEquals(10_003, received.size)
    assertEquals(AppRunOutputEvent.Reset, received.first())
    assertEquals(AppRunOutputEvent.Line("line-0"), received[1])
    assertEquals(AppRunOutputEvent.Line("line-10000"), received[10_001])
    assertEquals(AppRunOutputEvent.Line("attached"), received.last())

    output.reset()
    val nextRun = mutableListOf<AppRunOutputEvent>()
    output.attach { nextRun += it }.close()
    assertEquals(listOf<AppRunOutputEvent>(AppRunOutputEvent.Reset), nextRun)
  }

  @Test
  fun `building reports the gradle task ignores a duplicate run and stops after cancellation`() = runTest {
    val stopped = CompletableDeferred<Unit>()
    val repository = FakeRepository { start, events ->
      events.onStep(AppRunStep(AppRunStepKind.Building, ":app:installDebug"))
      start.stop.bind { stopped.complete(Unit) }
      stopped.await()
      AppRunResult.Stopped
    }
    val controller = AppRunController(repository, FakeHost(emptyMap()), WindowCoroutineScope(backgroundScope), RecordingDiagnostics())

    controller.run(goal(), pick, null)
    runCurrent()
    assertEquals(AppRunState.Building("Pixel 8", "gradlew :app:installDebug"), controller.state("NEWS-168"))

    controller.run(goal(), pick, null)
    runCurrent()
    assertEquals(1, repository.calls)
    controller.stop("NEWS-168")
    runCurrent()

    assertEquals(AppRunState.Stopped, controller.state("NEWS-168"))
  }

  @Test
  fun `stop request does not hide an already completed build failure`() = runTest {
    val finish = CompletableDeferred<Unit>()
    val repository = FakeRepository { _, _ ->
      finish.await()
      AppRunResult.BuildFailed(":app:installDebug")
    }
    val controller = AppRunController(repository, FakeHost(emptyMap()), WindowCoroutineScope(backgroundScope), RecordingDiagnostics())

    controller.run(goal(), pick, null)
    runCurrent()
    controller.stop("NEWS-168")
    finish.complete(Unit)
    runCurrent()

    assertEquals(AppRunState.BuildFailed("Pixel 8", ":app:installDebug"), controller.state("NEWS-168"))
  }

  private val pick = QaDevicePick(QaDevicePickKind.Serial, "serial-1", "Pixel 8")

  private fun goal(): WorkItem = workItem("NEWS-168", lifecycle = dev.skillbill.desktop.core.domain.model.Lifecycle.Terminal)

  private class FakeRepository(private val action: suspend (AppRunStart, AppRunEvents) -> AppRunResult) : AppRunRepository {
    var calls = 0
      private set

    override suspend fun run(start: AppRunStart, events: AppRunEvents): AppRunResult {
      calls++
      events.onTarget(start.target)
      return action(start, events)
    }
  }

  private class FakeHost(private val holders: Map<String, String>) : RunAppDeviceHost {
    override val pick = MutableStateFlow<QaDevicePick?>(null)
    override val discovery = MutableStateFlow<QaDeviceDiscovery?>(null)
    override fun refreshDevices() = Unit
    override fun leaseHolders(): Map<String, String> = holders
    override fun openDeviceMenu() = Unit
    @Composable override fun DeviceMenu(returnFocus: FocusRequester) = Unit
  }
}
