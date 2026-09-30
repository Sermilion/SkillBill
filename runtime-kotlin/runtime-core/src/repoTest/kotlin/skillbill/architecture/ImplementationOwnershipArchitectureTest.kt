package skillbill.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

class ImplementationOwnershipArchitectureTest {
  private val runtimeRoot: Path = ArchitectureScanSupport.runtimeRoot

  @Test
  fun `runtime core is composition only and not an implementation umbrella`() {
    assertRuntimeCorePublicProjectEdges(runtimeRoot)

    val runtimeCoreSourceFiles = kotlinFilesUnderWithArchitectureAsserts(moduleMainKotlinRoot("runtime-core"))
    val nonCompositionPackages =
      runtimeCoreSourceFiles
        .mapNotNull { sourceFile ->
          val sourcePackage = packageName(sourceFile) ?: return@mapNotNull null
          if (sourcePackage == "skillbill.di" || sourcePackage.startsWith("skillbill.di.")) {
            null
          } else {
            "${runtimeRoot.relativize(sourceFile)} declares package $sourcePackage"
          }
        }
        .sorted()
    assertEquals(
      emptyList(),
      nonCompositionPackages,
      "runtime-core must reject every non-composition package beyond skillbill.di.",
    )

    assertEquals(
      emptyList(),
      implementationImportViolations(),
      "runtime-core must not import implementation packages outside the composition allow-list.",
    )
  }

  private fun implementationImportViolations(): List<String> {
    val bannedImplementationImports =
      listOf(
        "skillbill.install",
        "skillbill.launcher",
        "skillbill.nativeagent",
        "skillbill.scaffold",
        "skillbill.skillremove",
        "skillbill.workflow",
      )
    return kotlinFilesUnderWithArchitectureAsserts(moduleMainKotlinRoot("runtime-core"))
      .flatMap { sourceFile ->
        sourceFile.readText().lineSequence()
          .mapNotNull { line -> line.trim().removePrefix("import ").takeIf { line.trim().startsWith("import ") } }
          .filter { importedName -> bannedImplementationImports.any(importedName::startsWith) }
          .filterNot { importedName -> importedName in ALLOWED_COMPOSITION_IMPORTS }
          .map { importedName -> "${runtimeRoot.relativize(sourceFile)} imports $importedName" }
      }
      .sorted()
  }

  @Test
  fun `runtime core imports concrete infrastructure only from composition files`() {
    val runtimeCoreSourceFiles = kotlinFilesUnderWithArchitectureAsserts(moduleMainKotlinRoot("runtime-core"))
    val diDir = moduleMainKotlinRoot("runtime-core").resolve("skillbill/di")
    val compositionFiles =
      kotlinFilesUnderWithArchitectureAsserts(diDir)
        .filter { path ->
          val name = path.fileName.toString()
          name == "RuntimeComponent.kt" ||
            name.endsWith("Bindings.kt") ||
            name.endsWith("Provides.kt")
        }
        .toSet()
    val concreteInfrastructureViolations =
      runtimeCoreSourceFiles
        .filterNot { sourceFile -> sourceFile in compositionFiles }
        .flatMap { sourceFile ->
          sourceFile.importsForbiddenBy(
            setOf(
              "skillbill.db",
              "skillbill.infrastructure",
            ),
          ).map { forbiddenImport ->
            "${runtimeRoot.relativize(sourceFile)} imports $forbiddenImport"
          }
        }
        .sorted()
    assertEquals(
      emptyList(),
      concreteInfrastructureViolations,
      "runtime-core may import concrete infrastructure only from explicit DI composition files.",
    )
  }

  @Test
  fun `application domain and ports do not import adapters infrastructure or composition roots`() {
    val layerRules =
      mapOf(
        "runtime-kotlin/runtime-application/src/main/kotlin" to
          listOf(
            "skillbill.cli",
            "skillbill.mcp",
            "skillbill.db",
            "skillbill.di",
            "skillbill.infrastructure",
          ),
        "runtime-kotlin/runtime-domain/src/main/kotlin" to
          listOf(
            "com.github.ajalt.clikt",
            "java.net.http",
            "java.sql",
            "skillbill.application",
            "skillbill.cli",
            "skillbill.mcp",
            "skillbill.db",
            "skillbill.di",
            "skillbill.infrastructure",
            "skillbill.ports",
          ),
        "runtime-kotlin/runtime-ports/src/main/kotlin" to
          listOf(
            "com.github.ajalt.clikt",
            "java.net.http",
            "java.sql",
            "skillbill.application",
            "skillbill.cli",
            "skillbill.mcp",
            "skillbill.db",
            "skillbill.di",
            "skillbill.infrastructure",
          ),
      )

    val violations =
      layerRules.flatMap { (sourceRoot, forbiddenPrefixes) ->
        kotlinFilesUnderWithArchitectureAsserts(runtimeRoot.resolve(sourceRoot)).flatMap { sourceFile ->
          sourceFile.importsForbiddenBy(forbiddenPrefixes.toSet()).map { forbiddenImport ->
            "${runtimeRoot.relativize(sourceFile)} imports $forbiddenImport"
          }
        }
      }.sorted()

    assertEquals(
      emptyList(),
      violations,
      "Application, domain, and port layers must not import adapters, infrastructure, or DI composition roots.",
    )
  }

  @Test
  fun `cli and mcp adapters do not import concrete runtime implementations`() {
    val adapterSourceRoots =
      listOf(
        "runtime-kotlin/runtime-cli/src/main/kotlin",
        "runtime-kotlin/runtime-mcp/src/main/kotlin",
      )
    val violations =
      adapterSourceRoots
        .map { sourceRoot -> runtimeRoot.resolve(sourceRoot) }
        .flatMap(::kotlinFilesUnderWithArchitectureAsserts)
        .flatMap { sourceFile ->
          sourceFile.runtimeImplementationImports().map { importedName ->
            "${runtimeRoot.relativize(sourceFile)} imports $importedName"
          }
        }
        .sorted()

    assertEquals(
      emptyList(),
      violations,
      "CLI and MCP adapters must go through application services and ports instead of " +
        "concrete install, scaffold, native-agent, launcher, validation, or filesystem implementations.",
    )
  }

  @Test
  fun `scaffold policy packages must not import infrastructure adapters`() {
    val policySourceRoots =
      listOf("runtime-kotlin/runtime-domain/src/main/kotlin/skillbill/scaffold/policy")
        .map { sourceRoot -> runtimeRoot.resolve(sourceRoot) }
        .filter(Files::isDirectory)

    val violations =
      policySourceRoots
        .flatMap(::kotlinFilesUnderWithArchitectureAsserts)
        .flatMap { sourceFile ->
          sourceFile.readText().lineSequence()
            .map { line -> line.trim() }
            .filter(SCAFFOLD_POLICY_FORBIDDEN_IMPORT_REGEX::matches)
            .map { importLine -> "${runtimeRoot.relativize(sourceFile)} contains '$importLine'" }
            .toList()
        }
        .sorted()

    assertEquals(
      emptyList(),
      violations,
      "Scaffold pure-policy packages must not import skillbill.infrastructure.* adapter packages or " +
        "the concrete scaffold service and filesystem adapters; those imports leak adapter ownership into " +
        "runtime-domain policy code.",
    )
  }

  @Test
  fun `scaffold policy import regex catches known bad and passes known good`() {
    val mustBeDetectedAsForbidden =
      listOf(
        "import skillbill.infrastructure.skills.Foo",
        "import skillbill.infrastructure.skills.bar.Baz",
        "import skillbill.infrastructure.skills.scaffold.ScaffoldService",
        "import skillbill.infrastructure.skills.scaffold.FileSystemAnything",
        "import skillbill.infrastructure.skills.scaffold.adapters.FileSystemScaffoldSourceLoader",
      )
    val mustNotBeDetectedAsForbidden =
      listOf(
        "import skillbill.scaffold.policy.X",
        "import skillbill.scaffold.model.Y",
        "import skillbill.ports.scaffold.foo.Bar",
        "import java.nio.file.Path",
      )

    assertEquals(
      emptyList(),
      mustBeDetectedAsForbidden.filterNot(SCAFFOLD_POLICY_FORBIDDEN_IMPORT_REGEX::matches),
      "Scaffold-policy forbidden-import regex must detect known-bad import lines.",
    )
    assertEquals(
      emptyList(),
      mustNotBeDetectedAsForbidden.filter(SCAFFOLD_POLICY_FORBIDDEN_IMPORT_REGEX::matches),
      "Scaffold-policy forbidden-import regex must not flag known-good import lines.",
    )
  }

  private companion object {
    val ALLOWED_COMPOSITION_IMPORTS: Set<String> =
      setOf(
        "skillbill.ports.install.InstallPlanWireValidator",
        "skillbill.infrastructure.launcher.agentrun.FileSystemAgentRunLauncher",
        "skillbill.infrastructure.launcher.agentrun.PathExecutableLookup",
        "skillbill.infrastructure.launcher.review.UnixSocketGovernedReviewEvidenceEndpointBinder",
        "skillbill.ports.workflow.decomposition.DecompositionManifestValidator",
        "skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator",
        "skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator",
        "skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator",
        "skillbill.ports.taskruntime.FeatureTaskRuntimeWireArtifactValidator",
        "skillbill.ports.idestatus.IdeStatusValidator",
        "skillbill.ports.workflow.WorkflowSnapshotValidator",
        "skillbill.infrastructure.skills.scaffold.adapters.FileSystemScaffoldRepoValidation",
        "skillbill.infrastructure.skills.scaffold.adapters.FileSystemScaffoldSourceLoader",
        "skillbill.infrastructure.skills.skillremove.FileSystemSkillRemoveFileSystem",
      )

    val SCAFFOLD_POLICY_FORBIDDEN_IMPORT_REGEX =
      Regex(
        "^import\\s+(skillbill\\.infrastructure\\.(?:host|contracts|skills|launcher|workflow)(?:\\..*)?|" +
          "skillbill\\.scaffold\\.(?:adapters\\..*|ScaffoldService|FileSystem.*))$",
      )
  }

  private fun packageName(sourceFile: Path): String? =
    sourceFile.readText().lineSequence()
      .firstOrNull { line -> line.startsWith("package ") }
      ?.removePrefix("package ")
      ?.trim()
      ?.takeIf(String::isNotBlank)

  private fun Path.importsForbiddenBy(forbiddenPackages: Set<String>): List<String> =
    readText()
      .lineSequence()
      .mapNotNull { line -> line.trim().removePrefix("import ").takeIf { line.trim().startsWith("import ") } }
      .filter { importedPackage ->
        forbiddenPackages.any { forbidden ->
          importedPackage == forbidden || importedPackage.startsWith("$forbidden.")
        }
      }
      .toList()

  private fun Path.runtimeImplementationImports(): List<String> =
    readText()
      .lineSequence()
      .mapNotNull { line -> line.trim().removePrefix("import ").takeIf { line.trim().startsWith("import ") } }
      .filter(::isRuntimeImplementationImport)
      .toList()
}
