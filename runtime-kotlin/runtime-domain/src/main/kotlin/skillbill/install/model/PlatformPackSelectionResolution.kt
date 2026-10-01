package skillbill.install.model

const val PACK_SIDECAR_PARENT_SKILL = "skill-bill"

fun selectedPlatformSlugs(input: InstallPolicyInput): List<String> {
  val explicitlySelected =
    selectedPlatformSlugs(
      selection = input.request.platformPackSelection,
      discoveredSlugs = input.platformPacks.map(InstallPlatformPackSnapshot::slug),
    )
  val selected = explicitlySelected.toMutableSet()
  if (input.baseSkills.any { it.name == PACK_SIDECAR_PARENT_SKILL }) {
    input.resolvedReviewFallbackSlug?.let(selected::add)
  }
  var changed: Boolean
  do {
    changed = false
    input.platformPacks.forEach { pack ->
      val selectedRequiredBaseline =
        pack.baselineLayers.any { layer ->
          layer.required && layer.platform in selected
        }
      if (selectedRequiredBaseline && selected.add(pack.slug)) {
        changed = true
      }
    }
  } while (changed)
  return input.platformPacks.map(InstallPlatformPackSnapshot::slug).filter(selected::contains)
}

fun selectedPlatformSlugs(
  selection: PlatformPackSelection,
  discoveredSlugs: List<String>,
): List<String> =
  when (selection.mode) {
    PlatformPackSelectionMode.NONE -> emptyList()
    PlatformPackSelectionMode.ALL -> discoveredSlugs
    PlatformPackSelectionMode.SELECTED -> {
      val unknown = selection.selectedSlugs - discoveredSlugs.toSet()
      require(unknown.isEmpty()) {
        "Unknown platform pack selection: ${unknown.sorted().joinToString(", ")}. " +
          "Discovered platform packs: ${discoveredSlugs.joinToString(", ")}."
      }
      discoveredSlugs.filter { slug -> slug in selection.selectedSlugs }
    }
  }
