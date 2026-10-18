package skillbill.scaffold.model

import skillbill.model.FileLocation
import skillbill.telemetry.model.CustomFieldMap

data class RoutingSignals(
  val strong: List<String>,
  val tieBreakers: List<String>,
  val path: List<String> = strong,
  val content: List<String> = emptyList(),
)

data class ReviewLaneCondition(
  val required: Boolean = false,
  val path: List<String> = emptyList(),
  val content: List<String> = emptyList(),
) {
  init {
    require(required || path.isNotEmpty() || content.isNotEmpty()) {
      "An optional review lane condition must declare path or content signals."
    }
  }
}

data class DeclaredFiles(
  val baseline: FileLocation?,
  val areas: Map<String, FileLocation>,
)

data class PointerSpec(
  val skillRelativeDir: String,
  val name: String,
  val target: String,
)

enum class CodeReviewCompositionScope(val wireValue: String) {
  SameReviewScope("same-review-scope"),
  ;

  companion object {
    fun fromWireValue(value: String): CodeReviewCompositionScope? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class CodeReviewCompositionMode(val wireValue: String) {
  KmpBaseline("kmp-baseline"),
  ;

  companion object {
    fun fromWireValue(value: String): CodeReviewCompositionMode? = entries.firstOrNull { it.wireValue == value }
  }
}

data class CodeReviewBaselineLayer(
  val platform: String,
  val skill: String,
  val scope: CodeReviewCompositionScope,
  val required: Boolean,
  val mode: CodeReviewCompositionMode,
)

data class CodeReviewComposition(
  val baselineLayers: List<CodeReviewBaselineLayer>,
)

data class GovernedAddonUsage(
  val skillRelativeDir: String,
  val addons: List<GovernedAddonSelection>,
)

data class FeatureAddonUsage(
  val consumer: String,
  val addons: List<GovernedAddonSelection>,
)

data class GovernedAddonSelection(
  val slug: String,
  val entrypoint: String,
  val companionPointers: List<String> = emptyList(),
  val activation: GovernedAddonActivation? = null,
  val specialistAreas: List<String> = emptyList(),
)

data class GovernedAddonActivation(
  val anyPath: List<String> = emptyList(),
  val anyContent: List<String> = emptyList(),
  val allContent: List<String> = emptyList(),
  val anyOfAllContent: List<List<String>> = emptyList(),
  val excludePath: List<String> = emptyList(),
  val excludeContent: List<String> = emptyList(),
) {
  init {
    require(
      anyPath.isNotEmpty() || anyContent.isNotEmpty() || allContent.isNotEmpty() || anyOfAllContent.isNotEmpty(),
    ) {
      "Add-on activation must declare any, all, or any_of_all signals."
    }
    val signals = anyPath + anyContent + allContent + anyOfAllContent.flatten() + excludePath + excludeContent
    require(signals.all(String::isNotBlank)) {
      "Add-on activation signals must not be blank."
    }
  }
}

data class ValidationGateDeclaration(
  val fullGateCommand: List<String>,
  val cacheBypassingFullGateCommand: List<String>,
  val collectAllFullGateCommand: List<String>,
  val cacheBypassingCollectAllFullGateCommand: List<String>,
  val findings: ValidationGateFindingsLocator,
  val buildCommand: List<String>? = null,
  val cacheBypassingBuildCommand: List<String>? = null,
  val suppressionMarkers: List<String> = emptyList(),
) {
  init {
    require(fullGateCommand.isNotEmpty() && fullGateCommand.all(String::isNotBlank)) {
      "validation_gate.full_gate_command must be a non-empty argv of non-blank strings."
    }
    require(
      cacheBypassingFullGateCommand.isNotEmpty() &&
        cacheBypassingFullGateCommand.all(String::isNotBlank),
    ) {
      "validation_gate.cache_bypassing_full_gate_command must be a non-empty argv of non-blank strings."
    }
    require(collectAllFullGateCommand.isNotEmpty() && collectAllFullGateCommand.all(String::isNotBlank)) {
      "validation_gate.collect_all_full_gate_command must be a non-empty argv of non-blank strings."
    }
    require(
      cacheBypassingCollectAllFullGateCommand.isNotEmpty() &&
        cacheBypassingCollectAllFullGateCommand.all(String::isNotBlank),
    ) {
      "validation_gate.cache_bypassing_collect_all_full_gate_command must be a non-empty argv of non-blank strings."
    }
    require(suppressionMarkers.all(String::isNotBlank)) {
      "validation_gate.suppression_markers entries must be non-blank when present."
    }
    if (buildCommand != null || cacheBypassingBuildCommand != null) {
      require(buildCommand != null && cacheBypassingBuildCommand != null) {
        "validation_gate.build_command and validation_gate.cache_bypassing_build_command must both be " +
          "present when either is declared."
      }
      require(buildCommand.isNotEmpty() && buildCommand.all(String::isNotBlank)) {
        "validation_gate.build_command must be a non-empty argv of non-blank strings when present."
      }
      require(cacheBypassingBuildCommand.isNotEmpty() && cacheBypassingBuildCommand.all(String::isNotBlank)) {
        "validation_gate.cache_bypassing_build_command must be a non-empty argv of non-blank strings when present."
      }
      require(buildCommand != collectAllFullGateCommand) {
        "validation_gate.build_command must not be byte-identical to validation_gate.collect_all_full_gate_command."
      }
      require(cacheBypassingBuildCommand != cacheBypassingCollectAllFullGateCommand) {
        "validation_gate.cache_bypassing_build_command must not be byte-identical to " +
          "validation_gate.cache_bypassing_collect_all_full_gate_command."
      }
    }
  }
}

enum class ValidationGateFindingsFormat(val wireValue: String) {
  JUNIT_XML("junit_xml"),
  ;

  companion object {
    fun fromWire(value: String): ValidationGateFindingsFormat? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class ValidationGateExecutedWorkFormat(val wireValue: String) {
  GRADLE_ACTIONABLE_SUMMARY("gradle_actionable_summary"),
  ;

  companion object {
    fun fromWire(value: String): ValidationGateExecutedWorkFormat? = entries.firstOrNull { it.wireValue == value }
  }
}

enum class ValidationGateCompilerDiagnosticsFormat(val wireValue: String) {
  GRADLE_KOTLIN_COMPILER_STDOUT("gradle_kotlin_compiler_stdout"),
  ;

  companion object {
    fun fromWire(value: String): ValidationGateCompilerDiagnosticsFormat? =
      entries.firstOrNull { it.wireValue == value }
  }
}

enum class QaPlatform(val wireValue: String) {
  ANDROID("android"),
  IOS("ios"),
  ;

  companion object {
    fun fromWire(value: String): QaPlatform? = entries.firstOrNull { it.wireValue == value }
  }
}

data class QaCapabilityDeclaration(
  val platforms: Set<QaPlatform>,
  val guidanceFile: String,
  val discoveryHints: List<String> = emptyList(),
) {
  init {
    require(platforms.isNotEmpty()) { "qa_capability.platforms must name at least one platform." }
    require(guidanceFile.isNotBlank()) { "qa_capability.guidance_file must be a non-blank pack-relative path." }
  }
}

data class ValidationGateExecutedWorkSignal(
  val format: ValidationGateExecutedWorkFormat,
)

data class ValidationGateCompilerDiagnosticsLocator(
  val format: ValidationGateCompilerDiagnosticsFormat,
)

data class ValidationGateFindingsLocator(
  val format: ValidationGateFindingsFormat,
  val artifactGlobs: List<String>,
  val compilerDiagnostics: ValidationGateCompilerDiagnosticsLocator,
  val executedWork: ValidationGateExecutedWorkSignal? = null,
) {
  init {
    require(artifactGlobs.isNotEmpty() && artifactGlobs.all(String::isNotBlank)) {
      "validation_gate.findings.artifact_globs must be a non-empty list of non-blank globs."
    }
  }
}

data class PlatformManifest(
  val slug: String,
  val packRoot: FileLocation,
  val contractVersion: String,
  val routingSignals: RoutingSignals,
  val declaredCodeReviewAreas: List<String>,
  val declaredFiles: DeclaredFiles,
  val areaMetadata: Map<String, String>,
  val laneConditions: Map<String, ReviewLaneCondition> = emptyMap(),
  val displayName: String? = null,
  val notes: String? = null,
  val declaredQualityCheckFile: FileLocation? = null,
  val validationGate: ValidationGateDeclaration? = null,
  val qaCapability: QaCapabilityDeclaration? = null,
  val codeReviewComposition: CodeReviewComposition? = null,
  val fallbackCapabilities: Set<String> = emptySet(),
  val pointers: List<PointerSpec> = emptyList(),
  val addonUsage: List<GovernedAddonUsage> = emptyList(),
  val featureAddonUsage: List<FeatureAddonUsage> = emptyList(),
  val customFields: CustomFieldMap = CustomFieldMap.EMPTY,
  val requiredRubricCompanions: Map<String, List<String>> = emptyMap(),
) {
  val routedSkillName: String? = declaredFiles.baseline?.let { "bill-$slug-code-review" }
}

data class BaselineReviewCatalog(
  val packs: List<BaselineReviewPackEntry>,
  val compositionEdges: List<BaselineReviewCompositionEdge>,
  val layerSuggestions: List<BaselineReviewLayerSuggestion> = emptyList(),
)

data class BaselineReviewPackEntry(
  val platform: String,
  val displayName: String,
  val strongRoutingSignals: List<String>,
  val skills: List<BaselineReviewSkillEntry>,
)

data class BaselineReviewSkillEntry(
  val name: String,
  val supportedModes: List<String>,
  val supportedScopes: List<String>,
)

data class BaselineReviewCompositionEdge(
  val sourcePlatform: String,
  val targetPlatform: String,
  val targetSkill: String,
)

data class BaselineReviewLayerSuggestion(
  val label: String,
  val triggerSignals: List<String>,
  val platform: String,
  val skill: String,
  val scope: String,
  val required: Boolean,
  val mode: String,
)

data class GovernedAddonFile(
  val packSlug: String,
  val addonPath: FileLocation,
) {
  val addonSlug: String = addonPath.fileName.removeSuffix(".md")
}

data class ScaffoldResult(
  val kind: String,
  val skillName: String,
  val skillPath: FileLocation,
  val createdFiles: List<FileLocation> = emptyList(),
  val manifestEdits: List<FileLocation> = emptyList(),
  val manifestPreviews: Map<FileLocation, String> = emptyMap(),
  val symlinks: List<FileLocation> = emptyList(),
  val installTargets: List<FileLocation> = emptyList(),
  val notes: List<String> = emptyList(),
)

data class SkillClassMatcher(
  val exact: String? = null,
  val pattern: Regex? = null,
  val excludeExact: List<String> = emptyList(),
)

data class SkillClassSection(
  val heading: String,
  val body: String,
)

data class SkillClassManifest(
  val classId: String,
  val classFile: FileLocation,
  val contractVersion: String,
  val matchers: List<SkillClassMatcher>,
  val pointers: List<String>,
  val sections: List<SkillClassSection>,
  val ceremonyLines: List<String>,
)
