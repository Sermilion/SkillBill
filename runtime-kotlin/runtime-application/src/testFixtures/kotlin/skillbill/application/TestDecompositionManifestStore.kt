package skillbill.application

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.application.decomposition.DECOMPOSITION_MANIFEST_FILENAME
import skillbill.application.decomposition.model.DecompositionManifestRuntimeUpdate
import skillbill.application.decomposition.model.DecompositionManifestWorkflowProjectionInput
import skillbill.application.decomposition.model.DecompositionManifestWriteRequest
import skillbill.contracts.JsonCodec
import skillbill.contracts.decomposition.DecompositionPlanningResult
import skillbill.contracts.projectstore.LegacyProjectStatePaths
import skillbill.contracts.workflow.payload.WorkflowArtifactKeys
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.projectstore.model.specBundleRoots
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.workflow.decomposition.model.DecompositionManifestWireMap
import skillbill.workflow.decomposition.runtime.model.DecompositionManifestProjectionOutcome
import skillbill.workflow.decomposition.runtime.model.DecompositionManifestWriteResult
import skillbill.workflow.engine.model.DurableWorkflowArtifacts
import skillbill.workflow.engine.model.WorkflowArtifactPatch
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Instant
import skillbill.application.decomposition.loadDecompositionManifest as loadManifestFromStore

object TestDecompositionManifestStore : DecompositionManifestStore {
  override fun readText(path: Path): String = Files.readString(path)

  override fun isRegularFile(path: Path): Boolean = Files.isRegularFile(path)

  override fun findDecompositionManifestFiles(repoRoot: Path): List<Path> =
    manifestsUnder(repoRoot.resolve(LegacyProjectStatePaths.FEATURE_SPECS_DIRECTORY))

  override fun listDirectChildDirectories(directory: Path): List<Path> {
    if (!Files.isDirectory(directory)) return emptyList()
    return Files.list(directory).use { paths ->
      paths.filter { path -> Files.isDirectory(path) }.toList()
    }
  }

  override fun listTree(directory: Path): List<Path> = Files.walk(directory).use { paths -> paths.toList() }

  override fun lastModified(path: Path): Instant = Files.getLastModifiedTime(path).toInstant()

  override fun deleteIfExists(target: Path) {
    Files.deleteIfExists(target)
  }

  override fun writeTextAtomically(
    target: Path,
    content: String,
  ) {
    Files.createDirectories(target.parent)
    val temp = Files.createTempFile(target.parent, "${target.fileName}.", ".tmp")
    Files.writeString(temp, content)
    try {
      Files.move(temp, target, REPLACE_EXISTING, ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
      Files.move(temp, target, REPLACE_EXISTING)
    }
  }

  override fun encodeManifestYaml(wireMap: DecompositionManifestWireMap): String =
    YAMLMapper().writeValueAsString(wireMap)

  override fun readTextWithoutRecovery(path: Path): String = readText(path)

  override fun isRegularFileWithoutRecovery(path: Path): Boolean = isRegularFile(path)

  override fun findDecompositionManifestFilesWithoutRecovery(repoRoot: Path): List<Path> =
    findDecompositionManifestFiles(repoRoot)

  override fun <T> writeBundleAtomically(
    writes: List<Pair<Path, String>>,
    verify: () -> T,
  ): T {
    val snapshots =
      writes.distinctBy { (path, _) -> path.toAbsolutePath().normalize() }.map { (path, _) ->
        val normalized = path.toAbsolutePath().normalize()
        val existed = Files.isRegularFile(normalized)
        normalized to (existed to if (existed) Files.readString(normalized) else null)
      }
    return runCatching {
      writes.forEach { (path, content) -> writeTextAtomically(path, content) }
      verify()
    }.getOrElse { failure ->
      snapshots.asReversed().forEach { (path, snapshot) ->
        runCatching {
          if (snapshot.first) {
            writeTextAtomically(path, requireNotNull(snapshot.second))
          } else {
            deleteIfExists(path)
          }
        }.onFailure(failure::addSuppressed)
      }
      throw failure
    }
  }
}

fun storeAwareTestDecompositionManifestStore(projectStore: ProjectStorePort): DecompositionManifestStore =
  object : DecompositionManifestStore by TestDecompositionManifestStore {
    override fun findDecompositionManifestFiles(repoRoot: Path): List<Path> =
      projectStore.resolve(repoRoot).specBundleRoots(repoRoot).flatMap(::manifestsUnder)

    override fun findDecompositionManifestFilesWithoutRecovery(repoRoot: Path): List<Path> =
      findDecompositionManifestFiles(repoRoot)
  }

private fun manifestsUnder(specsRoot: Path): List<Path> {
  if (!Files.isDirectory(specsRoot)) return emptyList()
  return Files.walk(specsRoot).use { paths ->
    paths
      .filter { path -> Files.isRegularFile(path) && path.fileName.toString() == DECOMPOSITION_MANIFEST_FILENAME }
      .toList()
  }
}

private fun decodeArtifacts(
  raw: String,
  typedArtifacts: Map<String, Any?>,
): DurableWorkflowArtifacts =
  DurableWorkflowArtifacts.fromMap(
    requireNotNull(JsonCodec.anyToStringAnyMap(JsonCodec.parseValue(raw))) + typedArtifacts,
  )

fun loadDecompositionManifest(path: Path) =
  loadManifestFromStore(
    path,
    TestDecompositionManifestStore,
    testDecompositionManifestValidator,
  )

fun writeIfDecomposed(request: DecompositionManifestWriteRequest): DecompositionManifestWriteResult? =
  testDecompositionManifestWriter.writeIfDecomposed(
    request,
    testDecompositionManifestValidator,
    TestDecompositionManifestStore,
  )

fun writeFromWorkflowUpdate(
  repoRoot: Path,
  existingArtifactsJson: String,
  artifactsPatch: Map<String, Any?>?,
  runtimeUpdate: DecompositionManifestRuntimeUpdate? = null,
  typedArtifacts: Map<String, Any?> = emptyMap(),
): DecompositionManifestWriteResult? =
  testDecompositionManifestWriter.writeFromWorkflowUpdate(
    DecompositionManifestWorkflowProjectionInput(
      repoRoot = repoRoot,
      existingArtifacts = decodeArtifacts(existingArtifactsJson, typedArtifacts),
      validator = testDecompositionManifestValidator,
      planningResult =
        artifactsPatch?.get(WorkflowArtifactKeys.PLAN)
          ?.let(JsonCodec::anyToStringAnyMap)
          ?.let { DecompositionPlanningResult.fromWireMap(it, "test.artifacts_patch.plan") },
      artifactsPatch = WorkflowArtifactPatch.from(artifactsPatch),
      runtimeUpdate = runtimeUpdate ?: DecompositionManifestRuntimeUpdate(),
      fileStore = TestDecompositionManifestStore,
    ),
  )

fun writeProjectionFromWorkflowState(
  repoRoot: Path,
  artifactsJson: String,
  typedArtifacts: Map<String, Any?> = emptyMap(),
): DecompositionManifestWriteResult? =
  when (
    val outcome =
      testDecompositionManifestWriter.writeProjectionFromWorkflowState(
        repoRoot = repoRoot,
        artifacts = decodeArtifacts(artifactsJson, typedArtifacts),
        validator = testDecompositionManifestValidator,
        fileStore = TestDecompositionManifestStore,
      )
  ) {
    is DecompositionManifestProjectionOutcome.Written -> outcome.result
    DecompositionManifestProjectionOutcome.Absent,
    is DecompositionManifestProjectionOutcome.Failed,
    -> null
  }
