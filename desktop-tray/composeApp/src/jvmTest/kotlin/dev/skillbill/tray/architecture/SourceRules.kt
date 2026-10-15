package dev.skillbill.tray.architecture

internal object SourceRules {
  const val TESTING_PACKAGE = "dev.skillbill.tray.core.testing"

  val commonForbidden = listOf("java", "javax", "jdk", "sun", "com.sun", "org.sqlite", "org.xerial", "skillbill")
  val domainForbidden = listOf(
    "androidx.compose", "org.jetbrains.compose", "androidx.lifecycle", "org.jetbrains.androidx", "androidx.room",
    "androidx.room3", "androidx.datastore", "androidx.navigation", "androidx.sqlite", "androidx.paging",
    "dev.nucleusframework", "skillbill", "dev.skillbill.tray.application", "dev.skillbill.tray.infrastructure",
    "dev.skillbill.tray.ui", "dev.skillbill.tray.presentation", "dev.skillbill.tray.composition",
    "dev.skillbill.tray.core.designsystem",
  )

  /** The data and settings modules: they own the runtime, JDBC and preferences adapters, in `jvmMain` only. */
  val adapterModules = setOf(ModuleGraphRules.DATA, ModuleGraphRules.DATASTORE)

  /** UI frameworks the adapter modules never see. */
  val adapterForbidden = listOf(
    "androidx.compose", "org.jetbrains.compose", "androidx.lifecycle", "org.jetbrains.androidx", "androidx.navigation",
    "dev.nucleusframework",
  )

  /** Runtime, JDBC and preferences APIs; production code outside the adapter modules does not touch them. */
  val adapterOnly = listOf("skillbill", "java.sql", "java.util.prefs", "org.sqlite", "org.xerial")

  /** Reflective lookup is invisible to the generated graph, so no production source may use it. */
  val reflectiveLookup = listOf(
    "java.util.ServiceLoader", "ServiceLoader", "Class.forName", "java.lang.Class.forName", "java.lang.reflect",
    "kotlin.reflect.full.createInstance",
  )

  /** Types composition sources never construct directly; the generated graph owns their creation. */
  val composedByGraph = Regex("^(?:.+Repository|DataStore|RuntimeComponent|RoomBoardSource)$")

  fun violations(references: Map<Source, SourceReferences>): List<String> = references.flatMap { (source, found) ->
    val path = source.path
    val project = checkNotNull(source.project) { "source $path has no Gradle source-set attribution" }
    val sourceSet = checkNotNull(source.sourceSet) { "source $path has no Gradle source-set attribution" }
    val production = sourceSet in productionSourceSets
    val forbidden = buildSet {
      if (sourceSet == "commonMain" || sourceSet == "commonTest") addAll(commonForbidden)
      if (project == ModuleGraphRules.DOMAIN) addAll(domainForbidden)
      if (production && project != ModuleGraphRules.TESTING) add(TESTING_PACKAGE)
      if (project in adapterModules) addAll(adapterForbidden)
      if (production && project !in adapterModules) addAll(adapterOnly)
      if (production) addAll(reflectiveLookup)
    }
    found.referencesAny(forbidden).map { "$path references $it" } +
      found.injectDefaults.map { "$path gives an @Inject constructor parameter a default value: $it" } +
      (if (production) found.collaboratorDefaults else emptyList()).map {
        "$path gives a production constructor collaborator a default value: $it"
      } +
      compositionConstructions(source, found)
  }

  /** A governed consumer the policy scan must reach, and a call it must make on the way (the runtime-owned arrangement). */
  data class PolicyConsumer(val pathSuffix: String, val name: String, val mustCall: String? = null)

  /** The real surfaces that render the runtime's arrangement; a scan that never reaches them proves nothing. */
  val policyConsumers = setOf(
    PolicyConsumer("/presentation/TrayPresentation.kt", "presentTrayState"),
    PolicyConsumer("/presentation/TrayPresentation.kt", "presentPopover"),
    PolicyConsumer("/application/DashboardController.kt", "observeSelectedDetail"),
    PolicyConsumer("/application/DashboardController.kt", "publish", mustCall = "arrange"),
  )

  private val policySurfaces = listOf("/application/", "/presentation/", "/ui/")

  private fun Source?.isDesktopProduction(): Boolean = this != null && sourceSet in productionSourceSets

  /**
   * Walks every edge out of the desktop surfaces that render work (application, presentation, ui and the domain model)
   * and reports each visited desktop function outside the adapter modules that sorts work, judges lifecycle or freshness
   * membership, weighs a time window, or weighs freshness into eligibility itself. Data adapters legitimately order
   * storage pages, so they are walked but not reported. An empty scan, an empty root set, an edge that resolves to no
   * declaration, and a missing [requiredConsumers] entry each fail loudly instead of passing.
   */
  fun policyOwnershipViolations(sources: List<Source>, requiredConsumers: Set<PolicyConsumer> = policyConsumers): List<String> {
    val functions = parsedSymbols(sources)
    if (functions.isEmpty()) throw AssertionError("The governed policy scan set is empty.")
    val bySource = sources.associateBy(Source::path)
    val roots = functions.filter { function ->
      val source = bySource[function.path]
      source.isDesktopProduction() &&
        (source?.project == ModuleGraphRules.DOMAIN || policySurfaces.any { it in function.path })
    }
    val visited = walkGovernedEdges(functions, roots)
    val missing = requiredConsumers.filter { consumer ->
      visited.none { it.path.endsWith(consumer.pathSuffix) && it.name == consumer.name && (consumer.mustCall == null || consumer.mustCall in it.calls) }
    }
    if (missing.isNotEmpty()) throw AssertionError("The policy scan did not reach its governed consumers: $missing")
    return visited.filter { function ->
      val source = bySource[function.path]
      source.isDesktopProduction() && source?.project !in adapterModules
    }.flatMap { function -> function.policySites.distinct().map { "${function.path}:${function.name} $it" } }.sorted()
  }

  private fun compositionConstructions(source: Source, found: SourceReferences): List<String> =
    if (source.sourceSet !in productionSourceSets || !isComposition(source.path)) {
      emptyList()
    } else {
      found.calls.filter { composedByGraph.matches(it) }.sorted().map { "${source.path} constructs $it directly" }
    }

  private fun isComposition(path: String): Boolean = "/composition/" in path || "/di/" in path
}
