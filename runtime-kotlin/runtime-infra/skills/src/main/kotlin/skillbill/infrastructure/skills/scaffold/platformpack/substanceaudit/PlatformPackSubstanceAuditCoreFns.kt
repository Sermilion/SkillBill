package skillbill.infrastructure.skills.scaffold.platformpack.substanceaudit

import skillbill.error.core.failureCodeLabel
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformManifest
import skillbill.model.toPath
import skillbill.scaffold.model.PlatformManifest
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer
import java.util.Locale
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.relativeTo

internal const val SHINGLE_WIDTH = 5

internal fun auditPlatformPacks(
  repoRoot: Path,
  policy: SubstancePolicy = SubstancePolicy(),
): PlatformPackSubstanceReport = auditPlatformPacksImpl(repoRoot, policy)

internal fun normalizeAuthoredText(
  text: String,
  names: Collection<String> = emptyList(),
): List<String> {
  var normalized = text.replace("\r\n", "\n").replace(Regex("(?s)\\A---\\n.*?\\n---\\n"), "")
  normalized = normalized.replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
  normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
  names.filter { it.isNotBlank() }.sortedByDescending { it.length }.forEach { name ->
    val lexicalName = Regex.escape(name.lowercase(Locale.ROOT))
    normalized =
      normalized.replace(
        Regex("(?<![\\p{L}\\p{N}_])$lexicalName(?![\\p{L}\\p{N}_])"),
        rolePlaceholder(name),
      )
  }
  return normalized.replace(Regex("[`*_~>#|{}\\[\\]()]"), " ")
    .replace(Regex("[^\\p{L}\\p{N}./:_+-]+"), " ").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
}

internal fun authoredShingles(tokens: List<String>): Set<String> =
  if (tokens.size < SHINGLE_WIDTH) {
    emptySet()
  } else {
    tokens.windowed(SHINGLE_WIDTH).map {
      it.joinToString(" ")
    }.toSet()
  }

internal fun discoverManifests(root: Path): Pair<List<PlatformManifest>, List<String>> {
  val packsRoot = root.resolve("platform-packs")
  if (!packsRoot.isDirectory()) return emptyList<PlatformManifest>() to emptyList()
  val errors = mutableListOf<String>()
  val manifests =
    Files.list(packsRoot).use { stream ->
      stream.filter { it.isDirectory() && !it.name.startsWith(".") }.sorted().map { packRoot ->
        val manifest = packRoot.resolve("platform.yaml")
        if (!manifest.isRegularFile()) {
          errors += "platform-packs/${packRoot.name}/platform.yaml: maintained platform pack manifest is missing"
          null
        } else {
          runCatching { loadPlatformManifest(packRoot) }.getOrElse { error ->
            errors += "platform-packs/${packRoot.name}/platform.yaml: ${error.message ?: error::class.simpleName}"
            null
          }
        }
      }.filter { it != null }.map { it!! }.toList()
    }
  return manifests to errors.sorted()
}

internal fun retainManifestsWithReadableDeclaredContent(
  root: Path,
  manifests: List<PlatformManifest>,
): Pair<List<PlatformManifest>, List<String>> {
  val errors = mutableListOf<String>()
  val readable =
    manifests.filter { pack ->
      declaredContentPaths(pack).mapNotNull { path ->
        runCatching { Files.readString(path) }.exceptionOrNull()?.let { error ->
          val source = path.toAbsolutePath().normalize().relativeTo(root).toString()
          val errorName = error.failureCodeLabel() ?: error::class.simpleName
          "$source: declared authored content is missing or unreadable ($errorName: ${error.message})"
        }
      }.also(errors::addAll).isEmpty()
    }
  return readable to errors.sorted()
}

internal fun declaredContentPaths(pack: PlatformManifest): List<Path> =
  buildList {
    pack.declaredFiles.baseline?.let { baseline -> add(baseline.toPath()) }
    addAll(pack.declaredFiles.areas.values.map { area -> area.toPath() })
  }.distinct().sortedBy(Path::toString)

internal fun authoredFiles(pack: PlatformManifest): List<AuthoredFile> {
  val declared =
    buildList {
      pack.declaredFiles.baseline?.let { add(Triple(AuthoredFileRole.BASELINE, null, it)) }
      pack.declaredFiles.areas.forEach { (area, path) -> add(Triple(AuthoredFileRole.SPECIALIST, area, path)) }
    }
  return declared.flatMap { (role, area, path) ->
    val names = listOf(pack.slug, pack.displayName.orEmpty(), path.toPath().parent.name)
    val primary =
      AuthoredFile(
        pack.slug,
        role,
        area,
        path.toPath(),
        authoredShingles(normalizeAuthoredText(Files.readString(path.toPath()), names)),
      )
    val sidecars =
      if (role == AuthoredFileRole.SPECIALIST) {
        linkedSidecars(
          path.toPath(),
        ).map { sidecar ->
          AuthoredFile(
            pack.slug,
            AuthoredFileRole.SIDECAR,
            area,
            sidecar,
            authoredShingles(normalizeAuthoredText(Files.readString(sidecar), names)),
          )
        }
      } else {
        emptyList()
      }
    listOf(primary) + sidecars
  }
}

internal fun linkedSidecars(content: Path): List<Path> =
  Regex(
    "\\[[^]]+]\\(([^)]+\\.md)\\)",
  ).findAll(Files.readString(content)).mapNotNull { match ->
    val candidate = content.parent.resolve(match.groupValues[1]).normalize()
    candidate.takeIf { it.parent == content.parent && it.isRegularFile() && it.name !in GENERATED_POINTER_NAMES }
  }.distinct().sortedBy { it.toString() }.toList()

internal fun effectiveAreas(
  slug: String,
  packs: Map<String, PlatformManifest>,
  visiting: Set<String> = emptySet(),
): Set<String> = resolveEffectiveAreas(slug, packs, visiting).areas

internal data class AreaResolution(val areas: Set<String>, val valid: Boolean)

internal fun resolveEffectiveAreas(
  slug: String,
  packs: Map<String, PlatformManifest>,
  visiting: Set<String> = emptySet(),
): AreaResolution {
  if (slug in visiting) return AreaResolution(emptySet(), false)
  val pack = packs[slug] ?: return AreaResolution(emptySet(), false)
  var valid = true
  val inherited =
    pack.codeReviewComposition?.baselineLayers.orEmpty().filter { it.required }.flatMap { layer ->
      val target = packs[layer.platform]
      if (target?.routedSkillName != layer.skill) {
        valid = false
        emptySet()
      } else {
        val resolved = resolveEffectiveAreas(layer.platform, packs, visiting + slug)
        valid = valid && resolved.valid
        if (resolved.valid) resolved.areas else emptySet()
      }
    }.toSet()
  return AreaResolution(pack.declaredCodeReviewAreas.toSet() + inherited, valid)
}
