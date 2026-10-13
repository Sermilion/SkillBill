package skillbill.scaffold.policy.platformpack.model

import skillbill.model.FileLocation
import skillbill.scaffold.model.CodeReviewBaselineLayer

data class PlatformPackManifestRenderRequest(
  val platform: String,
  val displayName: String,
  val strongSignals: List<String>,
  val tieBreakers: List<String> = emptyList(),
  val declaredCodeReviewAreas: List<String> = emptyList(),
  val baselineContentPath: String,
  val declaredAreaFiles: Map<String, String> = emptyMap(),
  val areaMetadata: Map<String, String> = emptyMap(),
  val baselineLayers: List<CodeReviewBaselineLayer> = emptyList(),
  val notes: String? = null,
  val routingPathSignals: List<String> = defaultRoutingPathSignals(strongSignals),
)

data class PlatformPackManifestContentRenderRequest(
  val platform: String,
  val displayName: String,
  val routingSignals: List<String>,
  val tieBreakers: List<String>,
  val specialistAreas: List<String>,
  val specialistAreaMetadata: Map<String, String>,
  val baselineLayers: List<CodeReviewBaselineLayer>,
  val packRoot: FileLocation,
  val baselineSkillPath: FileLocation,
  val specialistSkillPaths: Map<String, FileLocation>,
  val routingPathSignals: List<String> = defaultRoutingPathSignals(routingSignals),
)

private fun defaultRoutingPathSignals(signals: List<String>): List<String> =
  signals.filter { signal ->
    signal.startsWith(".") || signal.startsWith("*/") || signal.contains('/') ||
      signal.matches(
        Regex("[A-Za-z0-9_.-]+\\.(kt|kts|java|go|swift|php|py|rs|ts|tsx|js|jsx|toml|yaml|yml|json|xml|gradle)"),
      )
  }.ifEmpty { signals.take(1) }
