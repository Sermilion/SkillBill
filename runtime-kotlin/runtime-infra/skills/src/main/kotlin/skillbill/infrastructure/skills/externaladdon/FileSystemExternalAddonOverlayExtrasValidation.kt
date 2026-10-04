package skillbill.infrastructure.skills.externaladdon

import skillbill.error.core.externalAddonOverlay

internal fun fragmentFieldMessage(
  slug: String,
  field: String,
  extra: Set<String>,
  allowed: String,
): String =
  "External addon source for platform '$slug': $field has unexpected keys ${extra.sorted()} " +
    "(only $allowed are allowed)."

internal fun isValidPointerName(name: String): Boolean = POINTER_NAME_PATTERN.matches(name) && !name.contains("..")

internal fun requireFlatAddonTarget(
  slug: String,
  target: String,
) {
  val expectedPrefix = "platform-packs/$slug/$ADDONS_DIR/"
  if (!target.startsWith(expectedPrefix)) {
    throw externalAddonOverlay(
      "External addon source for platform '$slug': pointer target '$target' must start with '$expectedPrefix'.",
    )
  }
  val remainder = target.removePrefix(expectedPrefix)
  if (remainder.contains('/') || remainder.contains('\\') || remainder.isEmpty()) {
    throw externalAddonOverlay(
      "External addon source for platform '$slug': pointer target '$target' must be a flat file directly " +
        "under '$expectedPrefix' (no subdirectories).",
    )
  }
}

internal fun validateFragmentFields(
  fragment: Map<String, Any?>,
  slug: String,
) {
  validatePointerEntries(fragment, slug)
  validateAddonUsageEntries(fragment, slug)
}
