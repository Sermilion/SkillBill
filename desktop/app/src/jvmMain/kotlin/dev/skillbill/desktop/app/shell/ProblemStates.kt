package dev.skillbill.desktop.app.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.skillbill.desktop.core.designsystem.component.EmptyState
import dev.skillbill.desktop.core.designsystem.component.SbButton
import dev.skillbill.desktop.core.designsystem.component.SbButtonVariant
import dev.skillbill.desktop.core.designsystem.icon.LucideIcons
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.ReadProblem
import dev.skillbill.desktop.core.domain.model.RuntimeBuildProblem
import dev.skillbill.desktop.core.domain.session.DatabaseSessionState
import dev.skillbill.desktop.core.domain.session.StorageFailure
import dev.skillbill.desktop.core.ui.feedback.storageFailureText

/** Why the main panel shows a problem state instead of the route. Settings never shows one, so the user can fix the path. */
sealed interface ShellProblem {
  data class RuntimeUnavailable(val problem: RuntimeBuildProblem) : ShellProblem

  data object Opening : ShellProblem

  data class SessionFailed(val failure: StorageFailure) : ShellProblem

  /** The first board load failed; a failure after a loaded board keeps that board on screen instead. */
  data class BoardFailed(val problem: ReadProblem) : ShellProblem

  data object Loading : ShellProblem
}

fun shellProblem(session: DatabaseSessionState, board: ShellBoardView): ShellProblem? = when {
  session is DatabaseSessionState.Opening -> ShellProblem.Opening
  session is DatabaseSessionState.Failed -> ShellProblem.SessionFailed(session.failure)
  board.lastLoaded != null -> null
  board.state is BoardState.Failed -> ShellProblem.BoardFailed(board.state.problem)
  else -> ShellProblem.Loading
}

private const val LOAD_FAILED = "Couldn't load your work"

/** The problem state's copy and actions, shared by the main window and the runtime refusal window. */
@Composable
fun ProblemStatePanel(problem: ShellProblem, onRefresh: () -> Unit = {}, onRetry: () -> Unit = {}) {
  // One secondary Retry, as the prototype's "Couldn't load your work" state; the body points at Settings in the sidebar.
  val loadActions: @Composable RowScope.() -> Unit = { SbButton("Retry", onRefresh) }
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    when (problem) {
      is ShellProblem.RuntimeUnavailable -> EmptyState(
        "Skill Bill runtime unavailable",
        LucideIcons.warn,
        body = runtimeUnavailableBody(problem.problem),
      ) { SbButton("Retry", onRetry, variant = SbButtonVariant.Primary) }
      ShellProblem.Opening -> EmptyState("Opening your database", LucideIcons.refresh, body = "Skill Bill is switching to the selected database.")
      is ShellProblem.SessionFailed -> EmptyState(LOAD_FAILED, LucideIcons.alert, body = storageFailureText(problem.failure), actions = loadActions)
      is ShellProblem.BoardFailed -> EmptyState(LOAD_FAILED, LucideIcons.alert, body = boardFailureBody(problem.problem), actions = loadActions)
      ShellProblem.Loading -> EmptyState("Loading your work", LucideIcons.inbox)
    }
  }
}

private fun boardFailureBody(problem: ReadProblem): String = when (problem) {
  is ReadProblem.Storage -> storageFailureText(problem.failure)
  is ReadProblem.Runtime -> "Check the database path in Settings, then try refreshing.\n${problem.message}"
}

private fun runtimeUnavailableBody(problem: RuntimeBuildProblem): String {
  val reason = when (problem) {
    is RuntimeBuildProblem.NotInstalled -> "Skill Bill runtime not installed."
    is RuntimeBuildProblem.Mismatched -> "The installed Skill Bill runtime is not the build this app was packaged with."
    is RuntimeBuildProblem.Unreadable -> "The runtime or desktop build identity could not be read."
  }
  val identities = when (problem) {
    is RuntimeBuildProblem.Mismatched -> "\nInstalled ${problem.installedIdentity}, app ${problem.appIdentity}."
    is RuntimeBuildProblem.Unreadable -> problem.installedIdentity?.let { "\nInstalled $it." }.orEmpty()
    is RuntimeBuildProblem.NotInstalled -> ""
  }
  return "$reason Install a matched runtime and desktop pair with ${problem.reinstallCommand}, then retry.$identities"
}
