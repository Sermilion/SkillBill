package dev.skillbill.desktop.feature.detail

internal data class PreplanDigestRow(val title: String, val preview: String, val items: List<String>, val addons: List<String> = emptyList())

private val preplanTitles = listOf("History and decisions", "Reusable patterns", "Add-ons", "Validation strategy")

internal fun preplanDigestTitle(title: String): String? = when (title.lowercase().trim().trimEnd(':').replace('_', ' ')) {
  "history and decisions", "history/decisions", "history / decisions", "boundary history/decision findings", "history and decision findings" -> preplanTitles[0]
  "reusable patterns", "patterns" -> preplanTitles[1]
  "add-ons", "add ons", "addons", "selected add-ons" -> preplanTitles[2]
  "validation strategy", "validation" -> preplanTitles[3]
  else -> null
}

internal fun preplanDigestRows(sections: List<PlanDigestSection>): List<PreplanDigestRow> = preplanTitles.map { title ->
  val items = sections.filter { preplanDigestTitle(it.title) == title }.flatMap { it.items }
  val addons = if (title == "Add-ons") items.flatMap { addonName.findAll(it).map { match -> match.value }.toList() }.distinct() else emptyList()
  val preview = when {
    items.isEmpty() -> "Not retained"
    title == "History and decisions" -> "${items.size} recorded"
    title == "Reusable patterns" -> "${items.size} found"
    title == "Add-ons" && addons.isNotEmpty() -> addons.joinToString(", ", transform = ::laneChipLabel)
    else -> items.first().replace('`', ' ').trim().let { if (it.length > 60) it.take(57) + "…" else it }
  }
  PreplanDigestRow(title, preview, items, addons)
}

private val addonName = Regex("\\bbill-[a-z0-9]+(?:-[a-z0-9]+)+\\b")
