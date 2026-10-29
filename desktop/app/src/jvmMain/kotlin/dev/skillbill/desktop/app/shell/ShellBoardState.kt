package dev.skillbill.desktop.app.shell

import dev.skillbill.desktop.app.di.WindowCoroutineScope
import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.repository.WorkBoardRepository
import dev.skillbill.desktop.core.ui.board.ShellBoard
import dev.skillbill.desktop.core.ui.di.WindowScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/** The shared board as the shell reads it: the latest state, and the last loaded board a later failure still leaves on screen. */
data class ShellBoardView(val state: BoardState, val lastLoaded: BoardState.Loaded?)

@Inject
@SingleIn(WindowScope::class)
class ShellBoardState(board: WorkBoardRepository, scope: WindowCoroutineScope) {
  private val initial = ShellBoardView(BoardState.Loading, null)

  val view: StateFlow<ShellBoardView> = board.observe(ShellBoard.query)
    .runningFold(initial) { previous, state ->
      val lastLoaded = when (state) {
        is BoardState.Loaded -> state
        // A new load, for example under a new session, starts without the previous board.
        BoardState.Loading -> null
        is BoardState.Failed -> previous.lastLoaded
      }
      ShellBoardView(state, lastLoaded)
    }
    .stateIn(scope.scope, SharingStarted.Eagerly, initial)
}

fun sectionLabel(kind: BoardSectionKind): String = when (kind) {
  BoardSectionKind.NeedsYou -> "Needs you"
  BoardSectionKind.Goals -> "Goal runs"
  BoardSectionKind.Phases -> "Standalone phases"
  BoardSectionKind.Operations -> "Operations"
  BoardSectionKind.Finished -> "Finished today"
}
