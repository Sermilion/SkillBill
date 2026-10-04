package skillbill.infrastructure.skills.nativeagent.rendering

import skillbill.error.shellcontent.composedNativeAgentBudgetExceeded
import skillbill.error.shellcontent.missingContentFile
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentCompositionTarget
import skillbill.infrastructure.skills.nativeagent.composition.NativeAgentCompositionTargetSource
import skillbill.infrastructure.skills.nativeagent.composition.displayPath
import skillbill.infrastructure.skills.nativeagent.composition.platformPackRoot
import skillbill.infrastructure.skills.nativeagent.platformpack.NativeAgentAddonSelectionPolicy
import skillbill.infrastructure.skills.nativeagent.platformpack.NativeAgentGovernedAddonActivation
import skillbill.infrastructure.skills.nativeagent.platformpack.NativeAgentPlatformPack
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Path

internal fun composeGovernedAgentBody(
  repoRoot: Path,
  target: NativeAgentCompositionTarget,
  body: String,
  additionalPackRoots: List<Path> = emptyList(),
): GovernedAgentComposition {
  val root = repoRoot.toAbsolutePath().normalize()
  val resolvedAddons = resolveDeclaredAddonTargets(root, target, additionalPackRoots)
  val session = SidecarInliningSession(root, target, additionalPackRoots)
  resolvedAddons.targets.forEach { addon -> session.claim(addon.path) }
  val rewrittenBody = session.rewrite(body, target.contentPath)
  claimExcludedAddonPointerTargets(root, target, session, additionalPackRoots)
  val addonBlocks =
    resolvedAddons.targets.map { addon ->
      addon to session.rewrite(readAddonFile(root, addon), addon.path)
    }
  val composedBody =
    buildString {
      append(rewrittenBody.trimEnd())
      append(session.inlinedReferenceBlocks())
      if (addonBlocks.isNotEmpty()) {
        append("\n\n## Composed Add-Ons\n\n")
        addonBlocks.forEachIndexed { index, (addon, text) ->
          if (index > 0) {
            append("\n\n")
          }
          append("### Add-On: ${addon.slug} (${addon.path.fileName})\n\n")
          activationScope(addon.activation)?.let { scope ->
            append(scope)
            append("\n\n")
          }
          append(text.trimEnd())
        }
      }
    }.trimEnd()
  return GovernedAgentComposition(
    body = composedBody,
    composedAddonSlugs = resolvedAddons.composedAddonSlugs,
  )
}

internal fun enforceComposedAgentBudget(
  root: Path,
  target: NativeAgentCompositionTarget,
  rendered: String,
  maxBytes: Long,
  additionalPackRoots: List<Path> = emptyList(),
) {
  val bytes = rendered.toByteArray(Charsets.UTF_8).size
  if (bytes > maxBytes) {
    val packRoot =
      platformPackRoot(
        root,
        target.contentPath.toAbsolutePath().normalize(),
        additionalPackRoots,
      )
    throw composedNativeAgentBudgetExceeded(
      "pack '${packRoot?.fileName ?: displayPath(root, target.contentPath)}' skill directory " +
        "'${nativeAgentSkillRelativeDir(packRoot, target.contentPath)}': rendered native agent is $bytes bytes, " +
        "over the $maxBytes byte review context launch budget",
    )
  }
}

internal fun enforceAddonProjectionParity(
  pack: NativeAgentPlatformPack,
  specialistSkillName: String,
  composedAddonSlugs: List<String>,
) {
  val selections = NativeAgentAddonSelectionPolicy.select(pack, specialistSkillName)
  val projected = selections.map { it.slug }
  if (projected == composedAddonSlugs) {
    return
  }
  val missing = selections.firstOrNull { selection -> selection.slug !in composedAddonSlugs }
  val extra = composedAddonSlugs.firstOrNull { slug -> slug !in projected }
  val slug = missing?.slug ?: extra.orEmpty()
  val pointerName = missing?.entrypoint ?: extra.orEmpty()
  val consumer = "code-review/$specialistSkillName"
  val pointer =
    pack.pointers.firstOrNull { spec ->
      spec.skillRelativeDir == consumer && spec.name == pointerName
    }
  val repoRoot = pack.packRoot.parent?.takeIf { parent -> parent.fileName.toString() == "platform-packs" }?.parent
  val path = pointer?.let { spec -> repoRoot?.resolve(spec.target)?.toAbsolutePath()?.normalize() }
  throw missingContentFile(
    "pack '${pack.slug}' add-on '$slug' slot '$NATIVE_AGENT_ADDON_ENTRYPOINT_SLOT': declared target did not compose " +
      "at '${path ?: pointerName}'",
  )
}

internal fun nativeAgentSkillRelativeDir(
  packRoot: Path?,
  contentPath: Path,
): String =
  packRoot
    ?.toAbsolutePath()
    ?.normalize()
    ?.relativize(contentPath.toAbsolutePath().normalize().parent)
    ?.toString()
    ?.replace('\\', '/')
    .orEmpty()

private fun activationScope(activation: NativeAgentGovernedAddonActivation?): String? {
  if (activation == null) {
    return null
  }
  val signals =
    buildList {
      signal("changed paths matching any of", activation.anyPath)?.let(::add)
      signal("changed content matching any of", activation.anyContent)?.let(::add)
      signal("changed content matching all of", activation.allContent)?.let(::add)
      activation.anyOfAllContent
        .mapNotNull { group -> signal("changed content matching all of", group) }
        .takeIf { it.isNotEmpty() }
        ?.let { groups -> add("any one of these content groups: ${groups.joinToString("; ")}") }
      signal("never when changed paths match", activation.excludePath)?.let(::add)
      signal("never when changed content matches", activation.excludeContent)?.let(::add)
    }
  if (signals.isEmpty()) {
    return null
  }
  return "Declared scope: apply this rubric only to changes with ${signals.joinToString(", and ")}. " +
    "When the change under review does not match, skip it and report nothing from it."
}

private fun signal(
  prefix: String,
  values: List<String>,
): String? =
  values
    .takeIf { it.isNotEmpty() }
    ?.joinToString(", ") { value -> "`$value`" }
    ?.let { rendered -> "$prefix $rendered" }

private fun claimExcludedAddonPointerTargets(
  root: Path,
  target: NativeAgentCompositionTarget,
  session: SidecarInliningSession,
  additionalPackRoots: List<Path>,
) {
  if (target.source != NativeAgentCompositionTargetSource.PlatformManifest) {
    return
  }
  val pack = target.manifest ?: return
  val contentPath = target.contentPath.toAbsolutePath().normalize()
  val packRoot = platformPackRoot(root, contentPath, additionalPackRoots) ?: return
  val skillRelativeDir = nativeAgentSkillRelativeDir(packRoot, contentPath)
  val addonPointerNames =
    pack.addonUsage
      .flatMap { usage ->
        usage.addons.flatMap { addon -> listOf(addon.entrypoint) + addon.companionPointers }
      }
      .toSet()
  pack.pointers
    .filter { pointer -> pointer.skillRelativeDir == skillRelativeDir && pointer.name in addonPointerNames }
    .forEach { pointer ->
      session.claim(root.resolve(pointer.target).toAbsolutePath().normalize())
    }
}
