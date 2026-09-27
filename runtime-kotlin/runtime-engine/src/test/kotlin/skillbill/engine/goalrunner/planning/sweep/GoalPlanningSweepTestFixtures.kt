package skillbill.engine.goalrunner.planning.sweep

import skillbill.engine.goalrunner.launchFacts
import skillbill.ports.agentrun.model.AgentRunLaunchOutcome
import skillbill.ports.goalrunner.planning.GoalPlanningContextDiscovery
import skillbill.ports.goalrunner.planning.model.GoalPlanningBoundaryHeading
import skillbill.ports.goalrunner.planning.model.GoalPlanningContext
import skillbill.ports.goalrunner.runner.GoalRunnerSubtaskLauncher
import skillbill.ports.goalrunner.runner.model.GoalRunnerSubtaskLaunchRequest
import skillbill.ports.goalrunner.verification.model.GoalVerificationBoundaryDiscovery
import skillbill.ports.taskruntime.FeatureTaskRuntimeRunInvariantsSource
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.workflow.decomposition.model.DecompositionManifestWireMap
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeFeatureSize
import skillbill.workflow.taskruntime.model.handoff.task.FeatureTaskRuntimeRunInvariants
import java.nio.file.Path

internal fun validPhaseOutcome(phase: String): AgentRunLaunchOutcome = launchFacts(stdout = phasePayload(phase))

internal class SweepPlanningLauncher(
  private val behavior: (phase: String, subtaskId: Int, request: GoalRunnerSubtaskLaunchRequest)
  -> AgentRunLaunchOutcome,
) : GoalRunnerSubtaskLauncher {
  val requests = mutableListOf<GoalRunnerSubtaskLaunchRequest>()
  val phases = mutableListOf<String>()
  val subtaskIds = mutableListOf<Int>()

  override fun launch(request: GoalRunnerSubtaskLaunchRequest): AgentRunLaunchOutcome {
    val phase = phaseOf(request)
    val subtaskId = request.skillRunRequest.subtaskId ?: 0
    synchronized(this) {
      requests += request
      phases += phase
      subtaskIds += subtaskId
    }
    return behavior(phase, subtaskId, request)
  }

  fun phaseOf(request: GoalRunnerSubtaskLaunchRequest): String {
    val prompt = request.skillRunRequest.promptOverride.orEmpty()
    return Regex("""Phase: (\w+) \(""").find(prompt)?.groupValues?.get(1) ?: "unknown"
  }
}

internal class CountingManifestFileStore : DecompositionManifestStore {
  private val readPaths = mutableListOf<String>()
  private val removedFileNames = mutableSetOf<String>()
  private var decompositionManifest = "content-decomposition-manifest.yaml"
  private val specContents = mutableMapOf<String, String>()

  @Synchronized
  override fun readText(path: Path): String {
    check(path.fileName.toString() !in removedFileNames) { "missing scratch spec at ${path.fileName}" }
    readPaths += path.toString()
    return if (path.fileName.toString() == "decomposition-manifest.yaml") {
      decompositionManifest
    } else {
      specContents[path.fileName.toString()] ?: "content-${path.fileName}"
    }
  }

  override fun readTextWithoutRecovery(path: Path): String = readText(path)

  override fun isRegularFile(path: Path): Boolean = path.fileName.toString() !in removedFileNames

  override fun isRegularFileWithoutRecovery(path: Path): Boolean = isRegularFile(path)

  override fun findDecompositionManifestFiles(repoRoot: Path): List<Path> = emptyList()

  override fun findDecompositionManifestFilesWithoutRecovery(repoRoot: Path): List<Path> =
    findDecompositionManifestFiles(repoRoot)

  override fun listDirectChildDirectories(directory: Path): List<Path> = emptyList()

  override fun deleteIfExists(target: Path): Unit =
    error("CountingManifestFileStore is read-only in goal planning sweep tests.")

  override fun writeTextAtomically(
    target: Path,
    content: String,
  ): Unit = error("CountingManifestFileStore is read-only in goal planning sweep tests.")

  override fun <T> writeBundleAtomically(
    writes: List<Pair<Path, String>>,
    verify: () -> T,
  ): T = error("CountingManifestFileStore is read-only in goal planning sweep tests.")

  override fun encodeManifestYaml(wireMap: DecompositionManifestWireMap): String =
    error("CountingManifestFileStore is read-only in goal planning sweep tests.")

  fun countContaining(fragment: String): Int = readPaths.count { path -> fragment in path }

  fun remove(fileName: String) {
    removedFileNames += fileName
  }

  fun replaceDecompositionManifest(content: String) {
    decompositionManifest = content
  }

  fun replaceSpec(
    fileName: String,
    content: String,
  ) {
    specContents[fileName] = content
  }
}

internal class FakeInvariantsSource : FeatureTaskRuntimeRunInvariantsSource {
  override fun read(specPath: Path): FeatureTaskRuntimeRunInvariants =
    FeatureTaskRuntimeRunInvariants(
      specReference = specPath.toString(),
      featureSize = FeatureTaskRuntimeFeatureSize.MEDIUM,
      acceptanceCriteria = listOf("The sweep produces a schema-valid plan for this sub-spec."),
      mandatesAndOverrides = emptyList(),
    )
}

internal val fakeContextDiscovery =
  object : GoalPlanningContextDiscovery {
    override fun loadPlanningContext(repoRoot: Path): GoalPlanningContext =
      GoalPlanningContext(
        boundaryCatalog =
          listOf(
            GoalPlanningBoundaryHeading(
              headingId = FIXTURE_HEADING_ID,
              sourcePath = "runtime-kotlin/agent/history.md",
              kind = GoalPlanningContext.KIND_HISTORY,
              heading = FIXTURE_HEADING,
            ),
          ),
        boundaryCatalogTruncated = false,
        validationGuidance = "Run focused Gradle checks.",
      )

    override fun discoverForFindingPaths(
      repoRoot: Path,
      findingPaths: List<String>,
      loudFailOnCapExceeded: Boolean,
    ) = GoalVerificationBoundaryDiscovery(
      boundaryCatalog = loadPlanningContext(repoRoot).boundaryCatalog,
      boundaryCatalogTruncated = false,
      boundaryContextUnavailable = findingPaths.isEmpty(),
    )
  }
