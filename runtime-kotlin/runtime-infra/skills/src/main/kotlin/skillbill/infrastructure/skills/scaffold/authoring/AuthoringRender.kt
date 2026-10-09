package skillbill.infrastructure.skills.scaffold.authoring
import skillbill.infrastructure.skills.nativeagent.composition.NATIVE_AGENT_BUNDLE_FILE
import skillbill.infrastructure.skills.nativeagent.composition.NATIVE_AGENT_SOURCE_DIR
import skillbill.infrastructure.skills.nativeagent.composition.parseNativeAgentSourceFile
import skillbill.infrastructure.skills.scaffold.rendering.renderFrontmatter
import skillbill.infrastructure.skills.scaffold.rendering.renderSubagentSpawnRuntimeNotes
import skillbill.infrastructure.skills.scaffold.validation.shape.parseSkillFrontmatter
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.GovernedAddonSelection
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal fun renderWrapper(target: AuthoringTarget): String {
  val contentText = Files.readString(target.contentFile)
  authoredContentFrontmatterBlock(contentText, target.contentFile, target.skillName)
  val sourceFrontmatter = parseSkillFrontmatter(contentText)
  val frontmatter =
    renderFrontmatter(
      skillName = sourceFrontmatter["name"].orEmpty(),
      description = sourceFrontmatter["description"].orEmpty(),
    )
  val executionBody = renderedAuthoredExecutionBody(contentText, target.contentFile, target.skillName)
  return assembleRenderedWrapper(
    target = target,
    frontmatter = frontmatter,
    executionBody = executionBody,
    context = renderedWrapperTemplateContext(target),
    skillClass = resolvedWrapperSkillClass(target),
  )
}

internal fun renderGovernedAddonsSection(target: AuthoringTarget): String {
  val addons = target.addonUsage
  if (addons.isEmpty()) {
    return ""
  }
  return buildString {
    appendLine("## Governed Add-Ons")
    appendLine()
    appendLine(
      "This platform pack declares add-on usage for this skill in `platform.yaml`. Runtime agents MUST resolve " +
        "these add-ons only after stack routing has selected `${target.platform}`.",
    )
    appendLine()
    appendLine(
      "Start from `Selected add-ons: none`. Scan each declared entrypoint's `## Activation signals` and " +
        "`## Section index` headings first, select only add-ons whose cues match the scoped work, then open " +
        "only the selected entrypoint and companion pointers needed for that scope.",
    )
    appendLine(
      "Report the final selection as `Selected add-ons: <slug, ...>` and pass the selected add-ons into any " +
        "specialist passes. Add-ons enrich this routed skill; they do not create standalone skill names or " +
        "bypass stack routing.",
    )
    appendLine()
    appendLine("### Declared Add-Ons")
    appendLine()
    addons.forEach { addon ->
      appendLine("- ${renderAddonSelection(addon)}")
    }
  }
}

private fun renderAddonSelection(addon: GovernedAddonSelection): String {
  val companionText =
    if (addon.companionPointers.isEmpty()) {
      "companions `none`"
    } else {
      "companions ${addon.companionPointers.joinToString(", ") { pointer -> "`$pointer`" }}"
    }
  return "`${addon.slug}`: entrypoint `${addon.entrypoint}`; $companionText."
}

internal fun renderReviewCompositionSection(target: AuthoringTarget): String {
  val baselineLayers = target.codeReviewComposition?.baselineLayers.orEmpty()
  val universalLanes = target.universalReviewLanes
  if (baselineLayers.isEmpty() && universalLanes.isEmpty()) return ""
  return buildString {
    appendLine("## Review Composition")
    appendLine()
    if (baselineLayers.isNotEmpty()) {
      appendLine(
        "This platform pack declares code-review composition in `platform.yaml`. The runtime MUST recursively " +
          "flatten required baseline layers into direct specialist lanes before any worker starts. It MUST NOT " +
          "launch a baseline orchestrator worker.",
      )
      appendLine()
      appendLine(
        "Scope propagation is mandatory: every flattened lane receives the same authoritative review packet, " +
          "including review IDs, changed hunks, resolved guidance, stack signals, and add-ons.",
      )
      appendLine(
        "Keep baseline-layer findings attributed to that layer before merging and deduplicating them with pack-local " +
          "specialist findings.",
      )
      appendLine()
      appendLine("### Required Baseline Layers")
      appendLine()
      baselineLayers.forEach { layer -> appendLine("- ${renderBaselineLayerLabel(layer)}") }
    }
    if (universalLanes.isNotEmpty()) {
      if (baselineLayers.isEmpty()) {
        appendLine("Append manifest-declared universal lanes after the routed lanes when their area is selected.")
        appendLine()
      } else {
        appendLine()
      }
      appendLine("### Universal Review Lanes")
      appendLine()
      universalLanes.forEach { lane ->
        appendLine("- `${lane.area}` -> `${lane.skillName}`; required `${lane.required}`, order `${lane.orderIndex}`.")
      }
    }
  }
}

private fun renderBaselineLayerLabel(layer: CodeReviewBaselineLayer): String =
  "`" + layer.platform + "/" + layer.skill + "` with scope `" + layer.scope.wireValue + "`, mode `" +
    layer.mode.wireValue + "`, required `" + layer.required + "`."

internal fun renderGeneratedSubagentSpawnRuntimeNotes(target: AuthoringTarget): String {
  val nativeAgentDir = target.contentFile.parent.resolve(NATIVE_AGENT_SOURCE_DIR)
  if (!Files.isDirectory(nativeAgentDir)) {
    return ""
  }
  val specialists =
    nativeAgentSourceFiles(nativeAgentDir)
      .flatMap(::parseNativeAgentSourceFile)
      .map { source -> source.name }
  return renderSubagentSpawnRuntimeNotes(target.skillName, specialists)
}

private fun nativeAgentSourceFiles(nativeAgentDir: Path): List<Path> =
  Files.list(nativeAgentDir).use { stream ->
    stream
      .filter { file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) }
      .filter { file -> isNativeAgentSourceFile(file) }
      .sorted(Comparator.comparing { file -> file.fileName.toString() })
      .toList()
  }

private fun isNativeAgentSourceFile(file: Path): Boolean {
  val fileName = file.fileName.toString()
  return fileName.endsWith(".md") || fileName == NATIVE_AGENT_BUNDLE_FILE
}
