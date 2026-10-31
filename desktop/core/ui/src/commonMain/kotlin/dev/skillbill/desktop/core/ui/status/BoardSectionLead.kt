package dev.skillbill.desktop.core.ui.status

import dev.skillbill.desktop.core.designsystem.status.StatusKind
import dev.skillbill.desktop.core.domain.model.BoardSectionKind

/** The StatusIcon a board section leads with, wherever the section is named: sidebar, Work headers, command menu. */
fun boardSectionLead(kind: BoardSectionKind): StatusKind = when (kind) {
  BoardSectionKind.NeedsYou -> StatusKind.Blocked
  BoardSectionKind.Finished -> StatusKind.Completed
  BoardSectionKind.Goals, BoardSectionKind.Phases, BoardSectionKind.Operations -> StatusKind.Active
}

/**
 * How full a section's lead pie is. It tells the running sections apart by the size of the work they hold: a goal 3/4, a
 * standalone phase 1/2, an operation 1/4. It is not progress.
 */
fun boardSectionLeadFill(kind: BoardSectionKind): Float = when (kind) {
  BoardSectionKind.Goals -> 0.75f
  BoardSectionKind.Operations -> 0.25f
  BoardSectionKind.Phases, BoardSectionKind.NeedsYou, BoardSectionKind.Finished -> 0.5f
}
