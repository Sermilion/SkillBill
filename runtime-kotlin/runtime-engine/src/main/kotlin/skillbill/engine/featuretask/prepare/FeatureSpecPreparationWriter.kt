package skillbill.engine.featuretask.prepare

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.DecompositionManifestWriter
import skillbill.application.decomposition.baseBranch
import skillbill.application.decomposition.decompositionManifestPath
import skillbill.application.decomposition.decompositionPlanningResult
import skillbill.application.decomposition.decompositionPlanningSubtask
import skillbill.application.decomposition.defaultFeatureBranch
import skillbill.application.decomposition.loadValidatedDecompositionManifestPersistingRepair
import skillbill.application.decomposition.model.DecompositionManifestWriteRequest
import skillbill.application.decomposition.model.DecompositionPlanningResultOptions
import skillbill.application.decomposition.model.DecompositionPlanningSubtaskOptions
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.decomposition.repoRelativePath
import skillbill.application.decomposition.specSource
import skillbill.contracts.decomposition.DecompositionManifestPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.decomposition.DecompositionPlanningResult
import skillbill.error.core.InvalidFeatureSpecPreparationRequestError
import skillbill.featurespec.model.FeatureSpecPreparationMode
import skillbill.featurespec.model.FeatureSpecSubtaskPreparation
import skillbill.featurespec.model.FeatureSpecWriteRequest
import skillbill.featurespec.model.FeatureSpecWriteResult
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.DecompositionSubtask
import skillbill.workflow.decomposition.model.SpecSource
import java.io.IOException
import java.nio.file.Path

@Inject
class FeatureSpecPreparationWriter(
  private val decompositionManifestValidator: DecompositionManifestValidator,
  private val fileStore: DecompositionManifestStore,
  private val decompositionManifestWriter: DecompositionManifestWriter,
) {
  fun write(
    repoRoot: Path,
    request: FeatureSpecWriteRequest,
    existingParentSpecPath: Path? = null,
  ): FeatureSpecWriteResult {
    val issueKey = request.decision.issueKey.trim()
    val featureName = normalizeFeatureName(request.featureName)
    if (featureName.isBlank()) {
      invalidRequest(DecompositionManifestPayloadKeys.FEATURE_NAME, "feature name is required.")
    }
    val specDirectory = repoRoot.resolve(".feature-specs/$issueKey-$featureName")
    val parentSpecPath = existingParentSpecPath ?: specDirectory.resolve("spec.md")
    if (existingParentSpecPath != null) {
      requireAcceptanceList("parent_spec.acceptance_criteria", authoredLines("parent_spec", parentSpecPath))
    }
    val parentSpecRelativePath = repoRelativePath(repoRoot, parentSpecPath)
    return writePreparedFeature(
      repoRoot = repoRoot,
      request = request,
      parentSpecPath = parentSpecPath,
      parentSpecRelativePath = parentSpecRelativePath,
      preserveParentSpec = existingParentSpecPath != null,
    )
  }

  fun verifyAuthored(
    repoRoot: Path,
    parentSpecPath: Path,
  ): FeatureSpecWriteResult {
    val parentRelativePath = repoRelativePath(repoRoot, parentSpecPath)
    requireAcceptanceList("parent_spec.acceptance_criteria", authoredLines("parent_spec", parentSpecPath))
    val manifestPath = parentSpecPath.resolveSibling(AUTHORED_MANIFEST_FILE)
    authoredLines("decomposition_manifest", manifestPath)
    val loaded = loadPreparedManifest(manifestPath)
    if (loaded.manifest.parentSpecPath != parentRelativePath) {
      invalidRequest("parent_spec_path", "manifest parent_spec_path must name the authored parent spec.")
    }
    val subtasks = loaded.manifest.subtasks
    validateAuthoredOrder(subtasks)
    subtasks.forEachIndexed { index, subtask ->
      val subtaskPath = repoRoot.resolve(subtask.specPath).normalize()
      if (!subtaskPath.startsWith(parentSpecPath.parent)) {
        invalidRequest("subtasks[$index].spec_path", "subtask spec must live in the authored bundle.")
      }
      requireAcceptanceList("subtasks[$index].acceptance_criteria", authoredLines("subtasks[$index]", subtaskPath))
    }
    return FeatureSpecWriteResult(
      mode = FeatureSpecPreparationMode.DECOMPOSED,
      parentSpecPath = parentRelativePath,
      featureImplementPath = parentRelativePath,
      decompositionManifestPath = repoRelativePath(repoRoot, manifestPath),
      subtaskSpecPaths = subtasks.map { repoRelativePath(repoRoot, repoRoot.resolve(it.specPath).normalize()) },
      repairEvidence = listOfNotNull(loaded.repairEvidence),
    )
  }

  fun listTree(directory: Path): List<Path> = fileStore.listTree(directory)

  private fun authoredLines(
    fieldPath: String,
    path: Path,
  ): List<String> {
    if (!fileStore.isRegularFile(path)) invalidRequest(fieldPath, "authored file '${path.fileName}' is missing.")
    return try {
      fileStore.readText(path).lines()
    } catch (error: IOException) {
      invalidRequest(fieldPath, "authored file '${path.fileName}' is unreadable: ${error.message}")
    }
  }

  private fun validateAuthoredOrder(subtasks: List<DecompositionSubtask>) {
    if (subtasks.isEmpty()) {
      invalidRequest(
        DecompositionPlanningPayloadKeys.SUBTASKS,
        "prepared features require at least one ordered subtask.",
      )
    }
    val earlierIds = mutableSetOf<Int>()
    var previousId = Int.MIN_VALUE
    subtasks.forEachIndexed { index, subtask ->
      if (subtask.id <= 0) {
        invalidRequest("subtasks[$index].id", "id must be a positive integer.")
      }
      if (subtask.id <= previousId) {
        invalidRequest("subtasks[$index].id", "subtask ids must be unique and in ascending dependency order.")
      }
      subtask.dependencies.forEachIndexed { dependencyIndex, dependency ->
        if (dependency.subtaskId !in earlierIds) {
          invalidRequest(
            "subtasks[$index].depends_on[$dependencyIndex]",
            "depends_on must reference an existing earlier subtask id.",
          )
        }
      }
      earlierIds += subtask.id
      previousId = subtask.id
    }
  }

  private fun writePreparedFeature(
    repoRoot: Path,
    request: FeatureSpecWriteRequest,
    parentSpecPath: Path,
    parentSpecRelativePath: String,
    preserveParentSpec: Boolean,
  ): FeatureSpecWriteResult {
    validateSubtasks(request.subtasks, request.specSource)
    val parentSpecText =
      renderParentSpec(
        ParentSpecRenderInput(
          issueKey = request.decision.issueKey,
          featureName = normalizeFeatureName(request.featureName),
          mode = request.decision.mode,
          intendedOutcome = request.decision.intendedOutcome,
          acceptanceCriteria = request.decision.acceptanceCriteria,
          constraints = request.decision.constraints,
          nonGoals = request.decision.nonGoals,
          overview = request.parentSpecOverview,
          validationStrategy = request.validationStrategy,
        ),
      )
    val subtaskRecords = prepareSubtasks(repoRoot, request, parentSpecPath, parentSpecRelativePath)
    val planningResult = planningResult(parentSpecPath, subtaskRecords)
    val preparedManifest =
      decompositionManifestWriter.prepare(
        request =
          DecompositionManifestWriteRequest(
            repoRoot = repoRoot,
            parentSpecPath = parentSpecPath,
            planningResult = planningResult,
            baseBranch = request.baseBranch.ifBlank { "main" },
            featureBranch = request.featureBranch.takeIf(String::isNotBlank) ?: defaultFeatureBranch(parentSpecPath),
            specSource = request.specSource,
          ),
        validator = decompositionManifestValidator,
        fileStore = fileStore,
      )
    val loaded =
      fileStore.writeBundleAtomically(
        writes =
          buildList {
            if (!preserveParentSpec) add(parentSpecPath to parentSpecText)
            subtaskRecords.forEach { add(it.path to it.text) }
            add(preparedManifest.manifestPath to preparedManifest.yaml)
          },
      ) {
        loadPreparedManifest(preparedManifest.manifestPath)
      }
    return FeatureSpecWriteResult(
      mode = request.decision.mode,
      parentSpecPath = parentSpecRelativePath,
      featureImplementPath = parentSpecRelativePath,
      decompositionManifestPath = repoRelativePath(repoRoot, preparedManifest.manifestPath),
      subtaskSpecPaths = subtaskRecords.map(PreparedSubtask::relativePath),
      repairEvidence = preparedManifest.repairEvidence + listOfNotNull(loaded.repairEvidence),
    )
  }

  private fun loadPreparedManifest(manifestPath: Path) =
    loadValidatedDecompositionManifestPersistingRepair(manifestPath, fileStore, decompositionManifestValidator)

  private fun planningResult(
    parentSpecPath: Path,
    subtaskRecords: List<PreparedSubtask>,
  ): DecompositionPlanningResult =
    decompositionPlanningResult(
      parentSpecPath = parentSpecPath.toString(),
      subtasks =
        subtaskRecords.map { subtask ->
          decompositionPlanningSubtask(
            id = subtask.definition.id,
            name = subtask.definition.name,
            specPath = subtask.path.toString(),
            options =
              DecompositionPlanningSubtaskOptions(
                dependsOn = subtask.definition.dependsOn,
                linearIssueId = subtask.definition.linearIssueId,
                scope = subtask.definition.scope,
              ),
          )
        },
      options =
        DecompositionPlanningResultOptions(
          recommendedFirstSubtaskId = subtaskRecords.first().definition.id,
        ),
    )

  private fun prepareSubtasks(
    repoRoot: Path,
    request: FeatureSpecWriteRequest,
    parentSpecPath: Path,
    parentSpecRelativePath: String,
  ): List<PreparedSubtask> =
    request.subtasks.map { subtask ->
      val subtaskPath = parentSpecPath.parent.resolve(subtaskFileName(subtask))
      val subtaskRelativePath = repoRelativePath(repoRoot, subtaskPath)
      PreparedSubtask(
        path = subtaskPath,
        relativePath = subtaskRelativePath,
        definition = subtask,
        text =
          renderSubtaskSpec(
            issueKey = request.decision.issueKey,
            subtask = subtask,
            parentSpecPath = parentSpecRelativePath,
            subtaskPath = subtaskRelativePath,
          ),
      )
    }

  private fun validateSubtasks(
    subtasks: List<FeatureSpecSubtaskPreparation>,
    specSource: SpecSource,
  ) {
    if (subtasks.isEmpty()) {
      invalidRequest(
        DecompositionPlanningPayloadKeys.SUBTASKS,
        "prepared features require at least one ordered subtask.",
      )
    }
    val ids = mutableSetOf<Int>()
    var previousId = Int.MIN_VALUE
    subtasks.forEachIndexed { index, subtask ->
      if (subtask.id <= 0) {
        invalidRequest("subtasks[$index].id", "id must be a positive integer.")
      }
      if (!ids.add(subtask.id)) {
        invalidRequest("subtasks[$index].id", "id must be unique.")
      }
      if (subtask.id <= previousId) {
        invalidRequest("subtasks[$index].id", "subtask ids must be in ascending dependency order.")
      }
      previousId = subtask.id
      if (subtask.scope.isBlank()) {
        invalidRequest("subtasks[$index].scope", "scope is required.")
      }
      if (subtask.acceptanceCriteria.isEmpty()) {
        invalidRequest("subtasks[$index].acceptance_criteria", "at least one acceptance criterion is required.")
      }
      if (subtask.validationStrategy.isBlank()) {
        invalidRequest("subtasks[$index].validation_strategy", "validation strategy is required.")
      }
      if (subtask.nextPath.isBlank()) {
        invalidRequest("subtasks[$index].next_path", "next path is required.")
      }
      if (subtask.linearIssueId.isNullOrBlank() && specSource == SpecSource.LINEAR) {
        invalidRequest("subtasks[$index].linear_issue_id", "linear source requires a Linear issue id.")
      }
      subtask.dependsOn.forEachIndexed { dependencyIndex, dependencyId ->
        if (dependencyId >= subtask.id || dependencyId !in ids) {
          invalidRequest(
            "subtasks[$index].depends_on[$dependencyIndex]",
            "depends_on must reference an existing earlier subtask id.",
          )
        }
      }
    }
  }
}

private const val AUTHORED_MANIFEST_FILE = "decomposition-manifest.yaml"

private val ACCEPTANCE_ITEM = Regex("""^\s*(?:\d+[.)]|[-*])\s+\S.*""")

private fun requireAcceptanceList(
  fieldPath: String,
  lines: List<String>,
) {
  val heading = lines.indexOfFirst { it.trim().startsWith("## Acceptance Criteria", ignoreCase = true) }
  val criteria =
    if (heading < 0) {
      0
    } else {
      lines.drop(heading + 1).takeWhile { !it.startsWith("#") }.count(ACCEPTANCE_ITEM::matches)
    }
  if (criteria == 0) invalidRequest(fieldPath, "at least one acceptance criterion is required.")
}

private data class PreparedSubtask(
  val path: Path,
  val relativePath: String,
  val definition: FeatureSpecSubtaskPreparation,
  val text: String,
)

private data class ParentSpecRenderInput(
  val issueKey: String,
  val featureName: String,
  val mode: FeatureSpecPreparationMode,
  val intendedOutcome: String,
  val acceptanceCriteria: List<String>,
  val constraints: List<String>,
  val nonGoals: List<String>,
  val overview: String,
  val validationStrategy: String,
)

private fun renderParentSpec(input: ParentSpecRenderInput): String =
  buildString {
    appendLine("# ${input.issueKey} - ${input.featureName}")
    appendLine()
    appendLine("## Mode")
    appendLine()
    appendLine(input.mode.wireValue)
    appendLine()
    appendLine("## Intended Outcome")
    appendLine()
    appendLine(input.intendedOutcome.ifBlank { "(none provided)" })
    appendLine()
    appendLine("## Overview")
    appendLine()
    appendLine(input.overview.ifBlank { "(none provided)" })
    appendLine()
    appendLine("## Acceptance Criteria")
    appendLine()
    input.acceptanceCriteria.forEachIndexed { index, criterion ->
      appendLine("${index + 1}. $criterion")
    }
    appendLine()
    appendLine("## Constraints")
    appendLine()
    input.constraints.forEach { constraint ->
      appendLine("- $constraint")
    }
    appendLine()
    appendLine("## Non-Goals")
    appendLine()
    if (input.nonGoals.isEmpty()) {
      appendLine("- None")
    } else {
      input.nonGoals.forEach { nonGoal -> appendLine("- $nonGoal") }
    }
    appendLine()
    appendLine("## Validation Strategy")
    appendLine()
    appendLine(input.validationStrategy.ifBlank { "skill-bill phase validation" })
  }

private fun renderSubtaskSpec(
  issueKey: String,
  subtask: FeatureSpecSubtaskPreparation,
  parentSpecPath: String,
  subtaskPath: String,
): String =
  buildString {
    appendLine("# $issueKey Subtask ${subtask.id} - ${subtask.name}")
    appendLine()
    appendLine("Parent spec: [$parentSpecPath](./spec.md)")
    appendLine("Issue key: $issueKey")
    appendLine()
    appendLine("## Scope")
    appendLine()
    appendLine(subtask.scope)
    appendLine()
    appendLine("## Acceptance Criteria")
    appendLine()
    subtask.acceptanceCriteria.forEachIndexed { index, criterion ->
      appendLine("${index + 1}. $criterion")
    }
    appendLine()
    appendLine("## Non-Goals")
    appendLine()
    if (subtask.nonGoals.isEmpty()) {
      appendLine("- None")
    } else {
      subtask.nonGoals.forEach { nonGoal -> appendLine("- $nonGoal") }
    }
    appendLine()
    appendLine("## Dependency Notes")
    appendLine()
    if (subtask.dependsOn.isEmpty()) {
      appendLine("Depends on: none")
    } else {
      appendLine("Depends on: ${subtask.dependsOn.joinToString(", ")}")
    }
    appendLine(subtask.dependencyNotes.ifBlank { "Dependency order is captured by depends_on in the manifest." })
    appendLine()
    appendLine("## Validation Strategy")
    appendLine()
    appendLine(subtask.validationStrategy)
    appendLine()
    appendLine("## Next Path")
    appendLine()
    appendLine(subtask.nextPath)
    appendLine()
    appendLine("## Spec Path")
    appendLine()
    appendLine(subtaskPath)
  }

private fun normalizeFeatureName(raw: String): String =
  raw
    .trim()
    .lowercase()
    .replace(Regex("[^a-z0-9]+"), "-")
    .trim('-')
    .ifBlank { "feature" }

private fun subtaskFileName(subtask: FeatureSpecSubtaskPreparation): String =
  "spec_subtask_${subtask.id}_${normalizeFeatureName(subtask.name)}.md"

private fun invalidRequest(
  fieldPath: String,
  reason: String,
): Nothing = throw InvalidFeatureSpecPreparationRequestError(fieldPath = fieldPath, reason = reason)
