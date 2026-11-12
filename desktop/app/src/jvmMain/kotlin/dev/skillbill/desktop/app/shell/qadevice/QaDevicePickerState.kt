package dev.skillbill.desktop.app.shell.qadevice

import dev.skillbill.desktop.app.shell.ShellBoardState
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.common.Diagnostics
import dev.skillbill.desktop.core.domain.qa.QaDeviceDiscovery
import dev.skillbill.desktop.core.domain.qa.QaDeviceDiscoveryRepository
import dev.skillbill.desktop.core.domain.qa.QaDevicePick
import dev.skillbill.desktop.core.domain.qa.leaseHolderIssueKeys
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.feedback.ToastMessage
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.feedback.ToastTone
import dev.skillbill.desktop.core.ui.navigation.WindowCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

data class QaDevicePickerUi(
  val pick: QaDevicePick? = null,
  val result: QaDeviceDiscovery? = null,
  val checking: Boolean = false,
  val open: Boolean = false,
  val keyboardIndex: Int = 0,
  val anchor: QaDeviceMenuAnchor = QaDeviceMenuAnchor.Sidebar,
)

enum class QaDeviceMenuAnchor { Sidebar, RunApp }

@Inject
@SingleIn(WindowScope::class)
class QaDevicePickerState(
  private val settings: AppSettingsRepository,
  private val board: ShellBoardState,
  private val discovery: QaDeviceDiscoveryRepository,
  private val scope: WindowCoroutineScope,
  private val toasts: ToastQueue,
  diagnostics: Diagnostics,
) {
  private val mutableUi = MutableStateFlow(QaDevicePickerUi())
  val ui: StateFlow<QaDevicePickerUi> = mutableUi.asStateFlow()
  private var discoveryJob: Job? = null

  init {
    scope.scope.launch {
      settings.settings.map { it.qaDevice }.distinctUntilChanged()
        .catch { diagnostics.record("desktop_qa_device_pick_unavailable") }
        .collect { pick -> mutableUi.update { it.copy(pick = pick) } }
    }
  }

  fun open(anchor: QaDeviceMenuAnchor = QaDeviceMenuAnchor.Sidebar) {
    if (mutableUi.value.open && mutableUi.value.anchor == anchor) return
    mutableUi.update { current ->
      val rows = qaDeviceRows(current.pick, current.result)
      current.copy(open = true, anchor = anchor, keyboardIndex = selectedRowIndex(rows, current.pick).coerceAtLeast(0))
    }
    refresh()
  }

  fun refresh() {
    discoveryJob?.cancel()
    val items = board.view.value.lastLoaded?.items.orEmpty()
    val roots = items.map { it.repositoryRoot }.distinct()
    val labels = leaseHolderIssueKeys(items)
    val job = scope.scope.launch(start = CoroutineStart.LAZY) {
      mutableUi.update { it.copy(checking = true) }
      try {
        val result = discovery.refresh(roots, labels)
        mutableUi.update { state ->
          val rows = qaDeviceRows(state.pick, result)
          state.copy(result = result, keyboardIndex = selectedRowIndex(rows, state.pick).coerceAtLeast(0))
        }
      } finally {
        if (discoveryJob === kotlinx.coroutines.currentCoroutineContext()[Job]) {
          mutableUi.update { it.copy(checking = false) }
        }
      }
    }
    discoveryJob = job
    job.start()
  }

  fun close() {
    discoveryJob?.cancel()
    discoveryJob = null
    mutableUi.update { it.copy(open = false, checking = false) }
  }

  fun move(direction: Int) {
    mutableUi.update { state ->
      val rows = qaDeviceRows(state.pick, state.result)
      state.copy(keyboardIndex = stepPickable(rows, state.keyboardIndex, direction))
    }
  }

  fun focus(index: Int) {
    mutableUi.update { state ->
      val row = qaDeviceRows(state.pick, state.result).getOrNull(index)
      if (index == -1 || row?.pickable == true) state.copy(keyboardIndex = index) else state
    }
  }

  fun choose(index: Int) {
    val row = qaDeviceRows(mutableUi.value.pick, mutableUi.value.result).getOrNull(index) ?: return
    if (!row.pickable) return
    scope.scope.launch {
      when (settings.setQaDevice(row.toPick())) {
        is Outcome.Success -> close()
        is Outcome.Failure -> toasts.show(ToastMessage(ToastTone.Problem, "Could not save the QA device pick."))
      }
    }
  }
}
