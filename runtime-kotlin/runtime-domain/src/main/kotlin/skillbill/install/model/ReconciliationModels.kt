package skillbill.install.model

sealed interface SkillReconciliationOutcome {
  val skillRelativePath: String

  data class Adopt(
    override val skillRelativePath: String,
    val upstreamHash: String,
    val localHash: String?,
    val baselineHash: String?,
    val upstreamSourceHash: String? = null,
  ) : SkillReconciliationOutcome

  data class Unchanged(
    override val skillRelativePath: String,
    val upstreamHash: String,
    val baselineHash: String?,
    val upstreamSourceHash: String? = null,
  ) : SkillReconciliationOutcome

  data class Prune(
    override val skillRelativePath: String,
    val localHash: String,
    val baselineHash: String?,
  ) : SkillReconciliationOutcome

  data class LocallyAuthored(
    override val skillRelativePath: String,
    val localHash: String,
    val baselineHash: String?,
  ) : SkillReconciliationOutcome
}

data class ReconciliationPlan(
  val outcomes: List<SkillReconciliationOutcome>,
) {
  val baselineOverlay: Map<String, String>
    get() =
      outcomes.flatMap { outcome ->
        when (outcome) {
          is SkillReconciliationOutcome.Adopt ->
            baselineEntries(outcome.skillRelativePath, outcome.upstreamHash, outcome.upstreamSourceHash)
          is SkillReconciliationOutcome.Unchanged ->
            baselineEntries(outcome.skillRelativePath, outcome.upstreamHash, outcome.upstreamSourceHash)
          is SkillReconciliationOutcome.Prune -> emptyList()
          is SkillReconciliationOutcome.LocallyAuthored -> emptyList()
        }
      }.toMap()

  val prunedPaths: List<String>
    get() = outcomes.filterIsInstance<SkillReconciliationOutcome.Prune>().map { it.skillRelativePath }

  val prunedBaselineKeys: List<String>
    get() = prunedPaths.flatMap { path -> listOf(path, BaselineManifest.sourceKeyFor(path)) }

  private fun baselineEntries(
    skillRelativePath: String,
    upstreamHash: String,
    upstreamSourceHash: String?,
  ): List<Pair<String, String>> =
    listOfNotNull(
      skillRelativePath to upstreamHash,
      upstreamSourceHash?.let { BaselineManifest.sourceKeyFor(skillRelativePath) to it },
    )
}

fun preserveLocallyAuthoredSource(
  localHash: String?,
  upstreamHash: String?,
  baselineHash: String?,
): Boolean = localHash != null && localHash != baselineHash && (upstreamHash == null || localHash != upstreamHash)

data class InstallReconcileApplyOutcome(
  val plan: ReconciliationPlan,
  val installedPaths: List<String>,
  val prunedPaths: List<String>,
  val refreshed: Boolean,
)

data class BaselineManifest(
  val contractVersion: String,
  val entries: Map<String, String>,
) {
  fun hashFor(skillRelativePath: String): String? = entries[skillRelativePath]

  fun sourceHashFor(skillRelativePath: String): String? = entries[sourceKeyFor(skillRelativePath)]

  fun withEntries(updated: Map<String, String>): BaselineManifest = copy(entries = (entries + updated).toSortedMap())

  fun withoutEntries(removed: Collection<String>): BaselineManifest =
    copy(entries = entries.filterKeys { it !in removed }.toSortedMap())

  companion object {
    const val CONTRACT_VERSION: String = "1.0"
    private const val SOURCE_KEY_SUFFIX: String = "#source"

    fun sourceKeyFor(skillRelativePath: String): String = skillRelativePath + SOURCE_KEY_SUFFIX

    fun empty(): BaselineManifest = BaselineManifest(CONTRACT_VERSION, emptyMap())

    fun of(
      contractVersion: String,
      entries: Map<String, String>,
    ): BaselineManifest = BaselineManifest(contractVersion, entries.toSortedMap())
  }
}
