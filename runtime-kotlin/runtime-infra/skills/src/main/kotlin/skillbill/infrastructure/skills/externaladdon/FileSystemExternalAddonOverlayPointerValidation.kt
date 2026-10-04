package skillbill.infrastructure.skills.externaladdon

import skillbill.error.core.externalAddonOverlay

internal fun validatePointerEntries(
  fragment: Map<String, Any?>,
  slug: String,
) {
  val pointers = fragment["pointers"] as? Map<*, *> ?: return
  pointers.forEach { (dirKey, entriesRaw) ->
    val dir =
      dirKey as? String
        ?: throw externalAddonOverlay(
          "External addon source for platform '$slug': pointers keys must be strings.",
        )
    val entries =
      (entriesRaw as? List<*>)
        ?: throw externalAddonOverlay(
          "External addon source for platform '$slug': pointers[$dir] must be a list.",
        )
    entries.forEachIndexed { index, entry ->
      validatePointerEntry(slug, dir, index, entry)
    }
  }
}

internal fun validatePointerEntry(
  slug: String,
  dir: String,
  index: Int,
  entry: Any?,
) {
  val entryMap = requirePointerEntryMap(slug, dir, index, entry)
  validatePointerEntryKeys(slug, dir, index, entryMap)
  validatePointerEntryName(slug, dir, index, entryMap)
}

private fun requirePointerEntryMap(
  slug: String,
  dir: String,
  index: Int,
  entry: Any?,
): Map<*, *> =
  entry as? Map<*, *> ?: throw externalAddonOverlay(
    "External addon source for platform '$slug': pointers[$dir][$index] must be a mapping.",
  )

private fun validatePointerEntryKeys(
  slug: String,
  dir: String,
  index: Int,
  entryMap: Map<*, *>,
) {
  val keys = entryMap.keys.mapNotNull { it as? String }.toSet()
  val extra = keys - POINTER_ENTRY_KEYS
  if (extra.isNotEmpty()) {
    throw externalAddonOverlay(
      fragmentFieldMessage(slug, "pointers[$dir][$index]", extra, "name and target"),
    )
  }
}

private fun validatePointerEntryName(
  slug: String,
  dir: String,
  index: Int,
  entryMap: Map<*, *>,
) {
  val name = entryMap["name"] as? String
  if (name != null && !isValidPointerName(name)) {
    throw externalAddonOverlay(
      "External addon source for platform '$slug': pointers[$dir][$index].name '$name' " +
        "must be a bare markdown filename (no separators, no '..' segments, ending in '.md').",
    )
  }
}
