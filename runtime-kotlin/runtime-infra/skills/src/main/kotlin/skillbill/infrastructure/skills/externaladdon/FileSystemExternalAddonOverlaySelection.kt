package skillbill.infrastructure.skills.externaladdon

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.externalAddonOverlay
import skillbill.infrastructure.skills.scaffold.platformpack.loader.AddonUsageManifestContext
import skillbill.infrastructure.skills.scaffold.platformpack.loader.parseAddonUsage
import skillbill.infrastructure.skills.scaffold.platformpack.loader.parsePointers
import skillbill.infrastructure.skills.scaffold.platformpack.manifest.declaredSkillRelativeDirs
import skillbill.install.model.ExternalAddonSource
import skillbill.model.toPath
import skillbill.scaffold.model.GovernedAddonSelection
import skillbill.scaffold.model.GovernedAddonUsage
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import java.nio.file.Files
import java.nio.file.Path

internal fun validateAndPlan(
  source: ExternalAddonSource,
  installed: PlatformManifest,
  collisionIndex: CollisionIndex,
): SourcePlan {
  val slug = source.platform
  val fragment = readSourceManifest(source.path.toPath(), slug)
  val rewritten = rewriteFragmentTargets(fragment, slug)
  validateFragmentFields(rewritten, slug)
  val fragmentPointers = wrapParserErrors(slug) { parsePointers(rewritten, slug) }
  fragmentPointers.forEach { pointer -> requireFlatAddonTarget(slug, pointer.target) }
  val fragmentAddonUsage =
    wrapParserErrors(slug) {
      parseAddonUsage(
        rewritten,
        AddonUsageManifestContext(
          slug = slug,
          packRoot = installed.packRoot.toPath(),
          pointers = fragmentPointers,
          declaredSkillDirs = installed.declaredSkillRelativeDirs(),
          declaredAreas = installed.declaredCodeReviewAreas.toSet(),
          strictReviewRouting = installed.laneConditions.isNotEmpty(),
        ),
      )
    }

  val filesToCopy = linkedMapOf<String, Path>()
  fragmentPointers.forEach { pointer ->
    val filename = pointer.target.substringAfterLast('/')
    verifySourceFile(source.path.toPath(), slug, filename)
    filesToCopy[filename] = source.path.resolve(filename).toPath()
  }

  val pointersToAppend = collectPointersToAppend(slug, fragmentPointers, collisionIndex)
  val addonsToAppend = collectAddonsToAppend(slug, fragmentAddonUsage, collisionIndex)

  return SourcePlan(
    platform = slug,
    sourcePath = source.path.toPath(),
    installedManifestPath = installed.packRoot.resolve(MANIFEST_FILE).toPath(),
    packRoot = installed.packRoot.toPath(),
    pointersToAppend = pointersToAppend,
    addonsToAppend = addonsToAppend,
    copiedFiles = filesToCopy,
  )
}

internal fun collectPointersToAppend(
  slug: String,
  pointers: List<PointerSpec>,
  collisions: CollisionIndex,
): MutableMap<String, MutableList<PointerSpec>> {
  val result = mutableMapOf<String, MutableList<PointerSpec>>()
  for (pointer in pointers) {
    val nameKey = pointer.dirName()
    val targetKey = pointer.skillRelativeDir to basename(pointer.target)
    when (val outcome = collisions.classifyPointer(slug, pointer, nameKey, targetKey)) {
      PointerCollisionOutcome.AlreadyPresent -> Unit
      is PointerCollisionOutcome.NameCollision -> throw externalAddonOverlay(
        collisionMessage(slug, pointer.skillRelativeDir, pointer.name, outcome.existingTarget, pointer.target),
      )
      is PointerCollisionOutcome.TargetCollision -> throw externalAddonOverlay(
        targetCollisionMessage(slug, pointer, outcome),
      )
      PointerCollisionOutcome.New -> {
        collisions.recordExternalPointer(slug, nameKey, targetKey, pointer)
        result.getOrPut(pointer.skillRelativeDir) { mutableListOf() } += pointer
      }
    }
  }
  return result
}

internal fun collectAddonsToAppend(
  slug: String,
  addonUsage: List<GovernedAddonUsage>,
  collisions: CollisionIndex,
): MutableMap<String, MutableList<GovernedAddonSelection>> {
  val result = mutableMapOf<String, MutableList<GovernedAddonSelection>>()
  for (usage in addonUsage) {
    val dir = usage.skillRelativeDir
    for (selection in usage.addons) {
      when (val outcome = collisions.classifyAddon(slug, dir, selection)) {
        AddonCollisionOutcome.AlreadyPresent -> Unit
        is AddonCollisionOutcome.Collision -> throw externalAddonOverlay(
          addonCollisionMessage(slug, dir, selection.slug, outcome.existing, selection),
        )
        AddonCollisionOutcome.New -> {
          collisions.recordExternalAddon(slug, dir, selection)
          result.getOrPut(dir) { mutableListOf() } += selection
        }
      }
    }
  }
  return result
}

internal fun readSourceManifest(
  sourcePath: Path,
  slug: String,
): Map<String, Any?> {
  val manifestPath = sourcePath.resolve(SOURCE_MANIFEST_FILE)
  if (!Files.isRegularFile(manifestPath)) {
    throw missingSourceManifestError(slug, manifestPath)
  }
  val rawMap = loadSourceManifestYamlMap(slug, manifestPath)
  return typedSourceManifestMap(slug, rawMap)
}

private fun missingSourceManifestError(
  slug: String,
  manifestPath: Path,
): SkillBillRuntimeException =
  externalAddonOverlay(
    "External addon source for platform '$slug': expected '$manifestPath' but it is missing.",
  )

private fun loadSourceManifestYamlMap(
  slug: String,
  manifestPath: Path,
): Map<*, *> {
  val raw =
    try {
      Yaml().load<Any?>(Files.readString(manifestPath))
    } catch (error: YAMLException) {
      throw externalAddonOverlay(
        "External addon source for platform '$slug': manifest '$manifestPath' is not valid YAML: ${error.message}",
        error,
      )
    }
  return raw as? Map<*, *>
    ?: throw externalAddonOverlay(
      "External addon source for platform '$slug': manifest '$manifestPath' must be a YAML mapping.",
    )
}

private fun typedSourceManifestMap(
  slug: String,
  rawMap: Map<*, *>,
): Map<String, Any?> {
  val typed = linkedMapOf<String, Any?>()
  rawMap.forEach { (k, v) ->
    val key =
      k as? String
        ?: throw externalAddonOverlay(
          "External addon source for platform '$slug': manifest keys must be strings.",
        )
    typed[key] = v
  }
  return typed
}

internal fun rewriteFragmentTargets(
  fragment: Map<String, Any?>,
  slug: String,
): Map<String, Any?> {
  val pointers = (fragment["pointers"] as? Map<*, *>) ?: return fragment
  val rewritten = linkedMapOf<String, Any?>()
  fragment.forEach { (k, v) -> rewritten[k] = v }
  val rewrittenPointers = linkedMapOf<String, Any?>()
  val canonicalPrefix = "platform-packs/$slug/$ADDONS_DIR/"
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
    val rewrittenEntries = entries.map { entry -> rewritePointerEntry(slug, dir, entry, canonicalPrefix) }
    rewrittenPointers[dir] = rewrittenEntries
  }
  rewritten["pointers"] = rewrittenPointers
  return rewritten
}

internal fun rewritePointerEntry(
  slug: String,
  dir: String,
  entry: Any?,
  canonicalPrefix: String,
): MutableMap<String, Any?> {
  val rawMap =
    entry as? Map<*, *>
      ?: throw externalAddonOverlay(
        "External addon source for platform '$slug': pointers[$dir] entries must be mappings.",
      )
  val map = linkedMapOf<String, Any?>()
  rawMap.forEach { (k, v) -> map[k as String] = v }
  val target =
    map["target"] as? String
      ?: throw externalAddonOverlay(
        "External addon source for platform '$slug': pointers[$dir] entry is missing string field 'target'.",
      )
  if (!target.startsWith(canonicalPrefix)) {
    val filename = Path.of(target).fileName.toString()
    map["target"] = canonicalPrefix + filename
  }
  return map
}
