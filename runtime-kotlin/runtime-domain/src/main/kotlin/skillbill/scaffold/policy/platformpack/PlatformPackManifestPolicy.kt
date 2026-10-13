package skillbill.scaffold.policy.platformpack

import skillbill.scaffold.model.CodeReviewBaselineLayer
import skillbill.scaffold.policy.platformpack.model.PlatformPackManifestContentRenderRequest
import skillbill.scaffold.policy.platformpack.model.PlatformPackManifestRenderRequest

const val PLATFORM_PACK_SHELL_CONTRACT_VERSION: String = "1.8"

fun renderPlatformPackManifest(request: PlatformPackManifestRenderRequest): String {
  val lines = mutableListOf<String>()
  lines += "platform: ${yamlScalar(request.platform)}"
  lines += "contract_version: ${yamlScalar(PLATFORM_PACK_SHELL_CONTRACT_VERSION)}"
  lines += "display_name: ${yamlScalar(request.displayName)}"
  lines += ""
  appendRoutingSignals(lines, request.strongSignals, request.tieBreakers, request.routingPathSignals)
  lines += ""
  appendDeclaredCodeReviewAreas(lines, request.declaredCodeReviewAreas)
  lines += ""
  appendDeclaredFiles(lines, request.baselineContentPath, request.declaredCodeReviewAreas, request.declaredAreaFiles)
  appendAreaMetadata(lines, request.declaredCodeReviewAreas, request.areaMetadata)
  appendLaneConditions(lines, request.declaredCodeReviewAreas)
  appendPointers(
    lines,
    request.baselineContentPath,
    request.declaredCodeReviewAreas,
    request.declaredAreaFiles,
  )
  appendBaselineLayers(lines, request.baselineLayers)
  if (request.notes != null) {
    lines += ""
    lines += "notes: ${yamlScalar(request.notes)}"
  }
  return lines.joinToString("\n") + "\n"
}

fun renderPlatformPackManifestContent(request: PlatformPackManifestContentRenderRequest): String =
  renderPlatformPackManifest(
    PlatformPackManifestRenderRequest(
      platform = request.platform,
      displayName = request.displayName,
      strongSignals = request.routingSignals,
      tieBreakers = request.tieBreakers,
      declaredCodeReviewAreas = request.specialistAreas,
      baselineContentPath =
        request.packRoot.relativize(request.baselineSkillPath.resolve("content.md"))
          .toString()
          .replace('\\', '/'),
      declaredAreaFiles =
        request.specialistSkillPaths.mapValues { (_, path) ->
          request.packRoot.relativize(path.resolve("content.md")).toString().replace('\\', '/')
        },
      areaMetadata = request.specialistAreaMetadata,
      baselineLayers = request.baselineLayers,
      routingPathSignals = request.routingPathSignals,
    ),
  )

private fun appendRoutingSignals(
  lines: MutableList<String>,
  strongSignals: List<String>,
  tieBreakers: List<String>,
  pathSignals: List<String>,
) {
  lines += "routing_signals:"
  lines += "  strong:"
  strongSignals.forEach { lines += "    - ${yamlScalar(it)}" }
  if (tieBreakers.isEmpty()) {
    lines += "  tie_breakers: []"
  } else {
    lines += "  tie_breakers:"
    tieBreakers.forEach { lines += "    - ${yamlScalar(it)}" }
  }
  if (pathSignals.isNotEmpty()) {
    lines += "  path: [${pathSignals.joinToString(", ", transform = ::yamlScalar)}]"
  }
}

private fun appendLaneConditions(
  lines: MutableList<String>,
  declaredAreas: List<String>,
) {
  if (declaredAreas.isEmpty()) {
    lines += "lane_conditions: {}"
    return
  }
  lines += "lane_conditions:"
  declaredAreas.forEach { area ->
    lines += "  $area: { required: true }"
  }
}

private fun appendDeclaredCodeReviewAreas(
  lines: MutableList<String>,
  declaredCodeReviewAreas: List<String>,
) {
  if (declaredCodeReviewAreas.isEmpty()) {
    lines += "declared_code_review_areas: []"
  } else {
    lines += "declared_code_review_areas:"
    declaredCodeReviewAreas.forEach { lines += "  - ${yamlScalar(it)}" }
  }
}

private fun appendDeclaredFiles(
  lines: MutableList<String>,
  baselineContentPath: String,
  declaredCodeReviewAreas: List<String>,
  declaredAreaFiles: Map<String, String>,
) {
  lines += "declared_files:"
  lines += "  baseline: ${yamlScalar(baselineContentPath)}"
  if (declaredAreaFiles.isEmpty()) {
    lines += "  areas: {}"
  } else {
    lines += "  areas:"
    declaredCodeReviewAreas.forEach { area ->
      declaredAreaFiles[area]?.let { lines += "    $area: ${yamlScalar(it)}" }
    }
  }
}

private fun appendAreaMetadata(
  lines: MutableList<String>,
  declaredCodeReviewAreas: List<String>,
  areaMetadata: Map<String, String>,
) {
  if (areaMetadata.isEmpty()) {
    lines += "area_metadata: {}"
  } else {
    lines += "area_metadata:"
    declaredCodeReviewAreas.forEach { area ->
      areaMetadata[area]?.let {
        lines += "  $area:"
        lines += "    focus: ${yamlScalar(it)}"
      }
    }
  }
}

private fun appendBaselineLayers(
  lines: MutableList<String>,
  baselineLayers: List<CodeReviewBaselineLayer>,
) {
  if (baselineLayers.isEmpty()) return
  lines += ""
  lines += "code_review_composition:"
  lines += "  baseline_layers:"
  baselineLayers.forEach { layer ->
    lines += "    - platform: ${yamlScalar(layer.platform)}"
    lines += "      skill: ${yamlScalar(layer.skill)}"
    lines += "      scope: ${yamlScalar(layer.scope.wireValue)}"
    lines += "      required: ${layer.required}"
    lines += "      mode: ${yamlScalar(layer.mode.wireValue)}"
  }
}
