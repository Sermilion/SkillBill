package dev.skillbill.desktop.feature.detail

import dev.skillbill.desktop.core.domain.model.Detail
import dev.skillbill.desktop.core.domain.model.GoalWorktreeState
import dev.skillbill.desktop.core.domain.model.WorkItem

/**
 * A recorded path that may be opened in the editor. [absolute] is the target passed to [DetailAction.OpenSpecFile];
 * [openable] is false when the receipt fact should stay visible but no safe resolution exists.
 */
internal data class RecordedReceiptPath(val absolute: String, val openable: Boolean)

internal expect fun parseRecordedPath(recorded: String): ParsedRecordedPath?

internal data class ParsedRecordedPath(val absolute: Boolean, val normalized: String)

internal fun resolveRecordedReceiptPath(recorded: String, detail: Detail, item: WorkItem): RecordedReceiptPath? {
  val trimmed = recorded.trim()
  if (trimmed.isEmpty()) return null
  val parsed = parseRecordedPath(trimmed) ?: return null
  if (parsed.absolute) return RecordedReceiptPath(parsed.normalized, openable = true)
  val base =
    when (val tree = detail.goalWorktree) {
      null ->
        item.goalWorktree?.takeIf { it.state != GoalWorktreeState.Removed }?.path
      else ->
        when (tree.state) {
          GoalWorktreeState.Removed -> return RecordedReceiptPath(trimmed, openable = false)
          GoalWorktreeState.Active, GoalWorktreeState.Retained -> tree.path
        }
    }?.takeIf { it.isNotBlank() } ?: return RecordedReceiptPath(trimmed, openable = false)
  val parsedBase = parseRecordedPath(base) ?: return RecordedReceiptPath(trimmed, openable = false)
  if (!parsedBase.absolute) return RecordedReceiptPath(trimmed, openable = false)
  return resolveAgainstBase(parsedBase.normalized, parsed.normalized)
}

internal expect fun resolveAgainstBase(base: String, relative: String): RecordedReceiptPath?
