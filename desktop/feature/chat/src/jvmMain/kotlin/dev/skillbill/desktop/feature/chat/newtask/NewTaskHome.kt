package dev.skillbill.desktop.feature.chat.newtask

import dev.skillbill.desktop.core.domain.model.BoardSectionKind
import dev.skillbill.desktop.core.domain.model.BoardState
import dev.skillbill.desktop.core.domain.model.DetectedAgentsRead
import dev.skillbill.desktop.core.domain.model.WorkItem
import dev.skillbill.desktop.core.domain.model.sectionSize

/** Which content the home shows under the step line, decided by the board. */
sealed interface NewTaskHome {
  data object Pending : NewTaskHome
  data class Returning(
    val needsYou: List<WorkItem>,
    val running: Int,
    val finished: Int,
  ) : NewTaskHome
  data object FirstRun : NewTaskHome
}

internal fun newTaskHome(board: BoardState): NewTaskHome = when (board) {
  BoardState.Loading, is BoardState.Failed -> NewTaskHome.Pending
  is BoardState.Loaded ->
    if (board.sections.all { it.items.isEmpty() } && board.counts.running == 0) {
      NewTaskHome.FirstRun
    } else {
      val needsYou = board.sections.firstOrNull { it.kind == BoardSectionKind.NeedsYou }?.items.orEmpty()
      NewTaskHome.Returning(
        needsYou = needsYou,
        running = board.counts.running,
        finished = board.sectionSize(BoardSectionKind.Finished),
      )
    }
}

internal data class ReadinessLine(val text: String, val ready: Boolean)

/** Claims only what the desktop can read: detected agents and the selected repository. A null [read] has not finished yet. */
internal fun readinessLine(read: DetectedAgentsRead?, repository: String?): ReadinessLine? = when (read) {
  null -> null
  is DetectedAgentsRead.Failed -> ReadinessLine("Readiness unknown: agent detection failed", ready = false)
  is DetectedAgentsRead.Success -> {
    val ids = read.agents.map { it.name }.distinct()
    when {
      ids.isEmpty() -> ReadinessLine("Not ready: no agent found", ready = false)
      repository == null -> ReadinessLine("Not ready: choose a repository", ready = false)
      else -> ReadinessLine("Ready: ${ids.joinToString(", ")} found, $repository selected", ready = true)
    }
  }
}
