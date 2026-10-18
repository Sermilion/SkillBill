package dev.skillbill.tray.architecture

/**
 * What the removal of the monolithic desktop forbids from coming back: a root project that compiles sources, a combined work
 * source, an OS process launcher outside the native helpers, the retired executable and poll preferences, and periodic polling in
 * the data, domain and feature modules. Every rule judges production source sets only, and a scan that reached nothing fails
 * through [requireNonEmptyScan] instead of passing.
 */
internal object DeletionRules {
  /** The four work ports; one class implementing two of them is the combined board source the feature split retired. */
  val ports = setOf("WorkBoardRepository", "WorkDetailRepository", "WorkControlRepository", "WorkStoreSession")

  /** The only production files that start or inspect an OS process, all native tray and display helpers. */
  val approvedProcessFiles = setOf(
    "$DESKTOP_PACKAGE_ROOT/infrastructure/NativeServicesImpl.kt",
    "$DESKTOP_PACKAGE_ROOT/infrastructure/NativeTrayHost.kt",
    "$DESKTOP_PACKAGE_ROOT/infrastructure/TrayPopoverPlacement.kt",
    "$DESKTOP_PACKAGE_ROOT/infrastructure/macos/MacStatusItemBridge.kt",
  )

  /** The one file that may name the retired preference keys, because it reads and discards them with attributed diagnostics. */
  const val LEGACY_SETTINGS_FILE = "core/datastore/src/commonMain/kotlin/dev/skillbill/tray/core/datastore/LegacySettingsMigration.kt"

  /** Modules where a repeating timer would be a poll; deadline scheduling uses a single `delay` and stays legal. */
  val pollingFreeModules = setOf(ModuleGraphRules.DATA, ModuleGraphRules.DATASTORE, ModuleGraphRules.DOMAIN) + ModuleGraphRules.features

  private val retiredPreferences = Regex("""\b(?:cliOverride|pollSeconds)\b""")
  private val repeatingTimers = Regex(
    """\bjava\.util\.Timer\b|\bTimer\s*\(|\b(?:ticker|fixedRateTimer|scheduleAtFixedRate|scheduleWithFixedDelay)\s*\(""",
  )
  private val rootCompilation = Regex(
    """\bkotlin\s*\(|org\.jetbrains\.(?:kotlin|compose)|devtools\.ksp|"skillbill\.(?:kmp|desktop|kotlin)|libs\.plugins\.(?:kotlin|compose|ksp)""",
  )
  // Matched against referenced names, imports and qualified uses included; a comment or string that mentions one is not a use.
  private val processTypes = setOf("Process", "ProcessBuilder", "ProcessHandle", "Runtime")

  /** A scan that reached no production source, or none of a rule's modules, proves nothing and fails. */
  fun requireNonEmptyScan(sources: List<Source>) {
    val production = sources.filter { it.sourceSet in productionSourceSets }
    if (production.isEmpty()) throw AssertionError("The governed deletion scan set is empty.")
    pollingFreeModules.forEach { module ->
      if (production.none { it.project == module }) throw AssertionError("The deletion scan reached no production source of $module.")
    }
  }

  /** The root project is an aggregator: it applies no compilation plugin and owns no file under `src/`. */
  fun rootViolations(rootBuildScript: String, rootSourceFiles: List<String>): List<String> = buildList {
    rootCompilation.findAll(rootBuildScript).map { it.value }.distinct()
      .forEach { add("The root build applies a Kotlin, Compose or KSP plugin ($it); only composeApp and the modules compile.") }
    rootSourceFiles.forEach { add("$it is a root source file; production sources belong to composeApp and the core and feature modules.") }
  }

  fun violations(references: Map<Source, SourceReferences>): List<String> {
    // A typealias is visible project-wide; an import alias only inside the file that declares it.
    val typeAliases = references.values.flatMap { it.typeAliases.entries }.groupBy({ it.key }, { it.value }).mapValues { (_, targets) -> targets.toSet() }
    return references.flatMap { (source, found) ->
      if (source.sourceSet !in productionSourceSets) return@flatMap emptyList<String>()
      val path = source.path
      val aliases = typeAliases + found.importAliases.mapValues { (name, target) -> typeAliases[name].orEmpty() + target }
      buildList {
        found.supertypes.forEach { (type, supertypes) ->
          val combined = supertypes.flatMap { resolve(it, aliases) }.filter { it in ports }.toSortedSet()
          if (combined.size >= 2) add("$path: $type implements ${combined.joinToString(" and ")}; each work port has its own implementation")
        }
        if (path !in approvedProcessFiles && found.names.any { it in processTypes }) {
          add("$path starts or inspects an OS process; only the native tray helpers may")
        }
        if (path != LEGACY_SETTINGS_FILE) {
          retiredPreferences.findAll(source.text).map { it.value }.distinct()
            .forEach { add("$path references the retired preference $it; only the legacy settings migration may") }
        }
        if (source.project in pollingFreeModules) {
          repeatingTimers.findAll(source.text).map { it.value.trim() }.distinct()
            .forEach { add("$path uses the repeating timer $it; the data, domain and feature modules observe committed changes and schedule deadlines") }
        }
      }
    }
  }

  private fun resolve(name: String, aliases: Map<String, Set<String>>, seen: Set<String> = emptySet()): Set<String> =
    if (name in seen) emptySet() else setOf(name) + aliases[name].orEmpty().flatMap { resolve(it, aliases, seen + name) }
}
