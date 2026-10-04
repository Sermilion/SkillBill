package skillbill.architecture

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class RuntimeLayerBoundaryArchitectureTest {
  @Test
  fun `DatabaseRuntime is the only main-source jdbc sqlite connection site`() {
    assertEquals(
      emptyList(),
      jdbcSqliteConnectionSitesOutsideDatabaseRuntime(runtimeArchitectureSourceRoots),
      "Every database creation site must route through DatabaseRuntime, which applies the base " +
        "schema and migrations. A direct jdbc:sqlite connection can leave a schema-less file behind.",
    )
  }

  @Test
  fun `only the prune gateway may delete review-metrics snapshots`() {
    val deletionSites =
      runtimeArchitectureSourceRoots
        .flatMap { root -> kotlinFilesUnderWithArchitectureAsserts(root) }
        .filter { path -> path.fileName.toString() != "FileSystemReviewSnapshotGateway.kt" }
        .filter { path ->
          val text = Files.readString(path)
          "review-metrics" in text && Regex("""Files\.delete\w*\(|toFile\(\)\.delete\w*\(""").containsMatchIn(text)
        }
        .map { path -> runtimeArchitectureRoot.relativize(path).toString().replace('\\', '/') }
        .sorted()

    assertEquals(
      emptyList(),
      deletionSites,
      "Snapshots may only be deleted through the opt-in prune gateway, never automatically.",
    )
  }

  @Test
  fun `runtime cli check task depends on validate agent configs`() {
    val buildFile =
      Files.readString(
        runtimeArchitectureRoot.resolve("runtime-kotlin/runtime-cli/build.gradle.kts"),
      )
    assertContains(buildFile, "val validateAgentConfigs by tasks.registering(JavaExec::class)")
    val validateAgentConfigsBlock =
      Regex(
        """val validateAgentConfigs by tasks\.registering\(JavaExec::class\) \{(?<body>.*?)\}""",
        RegexOption.DOT_MATCHES_ALL,
      )
        .find(buildFile)
    assertTrue(validateAgentConfigsBlock != null, "validateAgentConfigs task configuration is missing")
    val validateAgentConfigsBody = validateAgentConfigsBlock.groups["body"]?.value.orEmpty()
    assertContains(validateAgentConfigsBody, "mainClass.set(application.mainClass)")
    assertContains(
      validateAgentConfigsBody,
      "args(\"validate-agent-configs\", \"--repo-root\", rootProject.projectDir.parentFile.absolutePath)",
    )
    val checkBlock =
      Regex("""tasks\.named\("check"\)\s*\{(?<body>.*?)\}""", RegexOption.DOT_MATCHES_ALL)
        .find(buildFile)
    assertTrue(checkBlock != null, "runtime-cli check task configuration is missing")
    assertContains(checkBlock.groups["body"]?.value.orEmpty(), "dependsOn(validateAgentConfigs)")
  }

  @Test
  fun `application layer stays independent of entrypoint frameworks`() {
    assertNoBannedImports(
      files = sourceFiles().filter { it.packageName.startsWith("skillbill.application") },
      bannedImports =
        listOf(
          "androidx.compose",
          "com.github.ajalt.clikt",
          "org.jetbrains.compose",
          "skillbill.cli",
          "skillbill.mcp",
        ),
    )
  }

  @Test
  fun `application services use persistence ports instead of sqlite infrastructure`() {
    val applicationFiles =
      sourceFiles()
        .filter { it.packageName.startsWith("skillbill.application") }
    val applicationPersistenceBannedImports =
      listOf(
        "java.sql",
        "skillbill.db",
        "skillbill.infrastructure",
        "skillbill.review.ReviewRuntime",
        "skillbill.review.TriageRuntime",
      )
    assertNoBannedImports(
      files = applicationFiles,
      bannedImports = applicationPersistenceBannedImports,
    )
  }

  @Test
  fun `runtime ports avoid concrete thread interrupt restoration`() {
    val portMainFiles =
      sourceFilesIn(
        runtimeArchitectureRoot.resolve("runtime-kotlin/runtime-ports/src/main/kotlin"),
      )
    assertNoBannedSourceReferences(
      files = portMainFiles,
      bannedReferences =
        listOf(
          "Thread.currentThread",
          ".interrupt()",
        ),
      description = "concrete thread interrupt restoration",
    )
  }

  @Test
  fun `runtime application does not select the JVM interrupt adapter`() {
    val applicationMainFiles =
      sourceFilesIn(
        runtimeArchitectureRoot.resolve("runtime-kotlin/runtime-application/src/main/kotlin"),
      )
    assertNoBannedImports(
      files = applicationMainFiles,
      bannedImports =
        listOf(
          "skillbill.infrastructure.host.concurrency.JvmInterruptSignalPort",
          "skillbill.ports.concurrency.JvmInterruptSignalPort",
        ),
    )
  }

  @Test
  fun `runtime application owns no direct timing logging or threading environment APIs`() {
    val applicationMainFiles =
      sourceFilesIn(
        runtimeArchitectureRoot.resolve("runtime-kotlin/runtime-application/src/main/kotlin"),
      )
    assertTrue(applicationMainFiles.isNotEmpty(), "runtime-application main source scan must be non-vacuous.")
    assertNoBannedImports(
      files = applicationMainFiles,
      bannedImports =
        listOf(
          "java.util.logging",
          "java.util.concurrent",
        ),
    )
    assertNoBannedSourceReferences(
      files = applicationMainFiles,
      bannedReferences =
        listOf(
          "Thread.sleep",
          "Thread.currentThread",
          "Thread(",
          ".interrupt()",
          ".getLogger(",
          "java.util.logging",
          "java.util.concurrent",
          "Executors",
          "Executor",
          "Future",
          "Callable",
          "TimeUnit",
        ),
      description = "environment API reference",
    )
  }

  @Test
  fun `application domain and ports avoid direct file IO`() {
    assertNoDirectFileIo(sourceFiles())
  }

  @Test
  fun `file IO guard reports a runtime-engine main violation`() {
    val enginePath = "runtime-kotlin/runtime-engine/src/main/kotlin/skillbill/engine/Synthetic.kt"
    val explicitImport =
      SourceFile(
        relativePath = enginePath,
        packageName = "skillbill.engine",
        imports = listOf("java.nio.file.Files"),
        source = "package skillbill.engine\n\nfun read(path: Path): String = Files.readString(path)\n",
      )
    val wildcardImport =
      explicitImport.copy(
        imports = listOf("java.nio.file.*"),
        source =
          "package skillbill.engine\n\nimport java.nio.file.*\n\n" +
            "fun read(path: Path) = Files.readString(path)\n",
      )

    val importFailure = assertFails { assertNoDirectFileIo(listOf(explicitImport)) }
    val referenceFailure = assertFails { assertNoDirectFileIo(listOf(wildcardImport)) }

    assertContains(importFailure.message.orEmpty(), "$enginePath imports java.nio.file.Files")
    assertContains(referenceFailure.message.orEmpty(), "$enginePath:5 contains direct file IO dependency Files.")
  }

  private fun assertNoDirectFileIo(files: List<SourceFile>) {
    val boundaryFiles =
      files.filter { file -> directFileIoGuardedSourceRoots.any(file.relativePath::startsWith) }
    assertNoBannedImports(
      files = boundaryFiles,
      bannedImports = RuntimeArchitectureScanConstants.directFileIoImports,
    )
    assertNoBannedSourceReferences(
      files = boundaryFiles,
      bannedReferences = RuntimeArchitectureScanConstants.directFileIoSourceReferences,
      description = "direct file IO dependency",
    )
  }

  @Test
  fun `domain and ports avoid JDBC HTTP and entrypoint frameworks`() {
    val domainAndPortFiles =
      sourceFiles()
        .filter { file ->
          file.relativePath.startsWith("runtime-kotlin/runtime-domain/src/main/kotlin/") ||
            file.relativePath.startsWith("runtime-kotlin/runtime-ports/src/main/kotlin/")
        }

    assertNoBannedImports(
      files = domainAndPortFiles,
      bannedImports = RuntimeArchitectureScanConstants.boundaryFrameworkImports,
    )
    assertNoBannedSourceReferences(
      files = domainAndPortFiles,
      bannedReferences = RuntimeArchitectureScanConstants.boundaryFrameworkSourceReferences,
      description = "JDBC, HTTP, or entrypoint framework dependency",
    )
  }

  @Test
  fun `no main source unions declaredCodeReviewAreas across all installed manifests`() {
    val unionSites =
      sourceFiles()
        .filter { file -> file.relativePath.contains("/src/main/kotlin/") }
        .flatMap { file ->
          Files.readString(runtimeArchitectureRoot.resolve(file.relativePath)).lines()
            .withIndex()
            .filter { (_, line) -> "declaredCodeReviewAreas" in line && "flatMap" in line }
            .map { (index, _) -> "${file.relativePath}:${index + 1}" }
        }

    assertEquals(
      emptyList(),
      unionSites,
      "A review area set must come from ReviewLaunchPlanPolicy.composedAreas for the routed pack, " +
        "never from a union of declaredCodeReviewAreas across every installed manifest: that union " +
        "puts areas the routed composition never declares into the plan, and a plan lane is read " +
        "downstream as a lane the run launched.",
    )
  }

  @Test
  fun `domain avoids random ids clock reads and java util logging`() {
    val domainFiles =
      sourceFiles()
        .filter { file ->
          file.relativePath.startsWith("runtime-kotlin/runtime-domain/src/main/kotlin/")
        }

    assertNoBannedSourceReferences(
      files = domainFiles,
      bannedReferences = RuntimeArchitectureScanConstants.domainEffectPuritySourceReferences,
      description = "runtime-domain effect-purity violation",
    )
  }

  @Test
  fun `application domain and ports use Path only as an inert value type`() {
    val boundaryFiles =
      sourceFiles()
        .filter { file ->
          file.relativePath.startsWith("runtime-kotlin/runtime-application/src/main/kotlin/") ||
            file.relativePath.startsWith("runtime-kotlin/runtime-domain/src/main/kotlin/") ||
            file.relativePath.startsWith("runtime-kotlin/runtime-ports/src/main/kotlin/")
        }
    val pathImportingFiles = boundaryFiles.filter { file -> "java.nio.file.Path" in file.imports }
    assertTrue(
      pathImportingFiles.isNotEmpty(),
      "The architecture intentionally allows java.nio.file.Path as a value type; the test must " +
        "exercise at least one current application/domain/port Path model or contract.",
    )
    assertNoBannedSourceReferences(
      files = boundaryFiles,
      bannedReferences = RuntimeArchitectureScanConstants.processAccessSourceReferences,
      description = "process or home-directory lookup",
    )
    assertNoBannedSourceReferences(
      files = boundaryFiles,
      bannedReferences = RuntimeArchitectureScanConstants.homeExpansionSourceReferences,
      description = "home-directory path expansion",
    )

    val reviewParsingPatterns = Files.readString(sourcePath("skillbill/review/parsing/ReviewParsingPatterns.kt"))
    assertTrue(
      "expandAndNormalizePath" !in reviewParsingPatterns,
      "ReviewParsingPatterns must stay pure string/regex parsing; filesystem path normalization belongs " +
        "to the adapter input seam.",
    )
  }

  @Test
  fun `learnings domain owns learning records without persistence dependencies`() {
    assertNoBannedImports(
      files = sourceFiles().filter { it.packageName.startsWith("skillbill.learnings") },
      bannedImports =
        listOf(
          "java.sql",
          "skillbill.db",
          "skillbill.infrastructure",
          "skillbill.review",
        ),
    )

    val reviewModels = Files.readString(sourcePath("skillbill/review/model/ReviewModels.kt"))
    val learningRecord = Files.readString(sourcePath("skillbill/learnings/model/LearningRecord.kt"))
    assertTrue("data class LearningRecord" !in reviewModels)
    assertContains(learningRecord, "data class LearningRecord")
  }

  @Test
  fun `public model declarations live in model packages`() {
    val violations =
      sourceFiles()
        .filter { file ->
          file.relativePath.startsWith("runtime-kotlin/runtime-application/") ||
            file.relativePath.startsWith("runtime-kotlin/runtime-domain/") ||
            file.relativePath.startsWith("runtime-kotlin/runtime-ports/")
        }
        .flatMap { file ->
          if (
            file.packageName.split('.').contains("model") ||
            file.relativePath.endsWith("skillbill/goalrunner/FeatureTaskRuntimeCommitPushResultArtifact.kt")
          ) {
            return@flatMap emptyList()
          }
          val source = Files.readString(runtimeArchitectureRoot.resolve(file.relativePath))
          val lines = source.lines()
          val tracker = ScopeTracker()
          lines.mapIndexedNotNull { index, line ->
            tracker.consume(line)
            val match =
              RuntimeArchitectureScanConstants.publicModelDeclarationPattern.find(line)
                ?: return@mapIndexedNotNull null
            val trimmed = line.trim()
            if (Regex("""^(?:private|internal)\s+""").containsMatchIn(trimmed)) return@mapIndexedNotNull null
            if (tracker.insideNonPublicScope) return@mapIndexedNotNull null
            "${file.relativePath}:${index + 1} declares ${match.groupValues.last()} outside a model package"
          }
        }
        .toList()

    assertTrue(violations.isEmpty(), violations.joinToString(separator = "\n"))
  }

  @Test
  fun `review package is separated from sqlite runtime support`() {
    assertNoBannedImports(
      files =
        sourceFiles().filter {
          it.packageName == "skillbill.review" ||
            it.packageName.startsWith(
              "skillbill.review.",
            )
        },
      bannedImports =
        listOf(
          "java.sql",
          "skillbill.db",
          "skillbill.infrastructure",
          "skillbill.ports",
          "skillbill.telemetry",
        ),
    )

    val sqliteReviewRuntime = sourcePath("skillbill/infrastructure/sqlite/review/stage/ReviewRuntime.kt")
    val sqliteTriageRuntime = sourcePath("skillbill/infrastructure/sqlite/review/stage/TriageRuntime.kt")
    val sqliteStatsRuntime = sourcePath("skillbill/infrastructure/sqlite/review/stats/ReviewStatsRuntime.kt")
    listOf(sqliteReviewRuntime, sqliteTriageRuntime, sqliteStatsRuntime).forEach { path ->
      assertContains(Files.readString(path), "package skillbill.infrastructure.sqlite.review.")
    }
  }

  @Test
  fun `cli workflow commands delegate to application instead of low level runtimes`() {
    assertNoBannedImports(
      files =
        sourceFiles().filter { file ->
          file.packageName.startsWith("skillbill.cli") &&
            !file.packageName.startsWith("skillbill.cli.model")
        },
      bannedImports =
        listOf(
          "skillbill.db",
          "skillbill.review",
          "skillbill.application.telemetry.config.TelemetryConfigRuntime",
          "skillbill.application.telemetry.sync.TelemetrySyncRuntime",
          "skillbill.learnings.LearningStore",
          "skillbill.learnings.LearningsRuntime",
        ),
    )
  }

  @Test
  fun `mcp workflow calls delegate to application instead of low level runtimes`() {
    assertNoBannedImports(
      files = sourceFiles().filter { file -> file.packageName.startsWith("skillbill.mcp") },
      bannedImports =
        listOf(
          "skillbill.db",
          "skillbill.review",
          "skillbill.learnings.LearningStore",
          "skillbill.learnings.LearningsRuntime",
          "skillbill.application.telemetry.config.TelemetryConfigRuntime",
        ),
    )
  }

  @Test
  fun `mcp adapter avoids direct filesystem http sql dependencies`() {
    val mcpFiles =
      sourceFiles()
        .filter { file -> file.relativePath.startsWith("runtime-kotlin/runtime-mcp/src/main/kotlin/") }
    val cliFiles =
      sourceFiles()
        .filter { file -> file.relativePath.startsWith("runtime-kotlin/runtime-cli/src/main/kotlin/") }

    assertNoBannedSourceReferences(
      files = mcpFiles,
      bannedReferences = listOf("java.net.http", "java.sql"),
      description = "direct HTTP or SQL dependency",
    )
    assertNoBannedSourceReferences(
      files = cliFiles,
      bannedReferences = listOf("java.net.http", "java.sql", "java.nio.file.Files", "Files."),
      description = "direct filesystem, HTTP, or SQL dependency",
    )

    assertNoBannedSourceReferences(
      files = mcpFiles,
      bannedReferences = listOf("java.nio.file.Files", "Files."),
      description = "direct filesystem dependency",
    )
    assertMcpScaffoldRuntimeOnlyUsesFilesForRepoRootDiscovery(mcpFiles)
  }

  @Test
  fun `learning service exposes typed results instead of map payloads`() {
    val serviceSource = Files.readString(sourcePath("skillbill/application/learning/LearningService.kt"))
    val mapReturningLearningFunctions =
      Regex("""fun\s+(list|show|resolve|add|edit|setStatus|delete)\s*\([^)]*\)\s*:\s*Map<""")
        .findAll(serviceSource)
        .map { match -> match.groupValues[1] }
        .toList()

    assertTrue(
      mapReturningLearningFunctions.isEmpty(),
      "LearningService functions still return Map payloads: ${mapReturningLearningFunctions.joinToString()}",
    )
    assertContains(serviceSource, "LearningListResult")
    assertContains(serviceSource, "LearningResolveResult")
  }

  @Test
  fun `future domain packages stay infrastructure free`() {
    assertNoBannedImports(
      files = sourceFiles().filter { it.packageName.startsWith("skillbill.domain") },
      bannedImports =
        listOf(
          "com.github.ajalt.clikt",
          "java.net.http",
          "java.sql",
          "java.nio.file.Files",
          "skillbill.cli",
          "skillbill.db",
          "skillbill.mcp",
        ),
    )
  }

  @Test
  fun `update check service keeps parser state invocation local`() {
    val source =
      Files.readString(
        sourcePath("skillbill/application/updatecheck/UpdateCheckService.kt"),
      )
    assertTrue(!source.contains("lastUnknown"), "UpdateCheckService must not cache last UNKNOWN results.")
    assertTrue(!source.contains("releasePayloadMalformed"), "Malformed payload flags must stay invocation-local.")
    assertTrue(!source.contains("releaseEntryMalformed"), "Malformed entry flags must stay invocation-local.")
  }
}

private val directFileIoGuardedSourceRoots: List<String> =
  listOf(
    "runtime-kotlin/runtime-application/src/main/kotlin/",
    "runtime-kotlin/runtime-domain/src/main/kotlin/",
    "runtime-kotlin/runtime-ports/src/main/kotlin/",
    "runtime-kotlin/runtime-engine/src/main/kotlin/",
  )
