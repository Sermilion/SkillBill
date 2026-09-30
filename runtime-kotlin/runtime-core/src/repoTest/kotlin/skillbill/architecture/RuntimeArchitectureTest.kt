package skillbill.architecture

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeArchitectureTest {
  @Test
  fun `touched domain contract foundation stays free of concrete adapters`() {
    assertNoBannedImports(
      files =
        sourceFiles().filter { file ->
          file.relativePath.startsWith(
            "${moduleMainKotlinRootRelative("runtime-domain")}/skillbill/workflow/",
          ) ||
            file.relativePath.startsWith(
              "${moduleMainKotlinRootRelative("runtime-domain")}/skillbill/install/model/",
            )
        },
      bannedImports =
        listOf(
          "com.github.ajalt.clikt",
          "java.io",
          "java.net.http",
          "java.sql",
          "java.nio.file.Files",
          "kotlin.io.path",
          "skillbill.cli",
          "skillbill.db",
          "skillbill.infrastructure",
          "skillbill.mcp",
        ),
    )
  }

  @Test
  fun `runtime contracts main source is free of networknt jackson and nio files`() {
    val contractsFiles =
      sourceFiles().filter { file ->
        file.relativePath.startsWith("runtime-kotlin/runtime-contracts/src/main/kotlin/")
      }
    assertTrue(
      contractsFiles.isNotEmpty(),
      "runtime-contracts main source must exist for the purity lock to be meaningful.",
    )
    assertNoBannedImports(
      files = contractsFiles,
      bannedImports = RuntimeArchitectureScanConstants.contractsForbiddenImports,
    )
    assertNoBannedSourceReferences(
      files = contractsFiles,
      bannedReferences = RuntimeArchitectureScanConstants.contractsForbiddenSourceReferences,
      description = "runtime-contracts infrastructure-coupling violation",
    )
  }

  @Test
  fun `runtime contracts purity scanner fires on synthetic fixtures`() {
    val fixtureSource =
      """
      import com.networknt.schema.JsonSchemaFactory
      import com.fasterxml.jackson.databind.ObjectMapper
      import java.nio.file.Files
      import org.yaml.snakeyaml.Yaml
      import java.io.InputStream

      object ContractsLeak {
        fun read() {
          Files.readString(somePath)
          javaClass.getResourceAsStream("/skillbill/contract.yaml")
        }
      }
      """.trimIndent()
    val fixture = syntheticSourceFile("test-fixture/ContractsLeak.kt", fixtureSource)
    assertEquals(
      listOf(
        "com.networknt.schema.JsonSchemaFactory",
        "com.fasterxml.jackson.databind.ObjectMapper",
        "java.nio.file.Files",
        "org.yaml.snakeyaml.Yaml",
        "java.io.InputStream",
      ),
      fixture.imports,
      "Production RuntimeArchitectureScanConstants.importPattern must parse the fixture's five " +
        "forbidden imports from source.",
    )
    assertFailsWith<AssertionError>(
      "assertNoBannedImports must THROW on the contracts fixture; otherwise the runtime-contracts " +
        "import purity lock is not actually exercised.",
    ) {
      assertNoBannedImports(
        files = listOf(fixture),
        bannedImports = RuntimeArchitectureScanConstants.contractsForbiddenImports,
      )
    }
    val sourceViolations =
      RuntimeArchitectureScanConstants.contractsForbiddenSourceReferences
        .filter { reference -> fixture.source.lines().any { line -> line.containsBannedReference(reference) } }
    assertEquals(
      RuntimeArchitectureScanConstants.contractsForbiddenSourceReferences,
      sourceViolations,
      "Contracts purity source scanner must report each banned reference (incl. the `Files.` call site and " +
        "the classpath `getResourceAsStream` loader).",
    )
  }

  @Test
  fun `runtime contracts purity scanner does not flag benign Files-like tokens`() {
    val cleanFixture =
      syntheticSourceFile(
        "test-fixture/ContractsClean.kt",
        """

        data class ProfileFiles(val names: List<String>)

        object ContractsClean {
          fun count(): Int {
            val profileFiles = listOf<String>()
            return profileFiles.size
          }
        }
        """.trimIndent(),
      )
    assertEquals(
      emptyList(),
      cleanFixture.imports.filter { importedName ->
        RuntimeArchitectureScanConstants.contractsForbiddenImports.any(importedName::startsWith)
      },
      "Clean fixture must declare no forbidden imports.",
    )
    val cleanSourceViolations =
      cleanFixture.source.lines().flatMap { line ->
        RuntimeArchitectureScanConstants.contractsForbiddenSourceReferences.filter { reference ->
          line.containsBannedReference(reference)
        }
      }
    assertEquals(
      emptyList(),
      cleanSourceViolations,
      "Source scanner must NOT flag benign `Files`-like identifiers (`profileFiles`, `ProfileFiles`) that " +
        "are not the banned `java.nio.file.Files` / `Files.` call site.",
    )
  }

  @Test
  fun `runtime domain workflow source must not import contract schema validators or contract mappers`() {
    val guardedDomainFiles =
      sourceFiles().filter { file ->
        file.relativePath.startsWith(
          "${moduleMainKotlinRootRelative("runtime-domain")}/skillbill/workflow/",
        ) ||
          file.relativePath.startsWith(
            "${moduleMainKotlinRootRelative("runtime-domain")}/skillbill/install/",
          )
      }
    val violations =
      guardedDomainFiles.flatMap { file ->
        file.imports
          .filter { importedName ->
            importedName.endsWith("SchemaValidator") ||
              importedName.endsWith("CoherenceValidator") ||
              (importedName.startsWith("skillbill.contracts.") && importedName.endsWith("Mapper"))
          }
          .map { importedName -> "${file.relativePath} imports banned $importedName" }
      }
    assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
  }

  @Test
  fun `decomposition manifest application projection declares final parse seam ownership`() {
    val projectionIo =
      Files.readString(
        sourcePath("skillbill/application/decomposition/DecompositionManifestFileWrites.kt"),
      )

    assertContains(projectionIo, "fun loadValidatedDecompositionManifest")
    assertContains(projectionIo, "fun encodeValidatedDecompositionManifestYaml")
    assertContains(projectionIo, "validator.validateYamlText")
    assertContains(projectionIo, "DecompositionManifestValidator")
    assertContains(projectionIo, "DecompositionManifestStore")
  }

  @Test
  fun `schema locators live in infrastructure packages, never in the shared contracts kernel`() {
    val locatorDeclaration = Regex("""^\s*(?:internal\s+)?object\s+(\w*SchemaPaths)\b""", RegexOption.MULTILINE)
    val misplaced =
      declaredMainSourceFiles()
        .flatMap { file ->
          locatorDeclaration.findAll(file.source).map { match -> file to match.groupValues[1] }
        }
        .filterNot { (file, _) -> file.packageName.startsWith("skillbill.infrastructure.") }
        .map { (file, locator) -> "${file.relativePath}:$locator" }
        .sorted()

    assertEquals(
      emptyList(),
      misplaced,
      "Schema locators belong to the infrastructure module that stages the canonical YAML resources " +
        "(skillbill.infrastructure.contracts.locator), not to runtime-contracts",
    )

    val contractsSourceRoot =
      runtimeArchitectureRoot.resolve("${RuntimeModuleCatalog.runtimeKotlinModuleDirectory("runtime-contracts")}/src")
    val kernelLocators =
      ArchitectureScanSupport.kotlinFilesUnder(contractsSourceRoot)
        .flatMap { path -> locatorDeclaration.findAll(Files.readString(path)).map { match -> match.groupValues[1] } }
    assertEquals(emptyList(), kernelLocators, "runtime-contracts/src must declare no *SchemaPaths object")

    val locatorPackageRoot =
      moduleMainKotlinRoot("runtime-infra:contracts").resolve("skillbill/infrastructure/contracts/locator")
    val expectedLocatorFamilies =
      mapOf(
        "DecompositionSchemaPaths.kt" to
          setOf("DecompositionManifestSchemaPaths", "DecompositionManifestBundleJournalSchemaPaths"),
        "FeatureTaskRuntimeSchemaPaths.kt" to
          setOf(
            "FeatureTaskExecutionIdentitySchemaPaths",
            "FeatureTaskRuntimeWorkerOwnershipSchemaPaths",
          ),
        "GoalSchemaPaths.kt" to
          setOf(
            "GoalPlanningPreparationSchemaPaths",
            "GoalProgressEventSchemaPaths",
            "GoalObservabilityEventSchemaPaths",
            "GoalSubtaskReviewStateSchemaPaths",
          ),
        "InstallSchemaPaths.kt" to
          setOf("InstallPlanSchemaPaths", "NativeAgentLinkInventorySchemaPaths", "AgentAddonSchemaPaths"),
        "OutputEvidenceSchemaPaths.kt" to
          setOf("ProducerOutputEvidenceSchemaPaths", "RejectedOutputDiagnosticSchemaPaths"),
        "ReviewContextSchemaPaths.kt" to setOf("ReviewContextSchemaPaths"),
        "WorkflowSchemaPaths.kt" to setOf("WorkflowStateSchemaPaths", "IdeStatusSchemaPaths"),
      )
    expectedLocatorFamilies.forEach { (fileName, expectedLocators) ->
      val path = locatorPackageRoot.resolve(fileName)
      assertTrue(Files.exists(path), "Missing schema locator family file: ${runtimeArchitectureRoot.relativize(path)}")
      val declared = locatorDeclaration.findAll(Files.readString(path)).map { match -> match.groupValues[1] }.toSet()
      assertTrue(declared.containsAll(expectedLocators), "$fileName must declare $expectedLocators, found $declared")
    }
  }

  @Test
  fun `telemetry ports and adapters are explicit package surfaces`() {
    val portFiles =
      listOf(
        sourcePath("skillbill/ports/telemetry/transport/TelemetrySettingsProvider.kt"),
        sourcePath("skillbill/ports/telemetry/transport/TelemetryConfigStore.kt"),
        sourcePath("skillbill/ports/telemetry/transport/TelemetryClient.kt"),
        sourcePath("skillbill/ports/telemetry/transport/TelemetryOutboxRepository.kt"),
      )
    portFiles.forEach { path ->
      assertTrue(Files.exists(path), "Missing telemetry port: ${runtimeArchitectureRoot.relativize(path)}")
    }
    val telemetryClientPort = Files.readString(sourcePath("skillbill/ports/telemetry/transport/TelemetryClient.kt"))
    assertContains(telemetryClientPort, "skillbill.telemetry.model.TelemetryProxyCapabilities")
    assertContains(telemetryClientPort, "skillbill.telemetry.model.TelemetryRemoteStatsResult")

    assertContains(
      Files.readString(sourcePath("skillbill/infrastructure/http/JdkHttpRemoteTransport.kt")),
      "java.net.http.HttpClient",
    )
    assertContains(
      Files.readString(sourcePath("skillbill/infrastructure/host/FileTelemetryConfigStore.kt")),
      "java.nio.file.Files",
    )
    assertContains(
      Files.readString(sourcePath("skillbill/infrastructure/http/TelemetryProxyContracts.kt")),
      "data class TelemetryProxyBatchEvent",
    )
    assertContains(
      Files.readString(sourcePath("skillbill/infrastructure/http/TelemetryProxyPayloadMappers.kt")),
      "TelemetryProxyBatchPayload",
    )
  }

  @Test
  fun `review and telemetry domain models do not own json payload contracts`() {
    val violations =
      sourceFiles()
        .filter { file ->
          file.relativePath.startsWith(
            "${moduleMainKotlinRootRelative("runtime-domain")}/skillbill/review/",
          ) ||
            file.relativePath.startsWith(
              "${moduleMainKotlinRootRelative("runtime-domain")}/skillbill/telemetry/",
            )
        }
        .filter { file ->
          "JsonPayloadContract" in file.source ||
            Regex("""fun\s+[A-Za-z0-9_.]+\s*\([^)]*\)\s*:\s*Map<String,\s*Any\?>""").containsMatchIn(file.source)
        }
        .map { file -> file.relativePath }

    assertTrue(
      violations.isEmpty(),
      "Review and telemetry domain packages must stay typed; JSON payload projection belongs at " +
        "application, port, or adapter seams.\n" +
        violations.joinToString(separator = "\n"),
    )
  }

  @Test
  fun `contract package stays dto only without upward runtime dependencies`() {
    assertNoBannedImports(
      files = sourceFiles().filter { it.packageName.startsWith("skillbill.contracts") },
      bannedImports =
        listOf(
          "skillbill.application",
          "skillbill.cli",
          "skillbill.db",
          "skillbill.infrastructure",
          "skillbill.learnings",
          "skillbill.mcp",
          "skillbill.ports",
          "skillbill.review",
          "skillbill.telemetry",
        ),
    )
  }

  @Test
  fun `telemetry sync orchestration avoids concrete db filesystem and http APIs`() {
    assertNoBannedImports(
      files =
        listOf(
          sourcePath("skillbill/application/telemetry/config/TelemetrySettingsFromStore.kt"),
          sourcePath("skillbill/application/telemetry/sync/TelemetrySyncRuntime.kt"),
          sourcePath("skillbill/application/telemetry/config/TelemetryConfigMutations.kt"),
          sourcePath("skillbill/application/telemetry/settings/DefaultTelemetrySettingsProvider.kt"),
        ).map(::sourceFile),
      bannedImports =
        listOf(
          "java.net.http",
          "java.sql",
          "java.nio.file.Files",
          "skillbill.db",
          "skillbill.infrastructure",
        ),
    )
  }

  @Test
  fun `runtime context does not depend on infrastructure defaults`() {
    assertNoBannedImports(
      files = listOf(sourceFile(sourcePath("skillbill/di/core/RuntimeContext.kt"))),
      bannedImports = listOf("skillbill.infrastructure"),
    )
  }

  @Test
  fun `install ports expose typed capability APIs`() {
    val installPortFiles =
      sourceFiles()
        .filter { sourceFile ->
          sourceFile.relativePath.startsWith(
            "runtime-kotlin/runtime-ports/src/main/kotlin/skillbill/ports/install/",
          )
        }
    assertTrue(installPortFiles.isNotEmpty(), "Install capability ports must exist.")

    val nonRequestResultSignatures =
      installPortFiles
        .filter { sourceFile -> sourceFile.relativePath.endsWith("Port.kt") }
        .flatMap { sourceFile ->
          installPortFunctionSignatures(sourceFile).mapNotNull { signature ->
            if (signature.hasSingleRequestParameter && signature.hasResultReturn) null else signature.render()
          }
        }
    assertTrue(
      nonRequestResultSignatures.isEmpty(),
      "Install capability port functions must accept exactly one *Request model and return a *Result model.\n" +
        nonRequestResultSignatures.joinToString(separator = "\n"),
    )
  }

  @Test
  fun `crash reconciliation liveness stays behind the injectable supervisor and out of the process runner`() {
    val reconciliationSources =
      sourceFiles().filter { file ->
        file.relativePath.endsWith("featuretask/lifecycle/core/FeatureTaskRuntimeCrashReconciler.kt") ||
          file.relativePath.endsWith("featuretask/lifecycle/core/FeatureTaskRuntimeWorkerCoordinator.kt") ||
          file.relativePath.endsWith("goalrunner/persist/WorkflowGoalRunnerOutcomeStore.kt")
      }
    assertTrue(reconciliationSources.isNotEmpty(), "crash-reconciliation source scan must be non-vacuous.")
    assertTrue(
      reconciliationSources.any { file -> "FeatureTaskRuntimeWorkerSupervisor" in file.source },
      "Crash reconciliation must reach liveness through the injectable FeatureTaskRuntimeWorkerSupervisor port.",
    )
    assertNoBannedSourceReferences(
      files = reconciliationSources,
      bannedReferences =
        listOf(
          "skillbill.infrastructure.launcher.process",
          "JvmAgentRunProcessRunner",
          "AgentRunCommandBuilder",
          "ProcessWaitLoop",
        ),
      description = "concrete agent-runner coupling in crash reconciliation",
    )

    val processRunner =
      sourceFiles().single { file ->
        file.relativePath.endsWith("launcher/process/launch/JvmAgentRunProcessRunner.kt")
      }
    val runnerCouplingToReconciliation =
      listOf(
        "CrashReconcil",
        "CrashLiveness",
        "FeatureTaskRuntimeWorkerSupervisor",
        "reconcileFeatureTaskRuntimeCrashedWorker",
      ).filter { reference -> reference in processRunner.source }
    assertEquals(
      emptyList(),
      runnerCouplingToReconciliation,
      "The agent process runner (ProcessWaitLoop) must stay decoupled from crash reconciliation and the " +
        "supervisor liveness port; agent-conditional branching belongs behind injectable strategies.",
    )
  }

  @Test
  fun `every main source package is declared under an owned subsystem`() {
    val ownershipPrefixes = RuntimeModuleCatalog.declaredSubsystemPackages.sortedByDescending(String::length)
    val unowned =
      declaredMainSourceFiles()
        .filter { file -> file.packageName.isNotBlank() }
        .filterNot { file -> file.packageName == "skillbill" }
        .filterNot { file ->
          ownershipPrefixes.any { prefix -> file.packageName == prefix || file.packageName.startsWith("$prefix.") }
        }
        .map { file -> "${file.packageName} in ${file.relativePath}" }
        .distinct()
        .sorted()
    assertEquals(
      emptyList(),
      unowned,
      "Every real main-source package must be owned by RuntimeModuleCatalog.declaredSubsystemPackages.",
    )
  }

  @Test
  fun `inner layer test sources do not import adapters or infrastructure packages`() {
    val forbiddenPrefixes =
      listOf(
        "skillbill.infrastructure.",
        "skillbill.cli.",
        "skillbill.mcp.",
      )
    val violations =
      innerLayerTestSourceFiles().flatMap { file ->
        file.imports
          .filter { importedName -> forbiddenPrefixes.any(importedName::startsWith) }
          .map { importedName -> "${file.relativePath} imports $importedName" }
      }
    assertEquals(
      emptyList(),
      violations.sorted(),
      "Inner-layer tests must use application/domain/port-facing seams instead of adapter packages.",
    )
  }
}
