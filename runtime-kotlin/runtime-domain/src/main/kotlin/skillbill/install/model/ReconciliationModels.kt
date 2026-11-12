package skillbill.install.model

sealed interface SkillReconciliationOutcome {
  val skillRelativePath: String

  data class Adopt(
    override val skillRelativePath: String,
    val upstreamHash: String,
    val localHash: String?,
    val baselineHash: String?,
  ) : SkillReconciliationOutcome

  data class Unchanged(
    override val skillRelativePath: String,
    val upstreamHash: String,
    val baselineHash: String?,
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
      outcomes.mapNotNull { outcome ->
        when (outcome) {
          is SkillReconciliationOutcome.Adopt -> outcome.skillRelativePath to outcome.upstreamHash
          is SkillReconciliationOutcome.Unchanged -> outcome.skillRelativePath to outcome.upstreamHash
          is SkillReconciliationOutcome.Prune -> null
          is SkillReconciliationOutcome.LocallyAuthored -> null
        }
      }.toMap()

  val prunedPaths: List<String>
    get() = outcomes.filterIsInstance<SkillReconciliationOutcome.Prune>().map { it.skillRelativePath }
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

  fun withEntries(updated: Map<String, String>): BaselineManifest = copy(entries = (entries + updated).toSortedMap())

  fun withoutEntries(removed: Collection<String>): BaselineManifest =
    copy(entries = entries.filterKeys { it !in removed }.toSortedMap())

  companion object {
    const val CONTRACT_VERSION: String = "1.0"

    fun empty(): BaselineManifest = BaselineManifest(CONTRACT_VERSION, emptyMap())

    fun of(
      contractVersion: String,
      entries: Map<String, String>,
    ): BaselineManifest = BaselineManifest(contractVersion, entries.toSortedMap())
  }
}
