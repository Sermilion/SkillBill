
package skillbill.infrastructure.skills.scaffold.platformpack.loader

import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import skillbill.infrastructure.skills.scaffold.platformpack.manifest.anchoredTopLevelFieldNames
import skillbill.infrastructure.skills.scaffold.platformpack.manifest.declaredSkillRelativeDirs
import skillbill.ports.repository.toFileLocation
import skillbill.scaffold.model.PlatformManifest
import skillbill.telemetry.model.CustomFieldMap
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

internal fun readManifest(
  manifestPath: Path,
  slug: String,
): Any? =
  try {
    Yaml().load<Any?>(Files.readString(manifestPath))
  } catch (error: CancellationException) {
    throw error
  } catch (error: IOException) {
    invalidManifestSchemaFromPath(
      "Platform pack '$slug': manifest '$manifestPath' is not valid YAML: ${error.message}",
      error,
    )
  } catch (error: YAMLException) {
    invalidManifestSchemaFromPath(
      "Platform pack '$slug': manifest '$manifestPath' is not valid YAML: ${error.message}",
      error,
    )
  }

internal fun buildPack(
  slug: String,
  packRoot: Path,
  manifestPath: Path,
  raw: Any?,
  enforceContractVersion: Boolean,
): PlatformManifest {
  val manifest = requireManifestMap(slug, manifestPath, raw)
  val typedManifest = validateAgainstCanonicalSchema(slug, manifest, enforceContractVersion)
  validatePlatformSlug(slug, requireStringField(manifest, slug, "platform"))
  return assemblePlatformManifest(slug, packRoot, manifestPath, manifest, typedManifest)
}

internal fun assemblePlatformManifest(
  slug: String,
  packRoot: Path,
  manifestPath: Path,
  manifest: Map<*, *>,
  typedManifest: Map<String, Any?>,
): PlatformManifest {
  val declaredAreas = parseDeclaredAreas(manifest, slug)
  val routingSignals =
    parseRoutingSignals(
      manifest,
      slug,
      requirePath = manifest["lane_conditions"] != null,
      fallbackOnly = manifest["fallback_capabilities"] != null,
    )
  val declaredFiles = parseDeclaredFiles(manifest, slug, packRoot, declaredAreas)
  val areaMetadata = parseAreaMetadata(manifest, slug, declaredAreas)
  val laneConditions = parseLaneConditions(manifest, slug, declaredAreas)
  val declaredQualityCheckFile = parseOptionalPath(manifest, slug, "declared_quality_check_file", packRoot)
  val pointers = parsePointers(manifest, slug)
  val requiredRubricCompanions = parseRequiredRubricCompanions(manifest, slug)
  return PlatformManifest(
    slug = slug,
    packRoot = packRoot.toFileLocation(),
    contractVersion = requireStringField(manifest, slug, "contract_version"),
    routingSignals = routingSignals,
    declaredCodeReviewAreas = declaredAreas,
    declaredFiles = declaredFiles,
    areaMetadata = areaMetadata,
    laneConditions = laneConditions,
    displayName = parseOptionalString(manifest, slug, "display_name"),
    notes = parseOptionalString(manifest, slug, "notes"),
    declaredQualityCheckFile = declaredQualityCheckFile?.toFileLocation(),
    validationGate = parseValidationGate(manifest, slug),
    qaCapability = parseQaCapability(manifest, slug, packRoot),
    codeReviewComposition = parseCodeReviewComposition(manifest, slug),
    fallbackCapabilities = parseFallbackCapabilities(manifest, slug),
    pointers = pointers,
    addonUsage =
      parseAddonUsage(
        manifest,
        AddonUsageManifestContext(
          slug = slug,
          packRoot = packRoot,
          pointers = pointers,
          declaredSkillDirs = declaredSkillRelativeDirs(packRoot, declaredFiles, declaredQualityCheckFile),
          declaredAreas = declaredAreas.toSet(),
          strictReviewRouting = laneConditions.isNotEmpty(),
        ),
      ),
    featureAddonUsage = parseFeatureAddonUsage(manifest, slug, packRoot, pointers),
    customFields = CustomFieldMap.from(validatedCustomFields(slug, manifestPath, typedManifest)),
    requiredRubricCompanions = requiredRubricCompanions,
  )
}

internal fun validatePlatformSlug(
  slug: String,
  declaredPlatform: String,
) {
  if (declaredPlatform != slug) {
    invalidManifestSchema(
      "Platform pack '$slug': manifest 'platform' field is '$declaredPlatform', " +
        "expected '$slug' to match the directory name.",
    )
  }
}

internal fun parseFallbackCapabilities(
  manifest: Map<*, *>,
  slug: String,
): Set<String> {
  val raw = manifest["fallback_capabilities"] ?: return emptySet()
  val values =
    raw as? List<*> ?: invalidManifestSchema(
      "Platform pack '$slug': 'fallback_capabilities' must be a list.",
    )
  return values.mapIndexed { index, value ->
    (value as? String)?.trim()?.takeIf(String::isNotEmpty) ?: invalidManifestSchema(
      "Platform pack '$slug': 'fallback_capabilities[$index]' must be a non-blank string.",
    )
  }.toSet()
}

internal const val CODE_REVIEW_FALLBACK_CAPABILITY = "code-review"

internal fun extractCustomFields(manifest: Map<String, Any?>): Map<String, Any?> =
  manifest.filterKeys { it !in anchoredTopLevelFieldNames() }

internal fun validatedCustomFields(
  slug: String,
  manifestPath: Path,
  manifest: Map<String, Any?>,
): Map<String, Any?> {
  val customFields = extractCustomFields(manifest)
  guardAgainstAnchoredFieldTypos(slug, manifestPath, customFields.keys, anchoredTopLevelFieldNames())
  return customFields
}

internal fun requireManifestMap(
  slug: String,
  manifestPath: Path,
  raw: Any?,
): Map<*, *> =
  raw as? Map<*, *>
    ?: invalidManifestSchema(
      "Platform pack '$slug': manifest '$manifestPath' must be a YAML mapping at the top level.",
    )

internal fun validateAgainstCanonicalSchema(
  slug: String,
  manifest: Map<*, *>,
  enforceContractVersion: Boolean,
): Map<String, Any?> {
  val typedManifest: Map<String, Any?> =
    manifest.entries.associate { (key, value) ->
      val stringKey =
        key as? String
          ?: run {
            val keyType = key?.let { it::class.simpleName } ?: "null"
            invalidManifestSchema(
              "Platform pack '$slug': manifest top-level keys must be strings, but found '$key' ($keyType).",
            )
          }
      stringKey to value
    }
  canonicalSchemaValidator.validate(typedManifest, slug, enforceContractVersion)
  return typedManifest
}
