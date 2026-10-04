package skillbill.infrastructure.skills.externaladdon

import skillbill.error.core.externalAddonOverlay

internal fun validateAddonUsageEntries(
  fragment: Map<String, Any?>,
  slug: String,
) {
  val addonUsage = fragment["addon_usage"] as? Map<*, *> ?: return
  addonUsage.forEach { (dirKey, entriesRaw) ->
    val dir =
      dirKey as? String
        ?: throw externalAddonOverlay(
          "External addon source for platform '$slug': addon_usage keys must be strings.",
        )
    val entries =
      (entriesRaw as? List<*>)
        ?: throw externalAddonOverlay(
          "External addon source for platform '$slug': addon_usage[$dir] must be a list.",
        )
    entries.forEachIndexed { index, entry ->
      validateAddonUsageEntry(slug, dir, index, entry)
    }
  }
}

internal fun validateAddonUsageEntry(
  slug: String,
  dir: String,
  index: Int,
  entry: Any?,
) {
  val entryMap = requireAddonUsageEntryMap(slug, dir, index, entry)
  validateAddonUsageEntryKeys(slug, dir, index, entryMap)
  validateAddonUsageEntrySlug(slug, dir, index, entryMap)
}

private fun requireAddonUsageEntryMap(
  slug: String,
  dir: String,
  index: Int,
  entry: Any?,
): Map<*, *> =
  entry as? Map<*, *> ?: throw externalAddonOverlay(
    "External addon source for platform '$slug': addon_usage[$dir][$index] must be a mapping.",
  )

private fun validateAddonUsageEntryKeys(
  slug: String,
  dir: String,
  index: Int,
  entryMap: Map<*, *>,
) {
  val keys = entryMap.keys.mapNotNull { it as? String }.toSet()
  val extra = keys - ADDON_ENTRY_KEYS
  if (extra.isNotEmpty()) {
    throw externalAddonOverlay(
      fragmentFieldMessage(
        slug,
        "addon_usage[$dir][$index]",
        extra,
        "slug, entrypoint, companion_pointers, activation, and specialist_areas",
      ),
    )
  }
}

private fun validateAddonUsageEntrySlug(
  slug: String,
  dir: String,
  index: Int,
  entryMap: Map<*, *>,
) {
  val addonSlug = entryMap["slug"] as? String
  if (addonSlug != null && !ADDON_SLUG_PATTERN.matches(addonSlug)) {
    throw externalAddonOverlay(
      "External addon source for platform '$slug': addon_usage[$dir][$index].slug '$addonSlug' " +
        "must match '${ADDON_SLUG_PATTERN.pattern}'.",
    )
  }
}
