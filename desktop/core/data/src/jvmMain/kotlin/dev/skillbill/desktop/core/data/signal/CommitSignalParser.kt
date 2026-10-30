package dev.skillbill.desktop.core.data.signal

import skillbill.contracts.persistence.DesktopCommitSignal
import skillbill.contracts.persistence.DesktopReadSchema

internal data class ParsedSignal(val generation: String, val revisions: Map<String, Long>, val changed: Map<String, ChangedIds>)

/**
 * Parses the runtime's commit signal, or returns null when it is invalid: oversized, a line without a separator, a repeated
 * key, a missing or blank generation, a missing or malformed signalled-topic revision, or a malformed `.changed` value for a
 * signalled topic. Unknown keys are ignored so a newer runtime can add lines.
 */
internal fun parseCommitSignal(text: String): ParsedSignal? {
  if (text.length > DesktopCommitSignal.MAX_SIGNAL_CHARS) return null
  val entries = mutableMapOf<String, String>()
  for (line in text.lineSequence()) {
    if (line.isBlank()) continue
    val separator = line.indexOf(DesktopCommitSignal.REVISION_SEPARATOR)
    if (separator <= 0) return null
    val value = line.substring(separator + DesktopCommitSignal.REVISION_SEPARATOR.length)
    if (entries.put(line.substring(0, separator), value) != null) return null
  }
  val generation = entries[DesktopCommitSignal.GENERATION_LABEL]?.takeIf(String::isNotBlank) ?: return null
  val revisions = DesktopReadSchema.SIGNALLED_TOPICS.associateWith { topic ->
    entries[topic]?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
  }
  val changed = buildMap {
    DesktopReadSchema.SIGNALLED_TOPICS.forEach { topic ->
      val value = entries[topic + DesktopCommitSignal.CHANGED_SUFFIX] ?: return@forEach
      put(topic, parseChangedIds(value) ?: return null)
    }
  }
  return ParsedSignal(generation, revisions, changed)
}

private fun parseChangedIds(value: String): ChangedIds? {
  val separator = value.indexOf(DesktopCommitSignal.CHANGED_BASE_SEPARATOR)
  if (separator < 0) return null
  val base = value.substring(0, separator).toLongOrNull()?.takeIf { it >= 0 } ?: return null
  val ids = value.substring(separator + DesktopCommitSignal.CHANGED_BASE_SEPARATOR.length)
  if (ids == DesktopCommitSignal.OVERFLOW_MARKER) return ChangedIds.All
  return ChangedIds.Ids(base, ids.split(DesktopCommitSignal.CHANGED_ID_SEPARATOR).filter(String::isNotBlank))
}

/**
 * The snapshot after reading [parsed]. An invalid payload (null) or a new generation resynchronizes. A topic whose revision
 * moved without a `.changed` line is [ChangedIds.All].
 */
internal fun SignalSnapshot.advance(parsed: ParsedSignal?): SignalSnapshot {
  if (parsed == null) return copy(resynchronize = true, resyncEpoch = resyncEpoch + 1)
  val resynchronize = generation != null && parsed.generation != generation
  val changed = parsed.revisions.filter { (topic, revision) -> revisions[topic] != revision }
    .mapValues { (topic, _) -> parsed.changed[topic] ?: ChangedIds.All }
  return SignalSnapshot(
    generation = parsed.generation,
    revisions = parsed.revisions,
    changed = changed,
    resynchronize = resynchronize,
    resyncEpoch = if (resynchronize) resyncEpoch + 1 else resyncEpoch,
  )
}
