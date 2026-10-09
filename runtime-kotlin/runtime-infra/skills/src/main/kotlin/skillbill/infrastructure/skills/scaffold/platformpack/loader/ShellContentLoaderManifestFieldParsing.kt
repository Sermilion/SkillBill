
package skillbill.infrastructure.skills.scaffold.platformpack.loader

import skillbill.infrastructure.skills.scaffold.rendering.defaultAreaFocus
import skillbill.ports.repository.toFileLocation
import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.model.CodeReviewComposition
import skillbill.scaffold.model.CodeReviewCompositionMode
import skillbill.scaffold.model.CodeReviewCompositionScope
import skillbill.scaffold.model.DeclaredFiles
import skillbill.scaffold.model.ReviewLaneCondition
import skillbill.scaffold.model.RoutingSignals
import skillbill.scaffold.policy.APPROVED_CODE_REVIEW_AREAS
import skillbill.scaffold.policy.UNIVERSAL_CODE_REVIEW_AREAS
import java.nio.file.Path

internal fun parseRoutingSignals(
  manifest: Map<*, *>,
  slug: String,
  requirePath: Boolean,
  fallbackOnly: Boolean,
): RoutingSignals {
  val routing = requireMappingField(manifest, slug, "routing_signals")
  val strongRaw =
    routing["strong"]
      ?: invalidManifestSchema("Platform pack '$slug': manifest field 'routing_signals.strong' is required.")
  if (requirePath && !fallbackOnly && routing["path"] == null) {
    invalidManifestSchema("Platform pack '$slug': manifest field 'routing_signals.path' is required.")
  }
  return RoutingSignals(
    strong = parseStringList(slug, strongRaw, "routing_signals.strong", required = true),
    tieBreakers = parseStringList(slug, routing["tie_breakers"], "routing_signals.tie_breakers", required = false),
    path =
      parseStringList(
        slug,
        routing["path"] ?: strongRaw.takeUnless { requirePath || fallbackOnly },
        "routing_signals.path",
        required = !fallbackOnly,
      ),
    content = parseStringList(slug, routing["content"], "routing_signals.content", required = false),
  )
}

internal fun parseLaneConditions(
  manifest: Map<*, *>,
  slug: String,
  declaredAreas: List<String>,
): Map<String, ReviewLaneCondition> {
  val raw = manifest["lane_conditions"] ?: return emptyMap()
  val mapping =
    raw as? Map<*, *>
      ?: invalidManifestSchema("Platform pack '$slug': 'lane_conditions' must be a mapping.")
  val parsed =
    mapping.map { (areaRaw, conditionRaw) ->
      val area =
        areaRaw as? String
          ?: invalidManifestSchema("Platform pack '$slug': lane condition areas must be strings.")
      if (area !in declaredAreas) {
        invalidManifestSchema("Platform pack '$slug': lane condition '$area' is not a declared area.")
      }
      val condition =
        conditionRaw as? Map<*, *>
          ?: invalidManifestSchema("Platform pack '$slug': lane condition '$area' must be a mapping.")
      area to
        ReviewLaneCondition(
          required = condition["required"] as? Boolean ?: false,
          path = parseStringList(slug, condition["path"], "lane_conditions.$area.path", required = false),
          content = parseStringList(slug, condition["content"], "lane_conditions.$area.content", required = false),
        )
    }.toMap()
  val missing = declaredAreas.toSet() - parsed.keys
  if (missing.isNotEmpty()) {
    invalidManifestSchema(
      "Platform pack '$slug': 'lane_conditions' is missing declared areas ${missing.sorted()}.",
    )
  }
  return parsed
}

internal fun parseDeclaredAreas(
  manifest: Map<*, *>,
  slug: String,
): List<String> {
  val rawAreas = requireField(manifest, slug, "declared_code_review_areas")
  if (rawAreas !is List<*>) {
    invalidManifestSchema("Platform pack '$slug': 'declared_code_review_areas' must be a list.")
  }
  return rawAreas.map { entry ->
    val area =
      entry as? String
        ?: invalidManifestSchema(
          "Platform pack '$slug': every entry in 'declared_code_review_areas' must be a string.",
        )
    if (area !in APPROVED_CODE_REVIEW_AREAS + UNIVERSAL_CODE_REVIEW_AREAS) {
      invalidManifestSchema(
        "Platform pack '$slug': declared area '$area' is not accepted; " +
          "must be one of ${(APPROVED_CODE_REVIEW_AREAS + UNIVERSAL_CODE_REVIEW_AREAS).sorted()}.",
      )
    }
    area
  }
}

internal fun parseDeclaredFiles(
  manifest: Map<*, *>,
  slug: String,
  packRoot: Path,
  declaredAreas: List<String>,
): DeclaredFiles {
  val rawFiles = (manifest["declared_files"] as? Map<*, *>) ?: emptyMap<Any?, Any?>()
  val baselinePath = parseDeclaredBaselinePath(rawFiles, slug, packRoot)
  val areaFiles = parseDeclaredAreaFileEntries(rawFiles, slug, packRoot, declaredAreas)
  if (baselinePath == null && areaFiles.isNotEmpty()) {
    invalidManifestSchema(
      "Platform pack '$slug': 'declared_files.areas' is set but 'declared_files.baseline' is missing.",
    )
  }
  return DeclaredFiles(
    baseline = baselinePath?.toFileLocation(),
    areas = areaFiles.mapValues { (_, entry) -> entry.toFileLocation() },
  )
}

internal fun parseAreaMetadata(
  manifest: Map<*, *>,
  slug: String,
  declaredAreas: List<String>,
): Map<String, String> {
  val rawMetadata = (manifest["area_metadata"] as? Map<*, *>) ?: emptyMap<Any?, Any?>()
  val areaMetadata = mutableMapOf<String, String>()
  val extraAreaMetadata = mutableSetOf<String>()
  for ((key, value) in rawMetadata) {
    val area =
      key as? String
        ?: invalidManifestSchema("Platform pack '$slug': area_metadata entries must be string -> mapping.")
    if (area !in declaredAreas) {
      extraAreaMetadata += area
      continue
    }
    val metadata =
      value as? Map<*, *>
        ?: invalidManifestSchema("Platform pack '$slug': area_metadata['$area'] must be a mapping.")
    val focus =
      metadata["focus"] as? String
        ?: invalidManifestSchema(
          "Platform pack '$slug': area_metadata['$area'].focus must be a non-empty string.",
        )
    areaMetadata[area] = focus
  }
  if (extraAreaMetadata.isNotEmpty()) {
    invalidManifestSchema(
      "Platform pack '$slug': area_metadata contains entries ${extraAreaMetadata.sorted()} " +
        "that are not listed in 'declared_code_review_areas'.",
    )
  }
  declaredAreas.forEach { declaredArea -> areaMetadata.putIfAbsent(declaredArea, defaultAreaFocus(declaredArea)) }
  return areaMetadata
}

internal fun parseRequiredRubricCompanions(
  manifest: Map<*, *>,
  slug: String,
): Map<String, List<String>> {
  val raw = manifest["required_rubric_companions"] ?: return emptyMap()
  val companions =
    raw as? Map<*, *> ?: invalidManifestSchema(
      "Platform pack '$slug': 'required_rubric_companions' must be a mapping.",
    )
  return companions.entries.associate { (key, value) ->
    val area =
      key as? String ?: invalidManifestSchema(
        "Platform pack '$slug': required_rubric_companions keys must be strings.",
      )
    val files =
      value as? List<*> ?: invalidManifestSchema(
        "Platform pack '$slug': required_rubric_companions['$area'] must be a list.",
      )
    area to
      files.mapIndexed { index, file ->
        (file as? String)?.takeIf(String::isNotBlank) ?: invalidManifestSchema(
          "Platform pack '$slug': required_rubric_companions['$area'][$index] must be a non-blank string.",
        )
      }
  }
}

internal fun parseCodeReviewComposition(
  manifest: Map<*, *>,
  slug: String,
): CodeReviewComposition? {
  val raw = manifest["code_review_composition"] ?: return null
  val composition =
    raw as? Map<*, *>
      ?: invalidManifestSchema(
        "Platform pack '$slug': 'code_review_composition' must be a mapping when provided.",
      )
  val layersRaw =
    composition["baseline_layers"]
      ?: invalidManifestSchema(
        "Platform pack '$slug': 'code_review_composition.baseline_layers' is required.",
      )
  val layers =
    layersRaw as? List<*>
      ?: invalidManifestSchema(
        "Platform pack '$slug': 'code_review_composition.baseline_layers' must be a list.",
      )
  return CodeReviewComposition(
    baselineLayers = layers.mapIndexed { index, entry -> parseCodeReviewBaselineLayer(slug, index, entry) },
  )
}

internal fun parseCodeReviewBaselineLayer(
  slug: String,
  index: Int,
  raw: Any?,
): CodeReviewBaselineLayer {
  val layer =
    raw as? Map<*, *>
      ?: invalidManifestSchema(
        "Platform pack '$slug': 'code_review_composition.baseline_layers[$index]' must be a mapping.",
      )
  val fieldPrefix = "code_review_composition.baseline_layers[$index]"
  val scopeValue = requireStringInMap(slug, layer, "$fieldPrefix.scope", "scope")
  val modeValue = requireStringInMap(slug, layer, "$fieldPrefix.mode", "mode")
  val required =
    layer["required"] as? Boolean
      ?: invalidManifestSchema("Platform pack '$slug': '$fieldPrefix.required' must be an explicit boolean.")

  return CodeReviewBaselineLayer(
    platform = requireStringInMap(slug, layer, "$fieldPrefix.platform", "platform"),
    skill = requireStringInMap(slug, layer, "$fieldPrefix.skill", "skill"),
    scope =
      CodeReviewCompositionScope.fromWireValue(scopeValue)
        ?: invalidManifestSchema(
          "Platform pack '$slug': '$fieldPrefix.scope' has unsupported value '$scopeValue'.",
        ),
    required = required,
    mode =
      CodeReviewCompositionMode.fromWireValue(modeValue)
        ?: invalidManifestSchema(
          "Platform pack '$slug': '$fieldPrefix.mode' has unsupported value '$modeValue'.",
        ),
  )
}

internal fun requireStringInMap(
  slug: String,
  map: Map<*, *>,
  fieldLabel: String,
  key: String,
): String {
  val value =
    map[key] as? String
      ?: invalidManifestSchema("Platform pack '$slug': '$fieldLabel' must be a non-empty string.")
  if (value.isBlank()) {
    invalidManifestSchema("Platform pack '$slug': '$fieldLabel' must be a non-empty string.")
  }
  return value
}

internal fun parseOptionalString(
  manifest: Map<*, *>,
  slug: String,
  key: String,
): String? =
  manifest[key]?.let {
    it as? String ?: invalidManifestSchema("Platform pack '$slug': '$key' must be a string when provided.")
  }
