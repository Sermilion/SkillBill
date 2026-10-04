package skillbill.infrastructure.workflow.goalplanning

import me.tatarka.inject.annotations.Inject
import skillbill.error.shellcontent.goalVerificationBoundaryCapExceededError
import skillbill.goalrunner.planning.GoalPlanningExcludedPaths
import skillbill.ports.goalrunner.planning.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.planning.model.GoalPlanningBoundaryHeading
import skillbill.ports.goalrunner.planning.model.GoalPlanningBoundaryHeadingKind
import skillbill.ports.goalrunner.planning.model.GoalPlanningContext
import skillbill.ports.goalrunner.verification.model.GoalVerificationBoundaryDiscovery
import java.nio.file.Path
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

@Inject
class FileSystemGoalPlanningContextDiscovery(
  private val clock: Clock,
) : GoalPlanningContextDiscovery {
  override fun loadPlanningContext(repoRoot: Path): GoalPlanningContext {
    val canonicalRoot = GoalPlanningRepositoryScope.canonicalRoot(repoRoot)
    val walk = GoalPlanningRepositoryScope.agentDirectories(canonicalRoot)
    return buildContext(canonicalRoot, walk, PlanningDiscoveryCaps)
  }

  override fun discoverForFindingPaths(
    repoRoot: Path,
    findingPaths: List<String>,
    loudFailOnCapExceeded: Boolean,
  ): GoalVerificationBoundaryDiscovery {
    val canonicalRoot = GoalPlanningRepositoryScope.canonicalRoot(repoRoot)
    val normalizedPaths =
      findingPaths.mapNotNull(GoalPlanningRepositoryScope::normalizeFindingPath)
        .distinct()
        .filterNot(GoalPlanningExcludedPaths::isExcluded)
    if (normalizedPaths.isEmpty()) {
      return GoalVerificationBoundaryDiscovery(
        boundaryCatalog = emptyList(),
        boundaryCatalogTruncated = false,
        boundaryContextUnavailable = true,
      )
    }
    val agentDirectories = GoalPlanningRepositoryScope.owningAgentDirectories(canonicalRoot, normalizedPaths)
    if (agentDirectories.isEmpty()) {
      return GoalVerificationBoundaryDiscovery(
        boundaryCatalog = emptyList(),
        boundaryCatalogTruncated = false,
        boundaryContextUnavailable = true,
      )
    }
    val walk =
      AgentDirectoryWalk(
        directories = agentDirectories,
        incomplete = false,
      )
    val catalog = discoverCatalog(canonicalRoot, walk, VerificationDiscoveryCaps, loudFailOnCapExceeded)
    return GoalVerificationBoundaryDiscovery(
      boundaryCatalog = catalog.headings,
      boundaryCatalogTruncated = catalog.truncated,
      boundaryContextUnavailable = false,
    )
  }

  private fun buildContext(
    canonicalRoot: Path,
    walk: AgentDirectoryWalk,
    caps: DiscoveryCaps,
  ): GoalPlanningContext {
    val catalog = discoverCatalog(canonicalRoot, walk, caps)
    return GoalPlanningContext(
      boundaryCatalog = catalog.headings,
      boundaryCatalogTruncated = catalog.truncated,
      validationGuidance =
        if (caps.includeValidationGuidance) {
          readValidationGuidance(canonicalRoot)
        } else {
          ""
        },
    )
  }

  private fun discoverCatalog(
    repoRoot: Path,
    walk: AgentDirectoryWalk,
    caps: DiscoveryCaps,
    loudFailOnCapExceeded: Boolean = false,
  ): Catalog {
    val candidates = candidateFiles(repoRoot, walk.directories)
    val eligible = candidates.take(caps.maxDiscoveryFileCount)
    var truncated = walk.incomplete || eligible.size < candidates.size
    val perFile = mutableListOf<List<GoalPlanningBoundaryHeading>>()
    for (candidate in eligible) {
      val read =
        goalPlanningReadFileOrNull(
          candidate.canonical,
          GoalPlanningContext.MAX_BOUNDARY_FILE_BYTES,
        )
      if (read == null) {
        truncated = true
      } else {
        if (read.cut) truncated = true
        var entries = BoundaryMemoryHeadingParser.parse(candidate.relative, read.text)
        if (caps.historyRecencyDays != null && candidate.kind == GoalPlanningContext.KIND_HISTORY) {
          val cutoff = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(caps.historyRecencyDays.toLong())
          val beforeRecencyFilter = entries.size
          entries =
            entries.filter { entry ->
              BoundaryMemoryHeadingParser.entryDate(entry.heading)?.let { entryDate -> entryDate >= cutoff } == true
            }
          if (entries.size < beforeRecencyFilter) truncated = true
        }
        if (entries.size > caps.maxHeadingsPerFile) truncated = true
        perFile.add(
          entries.take(caps.maxHeadingsPerFile).map { entry ->
            GoalPlanningBoundaryHeading(
              headingId = entry.headingId,
              sourcePath = candidate.relative,
              kind = candidate.kind,
              heading = entry.heading.take(GoalPlanningContext.MAX_HEADING_TEXT_CHARS),
            )
          },
        )
      }
    }
    val quotas = fairQuotas(perFile.map(List<GoalPlanningBoundaryHeading>::size), caps.maxCatalogHeadings)
    val headings = perFile.flatMapIndexed { index, headings -> headings.take(quotas[index]) }
    truncated = truncated || perFile.indices.any { index -> quotas[index] < perFile[index].size }
    if (loudFailOnCapExceeded && truncated) {
      throw goalVerificationBoundaryCapExceededError(
        "finding verification boundary discovery exceeded a verification cap",
      )
    }
    return Catalog(
      headings = headings,
      truncated = truncated,
    )
  }

  private fun candidateFiles(
    repoRoot: Path,
    agentDirectories: List<Path>,
  ): List<Candidate> =
    agentDirectories.flatMap { agentDir ->
      GoalPlanningRepositoryScope.BOUNDARY_MEMORY_FILES.mapNotNull { fileName ->
        GoalPlanningRepositoryScope.included(repoRoot, agentDir.resolve(fileName))
          ?.let { (canonical, relative) -> Candidate(canonical, relative, kindOf(fileName)) }
      }
    }

  private fun fairQuotas(
    sizes: List<Int>,
    maxCatalogHeadings: Int,
  ): List<Int> {
    val quotas = MutableList(sizes.size) { 0 }
    var remaining = maxCatalogHeadings
    var progressed = true
    while (remaining > 0 && progressed) {
      progressed = false
      for (index in sizes.indices) {
        if (remaining > 0 && quotas[index] < sizes[index]) {
          quotas[index] += 1
          remaining -= 1
          progressed = true
        }
      }
    }
    return quotas
  }

  private fun readValidationGuidance(repoRoot: Path): String {
    val canonical = GoalPlanningRepositoryScope.includedRegularFile(repoRoot, "AGENTS.md") ?: return ""
    val read =
      goalPlanningReadFileOrNull(
        canonical,
        GoalPlanningContext.MAX_VALIDATION_GUIDANCE_BYTES.toLong(),
      ) ?: return ""
    return read.text.replace("\r\n", "\n").replace('\r', '\n')
  }

  private fun kindOf(fileName: String): GoalPlanningBoundaryHeadingKind =
    if (fileName == "history.md") GoalPlanningContext.KIND_HISTORY else GoalPlanningContext.KIND_DECISIONS

  private data class Candidate(val canonical: Path, val relative: String, val kind: GoalPlanningBoundaryHeadingKind)

  private data class Catalog(val headings: List<GoalPlanningBoundaryHeading>, val truncated: Boolean)

  private data class DiscoveryCaps(
    val maxDiscoveryFileCount: Int,
    val maxHeadingsPerFile: Int,
    val maxCatalogHeadings: Int,
    val includeValidationGuidance: Boolean,
    val historyRecencyDays: Int? = null,
  )

  private companion object {
    val PlanningDiscoveryCaps =
      DiscoveryCaps(
        maxDiscoveryFileCount = GoalPlanningContext.MAX_DISCOVERY_FILE_COUNT,
        maxHeadingsPerFile = GoalPlanningContext.MAX_HEADINGS_PER_FILE,
        maxCatalogHeadings = GoalPlanningContext.MAX_CATALOG_HEADINGS,
        includeValidationGuidance = true,
      )
    val VerificationDiscoveryCaps =
      DiscoveryCaps(
        maxDiscoveryFileCount = GoalPlanningContext.VERIFICATION_MAX_DISCOVERY_FILE_COUNT,
        maxHeadingsPerFile = GoalPlanningContext.VERIFICATION_MAX_HEADINGS_PER_FILE,
        maxCatalogHeadings = GoalPlanningContext.VERIFICATION_MAX_CATALOG_HEADINGS,
        includeValidationGuidance = false,
        historyRecencyDays = GoalPlanningContext.VERIFICATION_HISTORY_RECENCY_DAYS,
      )
  }
}
