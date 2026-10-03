package skillbill.application.decomposition

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.model.DecompositionManifestRuntimeUpdate
import skillbill.application.decomposition.model.DecompositionManifestWorkflowProjectionInput
import skillbill.application.decomposition.model.DecompositionManifestWriteRequest
import skillbill.application.decomposition.model.DecompositionPlanManifestInput
import skillbill.application.decomposition.model.PreparedDecompositionManifestWrite
import skillbill.contracts.decomposition.DecompositionPlanningPayloadKeys
import skillbill.contracts.issuekey.issueAndFeature
import skillbill.error.core.failureCodeLabel
import skillbill.error.shellcontent.InvalidDecompositionManifestSchemaError
import skillbill.model.toPath
import skillbill.ports.repository.toFileLocation
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.workflow.decomposition.model.CurrentSubtaskIntent
import skillbill.workflow.decomposition.model.DecompositionExecutionModel
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionManifestPlan
import skillbill.workflow.decomposition.runtime.decompositionRuntime
import skillbill.workflow.decomposition.runtime.invalidManifest
import skillbill.workflow.decomposition.runtime.model.DecompositionManifestProjectionOutcome
import skillbill.workflow.decomposition.runtime.model.DecompositionManifestWriteResult
import skillbill.workflow.decomposition.withPreservedRuntimeState
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.model.DecompositionSubtaskAction
import java.io.IOException
import java.nio.file.Path

@Inject
class DecompositionManifestWriter {
  fun writeFromWorkflowUpdate(input: DecompositionManifestWorkflowProjectionInput): DecompositionManifestWriteResult? {
    val manifest = manifestFromWorkflowUpdate(input) ?: return null
    return when (
      val outcome =
        writeProjectionOutcome(
          WriteProjectionOutcomeArgs(
            repoRoot = input.repoRoot,
            manifest = manifest,
            validator = input.validator,
            manifestPath = manifest.manifestPath(input.repoRoot),
            fileStore = input.fileStore,
            operation = DecompositionManifestProjectionOperations.WRITE_PROJECTION,
          ),
        )
    ) {
      is DecompositionManifestProjectionOutcome.Written -> outcome.result
      is DecompositionManifestProjectionOutcome.Absent -> null
      is DecompositionManifestProjectionOutcome.Failed -> throw IOException(
        "Decomposition manifest projection failed during ${outcome.operation} at ${outcome.targetPath}.",
      )
    }
  }

  fun manifestFromWorkflowUpdate(input: DecompositionManifestWorkflowProjectionInput): DecompositionManifest? {
    val existingArtifacts = input.existingArtifacts
    val update =
      input.runtimeUpdate.copy(
        planningResult = input.planningResult,
        artifactsPatch = input.artifactsPatch,
        existingArtifacts = existingArtifacts,
      )
    val planningResult = input.planningResult
    return if (planningResult != null && planningResult.isDecomposeMode()) {
      manifestFromDecompositionPlan(
        DecompositionPlanManifestInput(
          repoRoot = input.repoRoot,
          plan = planningResult,
          artifactsPatch = input.artifactsPatch,
          existingArtifacts = existingArtifacts,
          validator = input.validator,
          fileStore = input.fileStore,
        ),
      )
    } else {
      updatedExistingManifest(input.repoRoot, update, input.validator, input.fileStore)
    }
  }

  fun maybeWriteFromWorkflowUpdate(input: DecompositionManifestWorkflowProjectionInput): Path? =
    writeFromWorkflowUpdate(input)?.manifestPath?.toPath()

  fun writeProjectionFromWorkflowState(
    repoRoot: Path,
    artifacts: DurableWorkflowArtifacts,
    validator: DecompositionManifestValidator,
    fileStore: DecompositionManifestStore,
  ): DecompositionManifestProjectionOutcome {
    val runtime = artifacts.decompositionRuntime() ?: return DecompositionManifestProjectionOutcome.Absent
    return writeProjectionOutcome(
      WriteProjectionOutcomeArgs(
        repoRoot = repoRoot,
        manifest = runtime,
        validator = validator,
        manifestPath = runtime.manifestPath(repoRoot),
        fileStore = fileStore,
        operation = DecompositionManifestProjectionOperations.WRITE_PROJECTION_FROM_WORKFLOW_STATE,
      ),
    )
  }

  fun writeIfDecomposed(
    request: DecompositionManifestWriteRequest,
    validator: DecompositionManifestValidator,
    fileStore: DecompositionManifestStore,
  ): DecompositionManifestWriteResult? {
    if (!request.planningResult.isDecomposeMode()) {
      return null
    }
    return write(request, validator, fileStore = fileStore)
  }

  fun write(
    request: DecompositionManifestWriteRequest,
    validator: DecompositionManifestValidator,
    runtimeUpdate: DecompositionManifestRuntimeUpdate? = null,
    fileStore: DecompositionManifestStore,
  ): DecompositionManifestWriteResult {
    val prepared = prepare(request, validator, runtimeUpdate, fileStore)
    writeDecompositionManifestText(prepared.manifestPath, prepared.yaml, fileStore)
    val loaded = loadValidatedDecompositionManifest(prepared.manifestPath, fileStore, validator)
    return DecompositionManifestWriteResult(
      manifestPath = prepared.manifestPath.toFileLocation(),
      manifest = loaded.manifest,
      repairEvidence = prepared.repairEvidence + listOfNotNull(loaded.repairEvidence),
    )
  }

  fun prepare(
    request: DecompositionManifestWriteRequest,
    validator: DecompositionManifestValidator,
    runtimeUpdate: DecompositionManifestRuntimeUpdate? = null,
    fileStore: DecompositionManifestStore,
  ): PreparedDecompositionManifestWrite {
    assertParentSpecIsNotDecomposedSubtask(request.repoRoot, request.parentSpecPath, validator, fileStore)
    val manifestPath = request.manifestPath()
    val existingLoad = loadValidatedDecompositionManifestOrNull(manifestPath, fileStore, validator)
    val existing = existingLoad?.manifest
    val manifest =
      request.toManifest()
        .assertExecutionModelCanReplace(existing, manifestPath)
        .withPreservedRuntimeState(existing)
        .let { candidate ->
          runtimeUpdate?.let { candidate.withRuntimeUpdate(request.repoRoot, it) } ?: candidate
        }
    val projectedManifest = manifest.gitTrackedProjection()
    val encoded = encodeValidatedDecompositionManifestYaml(projectedManifest, validator, fileStore)
    return PreparedDecompositionManifestWrite(
      manifestPath = manifestPath,
      manifest = projectedManifest,
      yaml = encoded.yamlText,
      repairEvidence = listOfNotNull(existingLoad?.repairEvidence, encoded.repairEvidence),
    )
  }

  private fun manifestFromDecompositionPlan(input: DecompositionPlanManifestInput): DecompositionManifest {
    val parentSpecPath = Path.of(parentSpecPath(input.plan))
    assertParentSpecIsNotDecomposedSubtask(input.repoRoot, parentSpecPath, input.validator, input.fileStore)
    val branchName =
      branchName(input.artifactsPatch?.get(DecompositionPlanningPayloadKeys.BRANCH))
        .ifBlank { branchName(input.existingArtifacts[DecompositionPlanningPayloadKeys.BRANCH]) }
    val executionModel = executionModel(input.plan)
    val request =
      DecompositionManifestWriteRequest(
        repoRoot = input.repoRoot,
        parentSpecPath = parentSpecPath,
        planningResult = input.plan,
        baseBranch = baseBranch(input.plan, parentSpecPath.toString()),
        featureBranch =
          when (executionModel) {
            DecompositionExecutionModel.SAME_BRANCH_COMMIT_PER_SUBTASK ->
              branchName.ifBlank { defaultFeatureBranch(parentSpecPath) }
            DecompositionExecutionModel.STACKED_BRANCHES -> null
          },
        executionModel = executionModel,
        stackBranches = parseStackBranches(input.plan),
        specSource = specSource(input.plan),
      )
    val manifestPath = request.manifestPath()
    val existing =
      runtimeManifestFromArtifacts(input.existingArtifacts)
        ?: loadManifestOrNull(manifestPath, input.validator, input.fileStore)
    return request.toManifest()
      .assertExecutionModelCanReplace(existing, manifestPath)
      .withPreservedRuntimeState(existing)
  }

  private fun updatedExistingManifest(
    repoRoot: Path,
    runtimeUpdate: DecompositionManifestRuntimeUpdate,
    validator: DecompositionManifestValidator,
    fileStore: DecompositionManifestStore,
  ): DecompositionManifest? {
    val artifacts =
      DurableWorkflowArtifacts.fromMap(
        LinkedHashMap(runtimeUpdate.existingArtifacts).apply {
          runtimeUpdate.artifactsPatch?.let(::putAll)
        },
      )
    val runtime = runtimeManifestFromArtifacts(artifacts)
    val manifestPath =
      manifestPathFromArtifacts(
        repoRoot = repoRoot,
        artifactsPatch = runtimeUpdate.artifactsPatch,
        existingArtifacts = runtimeUpdate.existingArtifacts,
        planningResult = runtimeUpdate.planningResult,
      ) ?: runtime?.manifestPath(repoRoot)
    val existing = runtime ?: manifestPath?.let { loadManifestOrNull(it, validator, fileStore) } ?: return null
    return existing.withRuntimeUpdate(repoRoot, runtimeUpdate)
  }

  private fun DecompositionManifestWriteRequest.toManifest(): DecompositionManifest {
    val subtasks = parseSubtasks(planningResult, parentSpecPath.toString())
    val currentId =
      currentSubtaskId
        ?: planningResult.currentSubtaskIdOrNull()
        ?: subtasks.first().id
    val currentSubtask =
      subtasks.firstOrNull { it.id == currentId }
        ?: invalidManifest(
          parentSpecPath.toString(),
          "current subtask id '$currentId' does not reference a planned subtask.",
        )
    val parentDirectory = resolvedParentSpecPath(repoRoot, parentSpecPath).parent
    val manifestDirectory = decompositionManifestDirectory(repoRoot, parentSpecPath, subtasks.map { it.specPath })
    val (issueKey, parsedFeatureName) = issueAndFeature(parentDirectory.fileName.toString())
    val featureName =
      if (manifestDirectory != parentDirectory) {
        manifestDirectory.fileName.toString()
      } else {
        parsedFeatureName
      }
    val typedPlan =
      DecompositionManifestPlan(
        parentSpecPath = repoRelativePath(repoRoot, parentSpecPath),
        baseBranch = baseBranch,
        featureBranch = featureBranch,
        specSource = specSource,
        executionModel = executionModel,
        stackBranches = stackBranches,
        currentSubtaskId = currentSubtask.id,
        subtasks = subtasks,
      )
    return DecompositionManifest(
      issueKey = issueKey,
      featureName = featureName,
      parentSpecPath = typedPlan.parentSpecPath,
      specSource = typedPlan.specSource,
      executionModel = typedPlan.executionModel,
      baseBranch = typedPlan.baseBranch,
      featureBranch = typedPlan.featureBranch,
      stackBranches = typedPlan.stackBranches,
      currentSubtaskIntent =
        CurrentSubtaskIntent(
          subtaskId = typedPlan.currentSubtaskId,
          action = DecompositionSubtaskAction.START.wireValue,
        ),
      subtasks = typedPlan.subtasks,
    )
  }
}

private fun assertParentSpecIsNotDecomposedSubtask(
  repoRoot: Path,
  parentSpecPath: Path,
  validator: DecompositionManifestValidator,
  fileStore: DecompositionManifestStore,
) {
  val normalizedParentSpec = resolvedParentSpecPath(repoRoot, parentSpecPath).normalize()
  val parentSpecLabel = repoRelativePath(repoRoot, parentSpecPath)
  val referringManifests =
    fileStore.findDecompositionManifestFiles(repoRoot)
      .filterNot { manifestPath -> archivedDecompositionManifest(repoRoot, manifestPath) }
      .filter { manifestPath ->
        normalizedParentSpec.startsWith(resolvedParentSpecPath(repoRoot, manifestPath).normalize().parent)
      }
      .mapNotNull { manifestPath ->
        val manifest =
          try {
            loadDecompositionManifest(manifestPath, fileStore, validator)
          } catch (error: IOException) {
            invalidParentSpecManifestLoad(parentSpecPath, manifestPath, parentSpecLabel, error)
          } catch (error: InvalidDecompositionManifestSchemaError) {
            invalidParentSpecManifestLoad(parentSpecPath, manifestPath, parentSpecLabel, error)
          }
        val matchingSubtask =
          manifest.subtasks.firstOrNull { subtask ->
            resolvedParentSpecPath(repoRoot, Path.of(subtask.specPath)).normalize() == normalizedParentSpec
          } ?: return@mapNotNull null
        manifestPath to matchingSubtask.id
      }
  if (referringManifests.isNotEmpty()) {
    val references =
      referringManifests.joinToString(", ") { (manifestPath, subtaskId) ->
        "$manifestPath (subtask_id=$subtaskId)"
      }
    invalidManifest(
      parentSpecPath.toString(),
      "parent_spec_path '$parentSpecLabel' is already a decomposed subtask in $references; " +
        "nested decomposition of subtask specs is not supported.",
    )
  }
}

private fun invalidParentSpecManifestLoad(
  parentSpecPath: Path,
  manifestPath: Path,
  parentSpecLabel: String,
  error: Exception,
): Nothing {
  val detail =
    error.message?.takeIf(String::isNotBlank) ?: error.failureCodeLabel() ?: error::class.simpleName.orEmpty()
  invalidManifest(
    parentSpecPath.toString(),
    "failed to load decomposition manifest '$manifestPath' while validating parent_spec_path " +
      "'$parentSpecLabel': $detail",
  )
}

internal fun DecompositionManifestWriteRequest.manifestPath(): Path =
  decompositionManifestPath(
    repoRoot,
    parentSpecPath,
    parseSubtasks(planningResult, parentSpecPath.toString()).map {
      it.specPath
    },
  )

private fun DecompositionManifest.manifestPath(repoRoot: Path): Path =
  decompositionManifestPath(repoRoot, Path.of(parentSpecPath), subtasks.map { it.specPath })

private fun runtimeManifestFromArtifacts(artifacts: DurableWorkflowArtifacts): DecompositionManifest? =
  artifacts.decompositionRuntime()

private fun writeProjection(
  repoRoot: Path,
  manifest: DecompositionManifest,
  validator: DecompositionManifestValidator,
  manifestPath: Path = manifest.manifestPath(repoRoot),
  fileStore: DecompositionManifestStore,
): DecompositionManifestWriteResult {
  val encoded =
    encodeValidatedDecompositionManifestYaml(
      manifest.gitTrackedProjection(),
      validator,
      fileStore,
      sourceLabel = "decomposition_runtime",
    )
  writeDecompositionManifestText(manifestPath, encoded.yamlText, fileStore)
  val loaded = loadValidatedDecompositionManifest(manifestPath, fileStore, validator)
  return DecompositionManifestWriteResult(
    manifestPath = manifestPath.toFileLocation(),
    manifest = loaded.manifest,
    repairEvidence = listOfNotNull(encoded.repairEvidence, loaded.repairEvidence),
  )
}

private data class WriteProjectionOutcomeArgs(
  val repoRoot: Path,
  val manifest: DecompositionManifest,
  val validator: DecompositionManifestValidator,
  val manifestPath: Path,
  val fileStore: DecompositionManifestStore,
  val operation: String,
)

private fun writeProjectionOutcome(args: WriteProjectionOutcomeArgs): DecompositionManifestProjectionOutcome =
  try {
    val repoRoot = args.repoRoot
    val manifest = args.manifest
    val validator = args.validator
    val manifestPath = args.manifestPath
    val fileStore = args.fileStore
    val operation = args.operation
    DecompositionManifestProjectionOutcome.Written(
      writeProjection(repoRoot, manifest, validator, manifestPath, fileStore),
    )
  } catch (_: IOException) {
    DecompositionManifestProjectionOutcome.Failed(
      operation = args.operation,
      targetPath = args.manifestPath.toString(),
    )
  }

private fun DecompositionManifest.gitTrackedProjection(): DecompositionManifest =
  copy(subtasks = subtasks.map { subtask -> subtask.copy(commitSha = null) })
