package dev.skillbill.desktop.feature.terminal

import dev.skillbill.desktop.core.common.HostEnvironment
import dev.skillbill.desktop.core.common.Outcome
import dev.skillbill.desktop.core.designsystem.component.ToastData
import dev.skillbill.desktop.core.designsystem.component.ToastLead
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.settings.AppSettings
import dev.skillbill.desktop.core.domain.settings.AppSettingsRepository
import dev.skillbill.desktop.core.ui.di.WindowScope
import dev.skillbill.desktop.core.ui.feedback.ToastQueue
import dev.skillbill.desktop.core.ui.input.ShellInput
import dev.skillbill.desktop.core.ui.pane.PaneCommands
import dev.skillbill.desktop.core.ui.selection.SelectionHolder
import dev.skillbill.desktop.core.ui.shell.ShellPanes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.awt.Font
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What the pane renders: the labelled tabs, the active one, and the stored or dragged height before the 70 % cap. A resize starts from the capped height. */
data class TerminalUiState(
  val tabs: List<TerminalTabUi> = emptyList(),
  val activeId: Long? = null,
  val paneOpen: Boolean = false,
  val heightDp: Float = AppSettings.TERMINAL_HEIGHT_DEFAULT_DP.toFloat(),
  val renamingId: Long? = null,
)

data class TerminalTabUi(val id: Long, val label: String, val directory: Path)

// A drag reports many deltas; the height is written once it has been still this long.
private val HEIGHT_WRITE_DELAY = 300.milliseconds

// The close-time write of the last height; a stuck disk must not hang exit.
private val HEIGHT_FLUSH_TIMEOUT = 1.seconds

/**
 * The window's tabs and shells. It is window-scoped, never session-scoped, so a database switch ends no shell; the window's
 * disposal ends them through [closeTerminals], and app exit through the launcher's shutdown hook. Everything but the shell exit
 * callback runs on the UI thread.
 */
@Inject
@SingleIn(WindowScope::class)
@ContributesBinding(WindowScope::class, boundType = PaneCommands::class)
class TerminalWorkspace(
  selection: SelectionHolder,
  private val settingsRepository: AppSettingsRepository,
  private val launcher: ShellLauncher,
  private val panes: ShellPanes,
  private val input: ShellInput,
  private val toasts: ToastQueue,
  host: HostEnvironment,
) : PaneCommands {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
  private val userHome = Path.of(host.userHome)
  private val environment = host.variables
  private val tabs = MutableStateFlow(TerminalTabsState())
  private val renaming = MutableStateFlow<Long?>(null)
  private val draggedHeight = MutableStateFlow<Float?>(null)

  // The height not yet confirmed written; the shutdown hook reads it off the UI thread.
  @Volatile private var pendingHeight: Int? = null
  // The shutdown hook reads this off the UI thread.
  private val sessions = ConcurrentHashMap<Long, TerminalSession>()
  private var nextId = 1L
  private var heightWrite: Job? = null
  private var selected: SelectedRoot = SelectedRoot.None
  private var font: Font? = null

  private val open = MutableStateFlow(false)
  override val terminalOpen: StateFlow<Boolean> = open.asStateFlow()
  private val focused = MutableStateFlow(false)
  override val terminalFocused: StateFlow<Boolean> = focused.asStateFlow()

  private val storedHeight = settingsRepository.settings.map { it.terminalHeightDp }
    .stateIn(scope, SharingStarted.Eagerly, AppSettings.TERMINAL_HEIGHT_DEFAULT_DP)

  val state: StateFlow<TerminalUiState> = combine(tabs, renaming, storedHeight, draggedHeight) { current, renamingId, stored, dragged ->
    val labels = terminalTabLabels(current.tabs)
    TerminalUiState(
      tabs = current.tabs.map { TerminalTabUi(it.id, labels.getValue(it.id), Path.of(it.directory)) },
      activeId = current.activeId,
      paneOpen = current.paneOpen,
      heightDp = dragged ?: stored.toFloat(),
      renamingId = renamingId,
    )
  }.stateIn(scope, SharingStarted.Eagerly, TerminalUiState())

  // Ctrl+` and Ctrl+Shift+` never reach the window's key handler while a Swing terminal has focus, so they are taken here.
  // The chord's KEY_TYPED is swallowed wherever focus is, so neither the shell nor a text field receives a stray character;
  // its release is swallowed only when the press was taken here. Both fields are confined to the EDT.
  private var chordArmed = false
  private var chordTaken = false
  private val chords = KeyEventDispatcher { event ->
    when (event.id) {
      KeyEvent.KEY_PRESSED -> {
        val isChord = event.keyCode == KeyEvent.VK_BACK_QUOTE && event.modifiersEx and InputEvent.CTRL_DOWN_MASK != 0
        chordArmed = isChord
        chordTaken = isChord && focused.value
        if (chordTaken) if (event.isShiftDown) newTerminalTab() else toggleTerminal()
        chordTaken
      }
      KeyEvent.KEY_TYPED -> chordArmed.also { chordArmed = false }
      KeyEvent.KEY_RELEASED -> if (event.keyCode == KeyEvent.VK_BACK_QUOTE) {
        chordArmed = false
        chordTaken.also { chordTaken = false }
      } else {
        false
      }
      else -> false
    }
  }

  init {
    scope.launch {
      selection.item.collect { item ->
        if (item != null) {
          selected = SelectedRoot.Work(Path.of(item.repositoryRoot))
        } else if (selected is SelectedRoot.Work) {
          selected = SelectedRoot.None
        }
      }
    }
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(chords)
    live += this
    installShutdownHook { live.forEach { it.flushHeight(); it.closeSessions() } }
  }

  override fun toggleTerminal() {
    val current = tabs.value
    when {
      current.tabs.isEmpty() -> addTab(selected.directory)
      else -> update(current.copy(paneOpen = !current.paneOpen))
    }
  }

  override fun newTerminalTab() = addTab(selected.directory)

  override fun openTerminal(directory: Path?) = addTab(directory)

  override fun specProjectSelected(checkout: Path?) {
    selected = SelectedRoot.Spec(checkout)
  }

  override fun closeTerminals() {
    KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(chords)
    live -= this
    scope.cancel()
    flushHeight()
    closeSessions()
  }

  fun selectTab(id: Long) = update(tabs.value.activated(id))

  fun closeTab(id: Long) {
    sessions.remove(id)?.close()
    update(tabs.value.without(setOf(id)))
  }

  fun closePane() = update(tabs.value.copy(paneOpen = false))

  fun startRename(id: Long) {
    renaming.value = id
  }

  /** A late commit from a field that is going away applies its name but never ends another tab's rename. */
  fun rename(id: Long, name: String) {
    if (renaming.value == id) renaming.value = null
    update(tabs.value.renamed(id, name))
  }

  fun cancelRename() {
    renaming.value = null
  }

  /**
   * A positive [deltaDp] grows the pane. The resize starts from the rendered height, which [capDp] limits, and stays within it,
   * so a step always changes what the user sees. The height shows at once and is written to the preferences once it settles.
   */
  fun resizeBy(deltaDp: Float, capDp: Float = Float.POSITIVE_INFINITY) {
    val min = AppSettings.TERMINAL_HEIGHT_MIN_DP.toFloat()
    val upper = capDp.coerceIn(min, AppSettings.TERMINAL_HEIGHT_MAX_DP.toFloat())
    // Read from the sources, not [state]: its combine dispatches, so consecutive drag deltas would read a stale base.
    val base = (draggedHeight.value ?: storedHeight.value.toFloat()).coerceIn(min, upper)
    val height = (base + deltaDp).coerceIn(min, upper)
    draggedHeight.value = height
    val value = height.roundToInt()
    pendingHeight = value
    heightWrite?.cancel()
    heightWrite = scope.launch {
      delay(HEIGHT_WRITE_DELAY)
      if (settingsRepository.setTerminalHeightDp(value) is Outcome.Success && pendingHeight == value) pendingHeight = null
    }
  }

  /** Posts the start failure and removes the tab; the pane stays open while other tabs remain. */
  fun startFailed(id: Long, message: String) {
    toasts.show(ToastData("Couldn't start the terminal", message, ToastLead.Icon(LucideIcons.alert)))
    closeTab(id)
  }

  fun launch(tab: TerminalTabUi): TerminalLaunch = terminalLaunch(environment, System.getProperty("os.name").orEmpty(), tab.directory)

  /** The tab's session, created on first use; a tab that is no longer listed gets a closed one. */
  fun session(id: Long): TerminalSession {
    sessions[id]?.let { return it }
    val session = TerminalSession(launcher, { process, settings -> jediTermView(process, settings, ::focusChanged) }) {
      scope.launch { closeTab(id) }
    }
    if (tabs.value.tabs.none { it.id == id }) session.close() else sessions[id] = session
    return session
  }

  /** The terminal font, parsed once per window; [loadedFont] lets a reshown tab skip the wait. */
  val loadedFont: Font? get() = font

  suspend fun font(): Font = font ?: terminalFont().also { font = it }

  private fun focusChanged(hasFocus: Boolean) {
    focused.value = hasFocus
    input.setFocusSource(ShellInput.TERMINAL_SOURCE, hasFocus)
  }

  private fun addTab(requested: Path?) {
    val directory = tabDirectory(requested, userHome)
    update(tabs.value.withTab(TerminalTab(nextId++, directory.toString(), tabName(directory, userHome))))
  }

  private fun update(next: TerminalTabsState) {
    tabs.value = next
    if (!next.paneOpen || next.tabs.none { it.id == renaming.value }) renaming.value = null
    open.value = next.paneOpen
    panes.terminalOpen = next.paneOpen
    if (!next.paneOpen) focusChanged(false)
  }

  // Runs after the scope is cancelled or from the shutdown hook, so the last resize is written before the process ends.
  // DataStore writes on its own IO scope, so blocking the EDT here cannot deadlock.
  private fun flushHeight() {
    val value = pendingHeight ?: return
    pendingHeight = null
    runBlocking { withTimeoutOrNull(HEIGHT_FLUSH_TIMEOUT) { settingsRepository.setTerminalHeightDp(value) } }
  }

  private fun closeSessions() {
    sessions.keys.toList().forEach { id -> sessions.remove(id)?.close() }
  }

  // The window's current selection: a work item's repository root, or the selected spec project's checkout.
  private sealed interface SelectedRoot {
    val directory: Path?

    data class Work(override val directory: Path) : SelectedRoot

    data class Spec(override val directory: Path?) : SelectedRoot

    data object None : SelectedRoot {
      override val directory: Path? = null
    }
  }

  private companion object {
    val live: MutableSet<TerminalWorkspace> = ConcurrentHashMap.newKeySet()
  }
}
