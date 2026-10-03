package skillbill.architecture

import kotlin.reflect.KClass

object PrincipleEnforcementInventory {
  const val RUNTIME_APPLICATION_MAIN: String = "runtime-kotlin/runtime-application/src/main/kotlin"
  const val RUNTIME_CLI_MAIN: String = "runtime-kotlin/runtime-cli/src/main/kotlin"
  const val RUNTIME_ENGINE_MAIN: String = "runtime-kotlin/runtime-engine/src/main/kotlin"
  const val APPLICATION_PACKAGE_PREFIX: String = "skillbill.application."
  const val CLI_PACKAGE_PREFIX: String = "skillbill.cli."
  const val SPILLOVER_FILE_NAME_BASELINE: String = "spillover-file-name-baseline.txt"
  const val CUSTOM_THROWABLE_BASELINE: String = "custom-throwable-baseline.txt"
  const val RUNTIME_COMPONENT_SOURCE: String =
    "runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di/core/RuntimeComponent.kt"

  data class ModuleArchitectureScanCase(
    val moduleName: String,
    val mainScanRoot: String,
    val moduleSourceRoot: String,
    val packagePrefix: String,
    val packageCycleBaseline: String,
    val packageCycleGranularity: ArchitectureScanSupport.PackageCycleGranularity,
    val ambientClockBaseline: String,
    val ambientEnvironmentBaseline: String,
    val injectDefaultsBaseline: String,
  )

  val moduleArchitectureScanCases: List<ModuleArchitectureScanCase> =
    RuntimeModuleCatalog.declaredGradleModules
      .filter { moduleName -> moduleName != "runtime-infra" }
      .map(::moduleArchitectureScanCase)

  private fun moduleArchitectureScanCase(moduleName: String): ModuleArchitectureScanCase {
    val directoryPath = RuntimeModuleCatalog.gradleModuleIdToDirectoryPath(moduleName)
    val baselineStem = RuntimeModuleCatalog.gradleModuleIdToBaselineStem(moduleName)
    return ModuleArchitectureScanCase(
      moduleName = moduleName,
      mainScanRoot = "runtime-kotlin/$directoryPath/src/main/kotlin",
      moduleSourceRoot = "runtime-kotlin/$directoryPath/src",
      packagePrefix = packagePrefixForModule(moduleName),
      packageCycleBaseline = packageCycleBaselineForModule(moduleName, baselineStem),
      packageCycleGranularity =
        if (moduleName == "runtime-domain" || moduleName == "runtime-contracts" || moduleName == "runtime-cli") {
          ArchitectureScanSupport.PackageCycleGranularity.EXACT_PACKAGE_SCC
        } else {
          ArchitectureScanSupport.PackageCycleGranularity.FIRST_SEGMENT_MUTUAL_PAIR
        },
      ambientClockBaseline = ambientClockBaselineForModule(moduleName, baselineStem),
      ambientEnvironmentBaseline = ambientEnvironmentBaselineForModule(moduleName, baselineStem),
      injectDefaultsBaseline = injectDefaultsBaselineForModule(moduleName, baselineStem),
    )
  }

  private fun packagePrefixForModule(moduleName: String): String =
    when (moduleName) {
      "runtime-application" -> APPLICATION_PACKAGE_PREFIX
      "runtime-engine" -> "skillbill.engine."
      "runtime-cli" -> CLI_PACKAGE_PREFIX
      "runtime-ports" -> "skillbill.ports."
      "runtime-mcp" -> "skillbill.mcp."
      "runtime-core" -> "skillbill.di."
      else -> RuntimeModuleCatalog.moduleMainPackageRoots[moduleName]?.let { "$it." } ?: "skillbill."
    }

  private fun packageCycleBaselineForModule(
    moduleName: String,
    baselineStem: String,
  ): String =
    when (moduleName) {
      "runtime-application" -> "application-package-cycle-baseline.txt"
      "runtime-cli" -> "runtime-cli-package-cycle-baseline.txt"
      else -> "$baselineStem-package-cycle-baseline.txt"
    }

  private fun ambientClockBaselineForModule(
    moduleName: String,
    baselineStem: String,
  ): String =
    when (moduleName) {
      "runtime-application" -> "runtime-application-ambient-clock-baseline.txt"
      "runtime-cli" -> "runtime-cli-ambient-clock-baseline.txt"
      else -> "$baselineStem-ambient-clock-baseline.txt"
    }

  private fun ambientEnvironmentBaselineForModule(
    moduleName: String,
    baselineStem: String,
  ): String =
    when (moduleName) {
      "runtime-cli" -> "runtime-cli-ambient-environment-baseline.txt"
      else -> "$baselineStem-ambient-environment-baseline.txt"
    }

  private fun injectDefaultsBaselineForModule(
    moduleName: String,
    baselineStem: String,
  ): String =
    when (moduleName) {
      "runtime-application" -> "inject-constructor-defaults-baseline.txt"
      "runtime-cli" -> "runtime-cli-inject-constructor-defaults-baseline.txt"
      else -> "$baselineStem-inject-constructor-defaults-baseline.txt"
    }

  val cliSharedLeafAreas: Set<String> = setOf("kernel", "model")

  const val CLI_COMPOSITION_ROOT_AREA: String = "core"

  val spilloverFileNameExemptions: Set<String> = emptySet()

  data class SuppressionAllowListRow(
    val relativePath: String,
    val symbol: String,
    val rule: String,
    val why: String,
  )

  val suppressionAllowList: List<SuppressionAllowListRow> =
    listOf(
      SuppressionAllowListRow(
        "runtime-infra/skills/src/main/kotlin/skillbill/infrastructure/skills/externaladdon/" +
          "FileSystemExternalAddonOverlayApply.kt",
        "asMutableMap",
        "UNCHECKED_CAST",
        "SnakeYAML returns an erased mutable map; ClassCastException guard keeps string-key overlay writes honest",
      ),
      SuppressionAllowListRow(
        "runtime-infra/skills/src/main/kotlin/skillbill/infrastructure/skills/externaladdon/" +
          "FileSystemExternalAddonOverlayApply.kt",
        "asMutableList",
        "UNCHECKED_CAST",
        "SnakeYAML returns an erased mutable list; ClassCastException guard keeps manifest list overlay writes honest",
      ),
      SuppressionAllowListRow(
        "runtime-application/src/testFixtures/kotlin/skillbill/application/review/snapshot/ReviewRecordingHarness.kt",
        "recordingDatabase",
        "UNCHECKED_CAST",
        "Dynamic ReviewRepository proxy passes typed args through erased invoke; casts mirror the repository contract",
      ),
      SuppressionAllowListRow(
        "runtime-core/src/test/kotlin/skillbill/di/workflow/ApplicationPersistencePortTestSupport.kt",
        "noopPort",
        "UNCHECKED_CAST",
        "Dynamic port proxy returns typed facade from erased invoke",
      ),
      SuppressionAllowListRow(
        "runtime-engine/src/test/kotlin/skillbill/engine/featuretask/runner/FeatureTaskRuntimeRunnerTestSupport.kt",
        "noopPort",
        "UNCHECKED_CAST",
        "Dynamic port proxy returns typed facade from erased invoke",
      ),
      SuppressionAllowListRow(
        "runtime-engine/src/test/kotlin/skillbill/engine/featuretask/runner/FeatureTaskRuntimeRunnerTestSupport.kt",
        "recordHarnessFindingVerdicts",
        "UNCHECKED_CAST",
        "Dynamic ReviewRepository proxy passes typed verdict list through erased invoke",
      ),
      SuppressionAllowListRow(
        "runtime-application/src/test/kotlin/skillbill/application/ParallelCodeReviewRunnerTest.kt",
        "RecordingReviewDatabase",
        "UNCHECKED_CAST",
        "Dynamic ReviewRepository proxy passes typed args through erased invoke",
      ),
    )

  val suppressionAllowListKeys: Set<Triple<String, String, String>> =
    suppressionAllowList.map { row -> Triple(row.relativePath, row.symbol, row.rule) }.toSet()

  val ambientEnvironmentExemptions: Set<String> =
    setOf(
      "runtime-kotlin/runtime-mcp/src/main/kotlin/skillbill/mcp/core/Main.kt",
      "runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di/core/RuntimeBootstrapBindings.kt",
    )

  data class EnforcedRule(
    val rule: String,
    val test: KClass<*>,
  )

  val enforceableRules: List<EnforcedRule> =
    listOf(
      EnforcedRule(
        "Wire vocabulary and contract-key declarations are unique, dynamically indexed, and referenced " +
          "without local token collections or literal payload-key accesses.",
        WireVocabularyArchitectureTest::class,
      ),
      EnforcedRule(
        "Strategy capability boundary: consumer authority stays closed through transitive helpers and " +
          "extensions; non-review consumers cannot acquire review mutation; coupled primitive writes " +
          "belong to the transition owner; unresolved governed edges fail.",
        StrategyCapabilityBoundaryArchitectureTest::class,
      ),
      EnforcedRule(
        "Package clustering: loose files in a subpackaged area do not belong to a sibling area cluster.",
        PackageClusteringArchitectureTest::class,
      ),
      EnforcedRule(
        "Production package siblings: non-model packages stay at or below 12 files and model packages at or " +
          "below 20 files, except for the named remainder inventory.",
        PackageSiblingCountArchitectureTest::class,
      ),
      EnforcedRule(
        "Production line ceiling: no production Kotlin file exceeds $PRODUCTION_LINE_CEILING lines without an " +
          "explicit exemption.",
        ProductionFileLineCeilingArchitectureTest::class,
      ),
      EnforcedRule(
        "Production logical-type line ceiling: extension files are attributed to receiver types and the " +
          "combined total is enforced.",
        ProductionLogicalTypeLineCeilingArchitectureTest::class,
      ),
      EnforcedRule(
        "Package acyclicity: mutual imports among areas under each module package prefix stay within that " +
          "module baseline across all declared runtime Gradle modules.",
        ApplicationPackageAcyclicityArchitectureTest::class,
      ),
      EnforcedRule(
        "Ambient clock ban: Instant.now, LocalDateTime.now, LocalDate.now, OffsetDateTime.now, " +
          "ZonedDateTime.now, Clock.systemUTC, and JvmSystemClock.instant require a baseline row in every " +
          "module main source root.",
        RuntimeApplicationAmbientClockArchitectureTest::class,
      ),
      EnforcedRule(
        "No @Inject constructor defaults: @Inject constructors carry no default arguments in any module main " +
          "source root, and runtime-application @Inject constructors expose no non-private properties.",
        InjectConstructorDefaultsArchitectureTest::class,
      ),
      EnforcedRule(
        "Failure wire codes: in-scope FailureWireCode hierarchies map cases to codes totally and injectively.",
        FailureCodeTotalityArchitectureTest::class,
      ),
      EnforcedRule(
        "Typed parse boundaries: named untrusted-input decode sites do not report malformation via error, " +
          "require, or bare throw.",
        TypedParseBoundaryArchitectureTest::class,
      ),
      EnforcedRule(
        "Inline FQN ban: production and test Kotlin use no inline fully-qualified references outside the " +
          "keep-list.",
        InlineFqnArchitectureTest::class,
      ),
      EnforcedRule(
        "Convention ownership: module build files do not re-apply Test or toolchain settings owned by " +
          "configureKotlinJvm.",
        ConventionReapplicationArchitectureTest::class,
      ),
      EnforcedRule(
        "Ambient environment ban: System.getenv, System.getProperty, and empty-string Path.of or Paths.get " +
          "require a baseline row in every module main source root.",
        AmbientEnvironmentArchitectureTest::class,
      ),
      EnforcedRule(
        "Command-area isolation: every runtime-cli command area's transitive skillbill.cli import closure " +
          "contains only the shared kernel and model leaves, never a sibling area or the composition root.",
        RuntimeCliAreaIsolationArchitectureTest::class,
      ),
      EnforcedRule(
        "Spillover-name ban: no source file in any runtime module, and no main-source file, type, or member " +
          "declaration, carries the spillover signature (Extras, Continued, Helpers, Support, Misc, Fns, " +
          "letter-plus-digit, or bare trailing-digit siblings) outside a named exemption; bare Support, " +
          "Helpers, Misc, and Extras apply to main sources only.",
        RuntimeSpilloverFileNameArchitectureTest::class,
      ),
      EnforcedRule(
        "Gradle module edges: every module api(project(...)) and implementation(project(...)) set matches " +
          "RuntimeModuleCatalog.moduleEdgeExpectations.",
        RuntimeCoreCompositionOnlyTest::class,
      ),
      EnforcedRule(
        "Port null-object absence: no runtime module main source declares an Unavailable, Noop, Empty, or " +
          "Unconfigured substitute; a reached absence is a nullable port resolved at the call site and the " +
          "test-only substitutes live in testFixtures.",
        PortNullObjectAbsenceArchitectureTest::class,
      ),
      EnforcedRule(
        "Inward-layer import rules: runtime-ports imports no adapter machinery and runtime-domain imports no " +
          "serialization or IO library, both asserted as an empty violation list without a baseline.",
        RuntimeContractModuleImportRulesTest::class,
      ),
      EnforcedRule(
        "Ports declaration guard: runtime-ports main source declares no top-level objects, no non-DTO " +
          "top-level classes, no (this as casts, and no interface default bodies that error or throw.",
        PortsDeclarationArchitectureTest::class,
      ),
      EnforcedRule(
        "Composition-only construction: no main-source site outside skillbill.di constructs a concrete class " +
          "censused from @Provides parameter types and explicit Provides constructions; import aliases count, " +
          "comments and string literals are ignored, and unrelated same-named functions are skipped.",
        RuntimeCompositionGuardArchitectureTest::class,
      ),
      EnforcedRule(
        "Runtime-core tests declare packages under skillbill.di and avoid retired absent and runtime packages.",
        RuntimeCompositionGuardArchitectureTest::class,
      ),
      EnforcedRule(
        "RuntimeComponent composition surface: abstract service properties are pinned separately from " +
          "@Provides generated wiring; any other public function on RuntimeComponent or a Runtime*Provides " +
          "mixin fails even when the abstract property set is unchanged.",
        RuntimeComponentInboundApiArchitectureTest::class,
      ),
      EnforcedRule(
        "Comment and KDoc policy: authored Kotlin under inlineFqnScanRoots contains no // line comments, no " +
          "non-KDoc block comments, and no KDoc except on interfaces and their members.",
        CommentAndInterfaceKdocArchitectureTest::class,
      ),
      EnforcedRule(
        "Compiler suppression allow-list: every authored @Suppress matches a suppressionAllowList row and " +
          "complexity-rule suppressions are never permitted.",
        SuppressionBanArchitectureTest::class,
      ),
    )

  val parseBoundarySites: List<ArchitectureScanSupport.ParseBoundarySite> =
    listOf(
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/lifecycle/execution/" +
            "FeatureTaskRuntimeExecutionPlanCodec.kt",
        functionNames = setOf("decode"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/lifecycle/execution/" +
            "FeatureTaskRuntimeExecutionPlanDecode.kt",
        functionNames =
          setOf(
            "decodeExecutionPlan",
            "decodePolicies",
            "planSlot",
            "planRevision",
            "planString",
            "planObject",
            "planObjects",
            "planStrings",
            "invalidPlanValue",
          ),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/featuretask/lifecycle/execution/" +
            "FeatureTaskRuntimeExecutionPlanTraversalCodec.kt",
        functionNames = setOf("decodeExecutionPlanTraversal"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-infra/contracts/src/main/kotlin/skillbill/infrastructure/contracts/" +
            "workflow/featuretask/FeatureTaskRuntimeExecutionPlanSchemaValidator.kt",
        functionNames = setOf("read", "write", "validate", "validateInstance", "requireBoundedBytes"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-infra/contracts/src/main/kotlin/skillbill/infrastructure/contracts/" +
            "workflow/featuretask/FeatureTaskRuntimeExecutionPlanCoherence.kt",
        functionNames = setOf("validateExecutionPlanCoherence", "validateExecutionPlanTraversal", "incoherentPlan"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "${RuntimeModuleCatalog.runtimeKotlinModuleDirectory("runtime-infra:sqlite")}/src/main/kotlin/" +
            "skillbill/infrastructure/sqlite/workflow/goalrunner/runner/GoalRunnerControlStoreDecodeState.kt",
        functionNames = setOf("decodeControlState"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "${RuntimeModuleCatalog.runtimeKotlinModuleDirectory("runtime-infra:sqlite")}/src/main/kotlin/" +
            "skillbill/infrastructure/sqlite/workflow/goalrunner/runner/GoalRunnerControlStoreDecodePolicies.kt",
        functionNames =
          setOf(
            "decodeReviewPolicy",
            "decodeAcceptances",
            "decodeAcceptanceEntry",
            "decodeReviewPolicyAddonEntry",
            "requireReviewPolicyAddonField",
            "requireAcceptanceInt",
            "requireAcceptanceString",
            "parseAcceptanceList",
          ),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "${RuntimeModuleCatalog.runtimeKotlinModuleDirectory("runtime-infra:sqlite")}/src/main/kotlin/" +
            "skillbill/infrastructure/sqlite/workflow/goalrunner/runner/GoalRunnerControlStoreDecodeValues.kt",
        functionNames =
          setOf(
            "decodeExecutionLease",
            "legacyPausedAt",
            "booleanOrDefault",
            "nullableString",
            "requiredString",
            "toPositiveLong",
            "toPositiveIntOrNull",
            "nonNegativeLongOrDefault",
            "exactPositiveSubtaskIdOrNull",
          ),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-application/src/main/kotlin/skillbill/application/workflow/service/" +
            "LegacyGoalRunnerControlMigration.kt",
        functionNames =
          setOf(
            "reviewPolicyFromLegacyArtifacts",
            "outOfBandAcceptancesFromLegacyArtifacts",
            "decodeGoalAgentAddonSelection",
            "decodeGoalAgentAddonSelectionEntry",
            "requiredAddonField",
          ),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "${RuntimeModuleCatalog.runtimeKotlinModuleDirectory("runtime-infra:sqlite")}/src/main/kotlin/" +
            "skillbill/infrastructure/sqlite/workflow/goalrunner/runner/LegacyGoalRunnerControlLedgerMigration.kt",
        functionNames =
          setOf(
            "reviewPolicyFromLegacyArtifacts",
            "outOfBandAcceptancesFromLegacyArtifacts",
            "decodeLegacyAgentAddonSelection",
            "decodeLegacyAgentAddonSelectionEntry",
            "requiredLegacyAddonField",
          ),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/phase/" +
            "FeatureTaskRuntimePhaseOutputValidationModels.kt",
        functionNames =
          setOf(
            "fromWire",
            "fromArtifactMap",
            "requireRepairEvidenceExactFields",
            "requireRepairEvidenceLocation",
            "phaseOutputRepairEvidenceSchemaError",
          ),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/handoff/task/" +
            "FeatureTaskRuntimeHandoffSourceRef.kt",
        functionNames = setOf("fromWire"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/handoff/task/" +
            "FeatureTaskRuntimeHandoffProjectionValue.kt",
        functionNames = setOf("fromWire"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/handoff/task/" +
            "FeatureTaskRuntimeHandoffModels.kt",
        functionNames = setOf("fromWire"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/handoff/task/" +
            "FeatureTaskRuntimeRunInvariantPromptFields.kt",
        functionNames = setOf("fromWire"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/core/" +
            "FeatureTaskRuntimeRepositoryCheckpoint.kt",
        functionNames = setOf("fromWire"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "${RuntimeModuleCatalog.runtimeKotlinModuleDirectory("runtime-infra:skills")}/src/main/kotlin/" +
            "skillbill/infrastructure/skills/scaffold/platformpack/loader/ShellContentLoaderValidationGate.kt",
        functionNames =
          setOf(
            "parseValidationGate",
            "parseValidationGateFindings",
            "parseCompilerDiagnosticsLocator",
            "parseExecutedWorkSignal",
            "requireGateArgv",
            "optionalGateArgv",
            "parseSuppressionMarkers",
          ),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/review/model/ReviewStageState.kt",
        functionNames =
          setOf(
            "fromWire",
            "decodeList",
          ),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/review/context/model/packet/" +
            "ReviewRunLaneSegmentAccountingJson.kt",
        functionNames = setOf("decode", "decodeSegment", "encode"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/model/persistence/artifact/" +
            "DurableArtifactMapReader.kt",
        functionNames = setOf("durableArtifactMapReader"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/persistence/" +
            "FeatureTaskRuntimeGoalContinuationArtifact.kt",
        functionNames = setOf("fromArtifactMap"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/phase/" +
            "FeatureTaskRuntimePhaseLedgerPersistenceModels.kt",
        functionNames = setOf("fromArtifactMap", "fromWire", "fromWireValue"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/decomposition/" +
            "DecompositionManifestWireCodec.kt",
        functionNames =
          setOf(
            "decode",
            "toDecompositionManifest",
            "toDecompositionSubtask",
            "decompositionReader",
          ),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/core/" +
            "FeatureTaskRuntimeResolvedBranch.kt",
        functionNames = setOf("fromArtifactMap"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/handoff/task/" +
            "FeatureTaskRuntimeHandoffEnvelope.kt",
        functionNames = setOf("fromEnvelopeMap", "projectionFromWire", "fieldFromWire", "handoffReader"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/persistence/" +
            "FeatureTaskRuntimeImplementationAttemptModels.kt",
        functionNames = setOf("fromArtifactMap", "featureTaskRuntimeImplementationAttemptsFromWire"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/persistence/" +
            "FeatureTaskRuntimeGoalContinuationPersistenceModels.kt",
        functionNames = setOf("fromArtifactMap"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/core/" +
            "FeatureTaskRuntimeDecomposeTerminal.kt",
        functionNames = setOf("fromArtifactMap"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/phase/" +
            "FeatureTaskRuntimePhaseRecord.kt",
        functionNames = setOf("fromArtifactMap"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/model/" +
            "goalobservability/GoalObservabilityModels.kt",
        functionNames = setOf("fromWire"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/goalrunner/model/GoalRunnerAccountingModels.kt",
        functionNames = setOf("fromWire"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/model/" +
            "goalobservability/GoalObservabilityParsing.kt",
        functionNames =
          setOf(
            "goalObservabilityHistoryFromArtifacts",
            "goalObservabilityEventFromArtifact",
            "asGoalWorkflowArtifactMap",
          ),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/model/goalreview/" +
            "GoalSubtaskReviewFindingArtifacts.kt",
        functionNames = setOf("fromArtifactMap"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/taskruntime/model/persistence/" +
            "GoalSubtaskReviewArtifactDecoder.kt",
        functionNames = setOf("decodeWire", "decodeContinuationOnlyWire", "decodeContinuationDirect"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/model/goalreview/" +
            "GoalSubtaskReviewStateDecoding.kt",
        functionNames =
          setOf(
            "reviewStateReader",
            "toReviewStateMap",
            "requireOnlyReviewStateKeys",
          ),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/model/goalobservability/" +
            "GoalObservabilityParsing.kt",
        functionNames = setOf("goalObservabilityReader", "requireGoalObservabilityContractVersion"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/goalrunner/ledger/AttemptLedgerDecoding.kt",
        functionNames =
          setOf(
            "decodeDeclaredGoalProgressEvent",
            "requiredProgressEventKind",
            "optionalProgressOutcome",
          ),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/workflow/model/" +
            "WorkflowRecordMapping.kt",
        functionNames = setOf("decodeSteps", "decodeStep", "decodeObject", "parseJson"),
      ),
      ArchitectureScanSupport.ParseBoundarySite(
        relativePath =
          "runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/workflow/engine/" +
            "WorkflowEngineSnapshotCodec.kt",
        functionNames = setOf("snapshotViewFrom", "mergeStepUpdates"),
      ),
    )

  val inlineFqnPrefixes: List<String> =
    listOf(
      "java.",
      "javax.",
      "jakarta.",
      "kotlin.",
      "kotlinx.",
      "org.",
      "com.",
      "dev.",
      "skillbill.",
    )

  val inlineFqnScanRoots: List<String> =
    listOf(
      "runtime-kotlin",
      "intellij-plugin",
      "runtime-kotlin/build-logic",
    )

  val reviewOnlyRules: List<String> =
    listOf(
      "Naming taste beyond noun-family clustering — mechanical naming rules false-positive on " +
        "intentional domain vocabulary.",
      "Deeper noun-family relatedness inside a single area cluster — only cross-area loose-file " +
        "buckets are mechanically provable.",
      "Open harness and capability vocabulary keys — reviewed by hand instead of an enum gate.",
    )

  const val PRODUCTION_LINE_CEILING: Int = 1200

  val productionLineCeilingExemptions: Map<String, String> = emptyMap()

  val runtimeComponentInboundApi: List<String> =
    listOf(
      "agentRunService",
      "configResolutionService",
      "externalAddonOverlayService",
      "externalAgentAddonSourceConfigPort",
      "externalPlatformPackResolutionService",
      "featureSpecPathResolverPort",
      "featureTaskContinuationLookupService",
      "featureTaskPhaseSettlementService",
      "featureTaskRuntimeRunInvariantsSource",
      "featureTaskRuntimeRunner",
      "featureTaskRuntimeExecutionPlanResolver",
      "featureTaskRuntimeStatusService",
      "featureTaskRuntimeWorkerCoordinator",
      "goalOperatorDecisionService",
      "goalPlanningLogService",
      "goalPreflightService",
      "goalRunner",
      "goalRunnerStatusService",
      "ideStatusService",
      "installAgentService",
      "installMcpRegistrationPort",
      "installNativeAgentLinkPort",
      "installSelectionPersistencePort",
      "installService",
      "learningService",
      "lifecycleTelemetryService",
      "operationExecutor",
      "operationRegistry",
      "phaseRunEntry",
      "repoValidationGateway",
      "repositoryEnclosingRootPort",
      "resolvedEnvironmentContext",
      "reviewService",
      "reviewSnapshotPruneService",
      "runtimeDiagnostics",
      "scaffoldCatalogGateway",
      "scaffoldGateway",
      "skillRemove",
      "systemService",
      "skillBillUpdateService",
      "skillBillUninstallService",
      "updateCheckService",
      "telemetryLevelMutator",
      "telemetryService",
      "unaddressedFindingsLedgerService",
      "workListService",
      "workflowService",
    )

  val packageClusteringGenericSegments: Set<String> =
    setOf(
      "model",
      "validation",
      "runner",
      "planning",
      "findings",
      "engine",
      "decomposition",
      "goal",
      "taskruntime",
      "idestatus",
      "specsource",
      "platformpack",
      "scaffold",
      "context",
      "plan",
      "spec",
      "diagnostics",
      "evidence",
      "config",
      "di",
      "telemetry",
      "learning",
      "workflow",
      "work",
      "updatecheck",
      "agentrun",
      "review",
      "persistence",
      "session",
      "db",
      "verification",
      "phaseoutput",
      "process",
      "agentaddon",
    )

  val productionPackageSiblingCountSourceRoots: List<String> =
    RuntimeModuleCatalog.declaredGradleModules
      .filterNot { moduleName -> moduleName == "runtime-infra" }
      .map { moduleName ->
        "${RuntimeModuleCatalog.runtimeKotlinModuleDirectory(moduleName)}/src/main/kotlin"
      } + "intellij-plugin/src/main/kotlin"

  val packageSiblingCountRemainderInventory: Map<String, String> = emptyMap()

  val packageClusteringSourceRoots: List<String> = productionPackageSiblingCountSourceRoots

  val conventionOwnedTestPatterns: List<Pair<String, String>> =
    listOf(
      """if\s*\(\s*project\.hasProperty\(\s*"update-snapshots"\s*\)\s*\)""" to "update-snapshots Test systemProperty",
      """systemProperty\(\s*"update-snapshots"""" to "update-snapshots Test systemProperty",
      """useJUnitPlatform\s*\(\s*\)""" to "Test.useJUnitPlatform",
      """maxParallelForks\s*=""" to "Test.maxParallelForks",
      """maxHeapSize\s*=""" to "Test.maxHeapSize",
      """testLogging\s*\{""" to "Test.testLogging",
      """jvmToolchain\s*\(""" to "KotlinJvmProjectExtension.jvmToolchain",
      """languageVersion\.set\(JavaLanguageVersion""" to "JavaPluginExtension.toolchain.languageVersion",
    )
}
