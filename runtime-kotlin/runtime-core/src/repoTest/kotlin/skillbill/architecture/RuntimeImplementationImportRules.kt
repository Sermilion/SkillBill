package skillbill.architecture

import java.nio.file.Path
import kotlin.test.assertEquals

internal fun isRuntimeImplementationImport(importedName: String): Boolean {
  val forbiddenPrefixes =
    listOf(
      "skillbill.infrastructure.sqlite.",
      "skillbill.infrastructure.",
      "skillbill.infrastructure.skills.",
      "skillbill.infrastructure.http.",
      "skillbill.infrastructure.sqlite.",
      "skillbill.infrastructure.skills.nativeagent.",
      "skillbill.infrastructure.launcher.",
      "skillbill.infrastructure.skills.skillremove.",
    )
  val importsForbiddenRoot = forbiddenPrefixes.any(importedName::startsWith)
  val importsInstallImplementation =
    importedName.startsWith("skillbill.infrastructure.skills.install.") &&
      !importedName.startsWith("skillbill.install.model.")
  val importsScaffoldImplementation =
    importedName.startsWith("skillbill.infrastructure.skills.scaffold.") &&
      !importedName.startsWith("skillbill.scaffold.model.")
  val importsTelemetryImplementation =
    importedName.startsWith("skillbill.telemetry.") &&
      !importedName.startsWith("skillbill.telemetry.model.")
  val importsLearningsImplementation =
    importedName.startsWith("skillbill.learnings.") &&
      !importedName.startsWith("skillbill.learnings.model.")
  val importsReviewImplementation = importedName.startsWith("skillbill.review.")
  return importsForbiddenRoot || importsInstallImplementation || importsScaffoldImplementation ||
    importsTelemetryImplementation || importsLearningsImplementation || importsReviewImplementation
}

internal fun isSchemaOrCoherenceValidatorImport(importedName: String): Boolean {
  val simpleName = importedName.substringAfterLast('.')
  return simpleName.endsWith("SchemaValidator") || simpleName.endsWith("CoherenceValidator")
}

internal fun jdbcSqliteConnectionSitesOutsideDatabaseRuntime(sourceRoots: List<Path>): List<String> =
  sourceRoots.flatMap { root ->
    if (!root.toFile().isDirectory) {
      emptyList()
    } else {
      root.toFile().walkTopDown()
        .filter { file -> file.isFile && file.extension == "kt" }
        .filter { file -> file.name != "DatabaseRuntime.kt" }
        .filter { file -> "jdbc:sqlite" in file.readText() }
        .map { file -> file.toPath().toString() }
        .toList()
    }
  }.sorted()

internal fun assertRuntimeCorePublicProjectEdges(runtimeRoot: Path) {
  val expectedApiEdges =
    RuntimeModuleCatalog.moduleEdgeExpectations
      .getValue("runtime-core")
      .api
      .map { moduleName -> ":$moduleName" }
      .toSet()
  assertEquals(
    expectedApiEdges,
    runtimeComponentPublicAbiEdges(runtimeRoot).projectEdges,
    "runtime-core public project edges must exactly match RuntimeComponent's generated public ABI.",
  )
}

private data class RuntimeComponentPublicAbiEdges(
  val projectEdges: Set<String>,
  val unknownTypes: Set<String>,
)

private val RUNTIME_CORE_COMPOSITION_TYPES =
  setOf(
    "RuntimeContext",
    "TransportContext",
    "WorkflowOpsContext",
    "OptionalCallbacks",
  )

private fun runtimeComponentPublicAbiEdges(runtimeRoot: Path): RuntimeComponentPublicAbiEdges {
  val componentText = runtimeComponentText(runtimeRoot)
  val importsBySimpleName = importsBySimpleName(componentText)
  val publicTypeNames =
    Regex("""abstract\s+val\s+\w+\s*:\s*([A-Za-z0-9_]+)""")
      .findAll(componentText)
      .map { match -> match.groupValues[1] }
      .toMutableSet()
  Regex("""fun\s+\w+\s*\([^)]*\)\s*:\s*([A-Za-z0-9_]+)""")
    .findAll(componentText)
    .filterNot { match ->
      componentText
        .substring(0, match.range.first)
        .lineSequence()
        .lastOrNull()
        ?.contains("internal") == true
    }
    .mapTo(publicTypeNames) { match -> match.groupValues[1] }
  Regex("""RuntimeComponent\s*\(\s*private\s+val\s+\w+\s*:\s*([A-Za-z0-9_]+)""", RegexOption.DOT_MATCHES_ALL)
    .find(componentText)
    ?.groupValues
    ?.get(1)
    ?.let(publicTypeNames::add)
  val projectEdges = mutableSetOf<String>()
  val unknownTypes = mutableSetOf<String>()
  publicTypeNames.forEach { typeName ->
    if (typeName in RUNTIME_CORE_COMPOSITION_TYPES) return@forEach
    when (val importedName = importsBySimpleName[typeName]) {
      null -> unknownTypes += typeName
      else ->
        when {
          importedName.startsWith("skillbill.ports.") || importedName.startsWith("skillbill.model.") ->
            projectEdges += ":runtime-ports"
          else -> {
            val apiModules = RuntimeModuleCatalog.moduleEdgeExpectations.getValue("runtime-core").api
            val owner =
              RuntimeModuleCatalog.moduleMainPackageRoots.entries
                .filter { (module, root) -> module in apiModules && importedName.startsWith("$root.") }
                .maxByOrNull { (_, root) -> root.length }
                ?.key
            if (owner == null) unknownTypes += importedName else projectEdges += ":$owner"
          }
        }
    }
  }
  assertEquals(
    emptySet(),
    unknownTypes,
    "RuntimeComponent public ABI must expose only runtime-application services and runtime-ports types. " +
      "Unknown or concrete ABI types: ${unknownTypes.sorted()}",
  )
  return RuntimeComponentPublicAbiEdges(projectEdges, unknownTypes)
}

private fun runtimeComponentText(runtimeRoot: Path): String =
  runtimeRoot.resolve(
    "runtime-kotlin/runtime-core/src/main/kotlin/skillbill/di/core/RuntimeComponent.kt",
  ).toFile().readText()

private fun importsBySimpleName(sourceText: String): Map<String, String> =
  sourceText.lineSequence()
    .mapNotNull { line -> line.trim().removePrefix("import ").takeIf { line.trim().startsWith("import ") } }
    .associateBy { importedName -> importedName.substringAfterLast('.') }
