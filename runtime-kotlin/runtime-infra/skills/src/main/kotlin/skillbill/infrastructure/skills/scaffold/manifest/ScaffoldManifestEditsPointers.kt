
package skillbill.infrastructure.skills.scaffold.manifest

internal fun appendManifestPointer(
  text: String,
  skillRelativeDir: String,
  pointerName: String,
  target: String,
): String =
  appendPointerLikeEntry(
    PointerLikeEntryAppendRequest(
      text = text,
      blockName = "pointers",
      skillRelativeDir = skillRelativeDir,
      entryName = pointerName,
      renderBlock = {
        "  $skillRelativeDir:\n" +
          "    - name: ${yamlScalar(pointerName)}\n" +
          "      target: ${yamlScalar(target)}\n"
      },
      renderEntry = {
        "    - name: ${yamlScalar(pointerName)}\n" +
          "      target: ${yamlScalar(target)}\n"
      },
      existingEntryPattern =
        Regex(
          "^    - name:\\s*['\"]?${Regex.escape(pointerName)}['\"]?\\s*$",
          RegexOption.MULTILINE,
        ),
    ),
  )

internal fun appendAddonUsage(
  text: String,
  skillRelativeDir: String,
  addonSlug: String,
  pointerName: String,
): String {
  val reviewFields =
    if (skillRelativeDir.startsWith("code-review/")) {
      buildString {
        appendLine("      activation: { any_path: [\"**\"] }")
        if (skillRelativeDir.endsWith("-code-review")) {
          val areas = declaredReviewAreas(text)
          if (areas.isNotEmpty()) {
            appendLine("      specialist_areas: [${areas.joinToString(", ", transform = ::yamlScalar)}]")
          }
        }
      }
    } else {
      ""
    }
  return appendPointerLikeEntry(
    PointerLikeEntryAppendRequest(
      text = text,
      blockName = "addon_usage",
      skillRelativeDir = skillRelativeDir,
      entryName = addonSlug,
      renderBlock = {
        "  $skillRelativeDir:\n" +
          "    - slug: ${yamlScalar(addonSlug)}\n" +
          "      entrypoint: ${yamlScalar(pointerName)}\n" + reviewFields
      },
      renderEntry = {
        "    - slug: ${yamlScalar(addonSlug)}\n" +
          "      entrypoint: ${yamlScalar(pointerName)}\n" + reviewFields
      },
      existingEntryPattern =
        Regex(
          "^    - slug:\\s*['\"]?${Regex.escape(addonSlug)}['\"]?\\s*$",
          RegexOption.MULTILINE,
        ),
    ),
  )
}

internal fun appendPointerLikeEntry(request: PointerLikeEntryAppendRequest): String {
  val blockRange =
    topLevelBlockRange(request.text, request.blockName)
      ?: return request.text.trimEnd() + "\n\n${request.blockName}:\n${request.renderBlock()}"
  val skillRange = nestedSkillDirRange(request.text, blockRange, request.skillRelativeDir)
  if (skillRange == null) {
    return request.text.replaceRange(blockRange.last + 1, blockRange.last + 1, request.renderBlock())
  }
  val existingBlock = request.text.substring(skillRange)
  if (request.existingEntryPattern.containsMatchIn(existingBlock)) {
    return request.text
  }
  return request.text.replaceRange(skillRange.last + 1, skillRange.last + 1, request.renderEntry())
}

private val TOP_LEVEL_KEY_PATTERN = Regex("^[^\\s#][^:\\n]*:", RegexOption.MULTILINE)

internal fun topLevelBlockRange(
  text: String,
  blockName: String,
): IntRange? {
  val header = Regex("^${Regex.escape(blockName)}:\\s*$", RegexOption.MULTILINE).find(text) ?: return null
  val next = TOP_LEVEL_KEY_PATTERN.find(text, startIndex = header.range.last + 1)
  val endExclusive = next?.range?.first ?: text.length
  return header.range.first until endExclusive
}

internal fun nestedSkillDirRange(
  text: String,
  blockRange: IntRange,
  skillRelativeDir: String,
): IntRange? {
  val block = text.substring(blockRange)
  val localHeader =
    Regex(
      "^  ${Regex.escape(skillRelativeDir)}:\\s*$",
      RegexOption.MULTILINE,
    ).find(block) ?: return null
  val start = blockRange.first + localHeader.range.first
  val next =
    Regex("^  [^\\s].*:\\s*$", RegexOption.MULTILINE)
      .find(text, startIndex = start + localHeader.value.length)
  val endExclusive = next?.range?.first?.takeIf { it <= blockRange.last + 1 } ?: (blockRange.last + 1)
  return start until endExclusive
}
