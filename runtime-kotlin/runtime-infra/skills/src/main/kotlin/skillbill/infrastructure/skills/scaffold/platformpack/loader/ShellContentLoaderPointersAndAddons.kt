
package skillbill.infrastructure.skills.scaffold.platformpack.loader

import skillbill.infrastructure.skills.scaffold.platformpack.loader.apprun.AppRunManifestKeys
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.APP_RUN_CONTRACT_VERSION
import skillbill.scaffold.model.AppRunDeclaration
import skillbill.scaffold.model.PointerSpec
import skillbill.scaffold.model.QaCapabilityDeclaration
import skillbill.scaffold.model.QaTargetKind
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

internal fun parsePointers(
  manifest: Map<*, *>,
  slug: String,
): List<PointerSpec> {
  val raw = manifest["pointers"] ?: return emptyList()
  val pointersMap =
    raw as? Map<*, *>
      ?: invalidManifestSchema(
        "Platform pack '$slug': 'pointers' must be a mapping of skill-relative-dir to a list of pointer entries.",
      )
  val seen = mutableSetOf<Pair<String, String>>()
  val collected = mutableListOf<PointerSpec>()
  for ((dirKey, entriesRaw) in pointersMap) {
    val skillRelativeDir =
      dirKey as? String
        ?: invalidManifestSchema(
          "Platform pack '$slug': 'pointers' keys must be strings (skill-relative directory paths).",
        )
    if (skillRelativeDir.isBlank()) {
      invalidManifestSchema(
        "Platform pack '$slug': 'pointers' skill-relative directory must be a non-empty string.",
      )
    }
    requireSafePointerSubpath(slug, skillRelativeDir, "pointers skill-relative directory")
    val entriesList =
      entriesRaw as? List<*>
        ?: invalidManifestSchema(
          "Platform pack '$slug': 'pointers[$skillRelativeDir]' must be a list of {name, target} entries.",
        )
    for (entry in entriesList) {
      val entryMap =
        entry as? Map<*, *>
          ?: invalidManifestSchema(
            "Platform pack '$slug': 'pointers[$skillRelativeDir]' entries must be mappings with name and target.",
          )
      val spec = parsePointerEntry(slug, skillRelativeDir, entryMap)
      val key = spec.skillRelativeDir to spec.name
      if (!seen.add(key)) {
        invalidManifestSchema(
          "Platform pack '$slug': duplicate pointer entry '${spec.name}' under '${spec.skillRelativeDir}'.",
        )
      }
      collected += spec
    }
  }
  return collected
}

internal const val FEATURE_TASK_ADDON_CONSUMER: String = "feature-task"

internal fun parseOptionalPath(
  manifest: Map<*, *>,
  slug: String,
  key: String,
  packRoot: Path,
): Path? {
  val raw = manifest[key] ?: return null
  val value =
    raw as? String
      ?: invalidManifestSchema("Platform pack '$slug': '$key' must be a non-empty path string when provided.")
  if (value.isBlank()) {
    invalidManifestSchema("Platform pack '$slug': '$key' must be a non-empty path string when provided.")
  }
  return packRoot.resolve(value).normalize()
}

internal fun parseQaCapability(
  manifest: Map<*, *>,
  slug: String,
  packRoot: Path,
): QaCapabilityDeclaration? {
  val raw = manifest["qa_capability"] ?: return null
  val capability =
    raw as? Map<*, *> ?: invalidManifestSchema("Platform pack '$slug': 'qa_capability' must be a mapping when present.")
  val platforms =
    parseQaTargetKinds(capability["platforms"], slug, "qa_capability.platforms", "QA target kind")
  val guidanceFile = requireQaGuidanceFile(capability, slug, packRoot)
  val hints =
    (capability["discovery_hints"] ?: emptyList<String>()) as? List<*>
      ?: invalidManifestSchema("Platform pack '$slug': 'qa_capability.discovery_hints' must be a list.")
  return QaCapabilityDeclaration(
    platforms = platforms,
    guidanceFile = guidanceFile,
    discoveryHints =
      hints.map { hint ->
        (hint as? String)?.takeIf(String::isNotBlank)
          ?: invalidManifestSchema(
            "Platform pack '$slug': 'qa_capability.discovery_hints' must hold non-blank strings.",
          )
      },
  )
}

private fun parseQaTargetKinds(
  raw: Any?,
  slug: String,
  field: String,
  duplicateLabel: String,
): Set<QaTargetKind> {
  val items =
    (raw as? List<*>)?.takeIf(List<*>::isNotEmpty)
      ?: invalidManifestSchema("Platform pack '$slug': '$field' must be a non-empty list.")
  val kinds = LinkedHashSet<QaTargetKind>()
  for (value in items) {
    val token =
      (value as? String)?.takeIf(QaTargetKind::isValid)
        ?: invalidManifestSchema(
          "Platform pack '$slug': '$field' item '$value' must be a string matching " +
            "${QaTargetKind.PATTERN_SOURCE}.",
        )
    if (!kinds.add(QaTargetKind(token))) {
      invalidManifestSchema("Platform pack '$slug': '$field' repeats $duplicateLabel '$token'.")
    }
  }
  return kinds
}

internal fun parseAppRun(
  manifest: Map<*, *>,
  slug: String,
  packRoot: Path,
): AppRunDeclaration? {
  if (!manifest.containsKey(AppRunManifestKeys.APP_RUN)) return null
  val raw = manifest[AppRunManifestKeys.APP_RUN]
  val appRun =
    raw as? Map<*, *> ?: invalidManifestSchema(
      "Platform pack '$slug': '${AppRunManifestKeys.APP_RUN}' must be a mapping when present.",
    )
  if (appRun[AppRunManifestKeys.CONTRACT_VERSION] != APP_RUN_CONTRACT_VERSION) {
    invalidManifestSchema(
      "Platform pack '$slug': '${AppRunManifestKeys.APP_RUN}.${AppRunManifestKeys.CONTRACT_VERSION}' " +
        "must be '$APP_RUN_CONTRACT_VERSION'.",
    )
  }
  return AppRunDeclaration(
    platforms =
      parseQaTargetKinds(
        appRun[AppRunManifestKeys.PLATFORMS],
        slug,
        "${AppRunManifestKeys.APP_RUN}.${AppRunManifestKeys.PLATFORMS}",
        "target kind",
      ),
    script = requireAppRunScript(appRun, slug, packRoot),
  )
}

private fun requireAppRunScript(
  appRun: Map<*, *>,
  slug: String,
  packRoot: Path,
): String {
  val script = appRun[AppRunManifestKeys.SCRIPT] as? String
  val scriptPattern = Regex("^[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)*$")
  if (script.isNullOrBlank() || !scriptPattern.matches(script) || script.split('/').contains("..")) {
    invalidManifestSchema(
      "Platform pack '$slug': 'app_run.script' must be a pack-relative POSIX path without '..' segments.",
    )
  }
  val root = packRoot.toAbsolutePath().normalize()
  val resolved = root.resolve(script).normalize()
  if (Path.of(script).isAbsolute || !resolved.startsWith(root)) {
    invalidManifestSchema("Platform pack '$slug': 'app_run.script' must stay inside the pack.")
  }
  val regularFile =
    try {
      Files.isRegularFile(resolved)
    } catch (failure: SecurityException) {
      invalidManifestSchemaFromPath("Platform pack '$slug': 'app_run.script' '$script' cannot be inspected.", failure)
    }
  if (!regularFile) {
    invalidManifestSchema("Platform pack '$slug': 'app_run.script' '$script' is not an existing regular file.")
  }
  val realPackRoot =
    try {
      packRoot.toRealPath()
    } catch (failure: IOException) {
      invalidManifestSchemaFromPath("Platform pack '$slug': 'app_run.script' pack root cannot be resolved.", failure)
    } catch (failure: SecurityException) {
      invalidManifestSchemaFromPath("Platform pack '$slug': 'app_run.script' pack root cannot be resolved.", failure)
    }
  val realScript =
    try {
      resolved.toRealPath()
    } catch (failure: IOException) {
      invalidManifestSchemaFromPath("Platform pack '$slug': 'app_run.script' '$script' cannot be resolved.", failure)
    } catch (failure: SecurityException) {
      invalidManifestSchemaFromPath("Platform pack '$slug': 'app_run.script' '$script' cannot be resolved.", failure)
    }
  if (!realScript.startsWith(realPackRoot)) {
    invalidManifestSchema("Platform pack '$slug': 'app_run.script' resolves outside the pack.")
  }
  return script
}

private fun requireQaGuidanceFile(
  capability: Map<*, *>,
  slug: String,
  packRoot: Path,
): String {
  val guidanceFile =
    (capability["guidance_file"] as? String)?.takeIf { it.isNotBlank() && it.endsWith(".md") }
      ?: invalidManifestSchema("Platform pack '$slug': 'qa_capability.guidance_file' must be a pack-relative .md path.")
  val root = packRoot.toAbsolutePath().normalize()
  val resolved = root.resolve(guidanceFile).normalize()
  if (Path.of(guidanceFile).isAbsolute || !resolved.startsWith(root)) {
    invalidManifestSchema("Platform pack '$slug': 'qa_capability.guidance_file' must stay inside the pack.")
  }
  if (!Files.isRegularFile(resolved)) {
    invalidManifestSchema("Platform pack '$slug': 'qa_capability.guidance_file' '$guidanceFile' does not exist.")
  }
  return guidanceFile
}
