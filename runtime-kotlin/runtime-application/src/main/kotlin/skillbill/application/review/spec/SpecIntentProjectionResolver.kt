package skillbill.application.review.spec

import me.tatarka.inject.annotations.Inject
import skillbill.application.decomposition.branchName
import skillbill.application.decomposition.parentSpecPath
import skillbill.application.decomposition.repoRelativePath
import skillbill.application.rethrowIfCooperativeCancellationOrInterruption
import skillbill.contracts.issuekey.TRACKER_STYLE_ISSUE_KEY_PATTERN
import skillbill.error.shellcontent.InvalidDecompositionManifestSchemaError
import skillbill.error.shellcontent.UnreadableSpecIntentProjectionError
import skillbill.model.toPath
import skillbill.ports.workflow.decomposition.DecompositionManifestStore
import skillbill.ports.workflow.decomposition.DecompositionManifestValidator
import skillbill.review.context.model.execution.SpecIntentAbsenceReason
import skillbill.review.context.model.execution.SpecIntentDegradationRecord
import skillbill.review.context.model.execution.SpecIntentProjectionResolveRequest
import skillbill.review.context.model.execution.SpecIntentResolution
import skillbill.review.context.model.execution.SpecIntentResolutionRung
import skillbill.review.context.model.execution.SpecIntentSurroundingContext
import skillbill.workflow.decomposition.model.DecompositionManifest
import skillbill.workflow.decomposition.model.DecompositionManifestValidationResult
import skillbill.workflow.decomposition.model.DecompositionSubtask
import java.io.IOException
import java.nio.file.Path

@Inject
class SpecIntentProjectionResolver(
  private val fileStore: DecompositionManifestStore,
  private val validator: DecompositionManifestValidator,
  private val extractor: SpecIntentProjectionExtractor,
) {
  fun resolve(request: SpecIntentProjectionResolveRequest): SpecIntentResolution {
    val degradations = mutableListOf<SpecIntentDegradationRecord>()
    val explicit = request.explicitSpecPath
    if (explicit != null) {
      return when (
        val read = extractor.extract(request.repoRoot.toPath(), explicit.toPath(), request.budget, surrounding = null)
      ) {
        is SpecIntentSourceRead.Read -> SpecIntentResolution.Resolved(read.value)
        is SpecIntentSourceRead.Unavailable ->
          throw UnreadableSpecIntentProjectionError(read.specPath, read.reason, read.cause)
      }
    }
    val issueKey = TRACKER_STYLE_ISSUE_KEY.find(request.branchName)?.value?.uppercase()
    val fromManifest = resolveManifest(request, issueKey, degradations)
    if (fromManifest != null) return withDegradations(fromManifest, degradations)
    if (issueKey == null) {
      return none(SpecIntentAbsenceReason.NOT_APPLICABLE_SCOPE, SpecIntentResolutionRung.NONE, degradations)
    }
    return withDegradations(resolveGlob(request, issueKey, degradations), degradations)
  }

  private fun resolveManifest(
    request: SpecIntentProjectionResolveRequest,
    issueKey: String?,
    degradations: MutableList<SpecIntentDegradationRecord>,
  ): SpecIntentResolution? {
    val candidates = fileStore.findDecompositionManifestFiles(request.repoRoot.toPath())
    if (candidates.isEmpty()) return null
    val loaded = mutableListOf<Pair<Path, DecompositionManifest>>()
    candidates.forEach { path ->
      when (val manifest = readManifest(path)) {
        null ->
          degradations +=
            SpecIntentDegradationRecord(
              seam = MANIFEST_UNREADABLE_SEAM,
              reason = MANIFEST_UNREADABLE_REASON,
              rung = SpecIntentResolutionRung.GLOB.wireValue,
              resolvedPath = repoRelativePath(request.repoRoot.toPath(), path),
            )
        else -> loaded += path to manifest
      }
    }
    val matching =
      loaded.map { it.second }.let { manifests ->
        issueKey?.let { key -> manifests.filter { it.issueKey == key } }.orEmpty().ifEmpty {
          manifests.filter { it.featureBranch == request.branchName }
        }
      }
    if (matching.size != 1) return null
    val manifest = matching.single()
    val owner = owningSubtask(manifest, request)
    val primary = Path.of(owner?.specPath ?: manifest.parentSpecPath)
    val surrounding = owner?.let { loadSurroundingContext(request, manifest.parentSpecPath, degradations) }
    return when (val read = extractor.extract(request.repoRoot.toPath(), primary, request.budget, surrounding)) {
      is SpecIntentSourceRead.Read -> SpecIntentResolution.Resolved(read.value)
      is SpecIntentSourceRead.Unavailable -> {
        degradations +=
          SpecIntentDegradationRecord(
            seam = RESOLVE_SEAM,
            reason = SpecIntentAbsenceReason.NO_SPEC_FOUND.wireValue,
            rung = SpecIntentResolutionRung.MANIFEST.wireValue,
            resolvedPath = read.specPath,
          )
        null
      }
    }
  }

  private fun resolveGlob(
    request: SpecIntentProjectionResolveRequest,
    issueKey: String,
    degradations: MutableList<SpecIntentDegradationRecord>,
  ): SpecIntentResolution {
    val matches =
      fileStore.listDirectChildDirectories(request.repoRoot.resolve(".feature-specs").toPath())
        .filter { it.fileName.toString().startsWith("$issueKey-") }
        .map { it.resolve("spec.md") }
        .filter { fileStore.isRegularFile(it) }
        .sorted()
    return when (matches.size) {
      0 -> none(SpecIntentAbsenceReason.NO_SPEC_FOUND, SpecIntentResolutionRung.GLOB, degradations)
      1 ->
        when (
          val read = extractor.extract(request.repoRoot.toPath(), matches.single(), request.budget, surrounding = null)
        ) {
          is SpecIntentSourceRead.Read -> SpecIntentResolution.Resolved(read.value)
          is SpecIntentSourceRead.Unavailable ->
            none(SpecIntentAbsenceReason.NO_SPEC_FOUND, SpecIntentResolutionRung.GLOB, degradations)
        }
      else -> none(SpecIntentAbsenceReason.AMBIGUOUS_MATCH, SpecIntentResolutionRung.GLOB, degradations)
    }
  }

  private fun readManifest(path: Path): DecompositionManifest? {
    return try {
      if (!fileStore.isRegularFile(path)) return null
      when (val result = validator.validateYamlTextResult(fileStore.readText(path), path.toString())) {
        is DecompositionManifestValidationResult.AcceptedUnchanged -> result.manifest
        is DecompositionManifestValidationResult.AcceptedAfterRepair -> result.manifest
        is DecompositionManifestValidationResult.Rejected -> null
      }
    } catch (error: IOException) {
      error.rethrowIfCooperativeCancellationOrInterruption()
      null
    } catch (_: InvalidDecompositionManifestSchemaError) {
      null
    }
  }

  private fun loadSurroundingContext(
    request: SpecIntentProjectionResolveRequest,
    parentSpecPath: String,
    degradations: MutableList<SpecIntentDegradationRecord>,
  ): SpecIntentSurroundingContext? {
    return when (val read = extractor.surroundingContext(request.repoRoot.toPath(), Path.of(parentSpecPath))) {
      is SpecIntentSourceRead.Read -> read.value
      is SpecIntentSourceRead.Unavailable -> {
        degradations +=
          SpecIntentDegradationRecord(
            seam = PARENT_SPEC_UNAVAILABLE_SEAM,
            reason = PARENT_SPEC_UNAVAILABLE_REASON,
            rung = SpecIntentResolutionRung.MANIFEST.wireValue,
            resolvedPath = read.specPath,
          )
        null
      }
    }
  }

  private fun owningSubtask(
    manifest: DecompositionManifest,
    request: SpecIntentProjectionResolveRequest,
  ): DecompositionSubtask? {
    val byIntent = manifest.subtasks.firstOrNull { it.id == manifest.currentSubtaskIntent.subtaskId }
    if (byIntent != null) return byIntent
    val byBranch = manifest.subtasks.filter { it.branch == request.branchName }
    if (byBranch.size == 1) return byBranch.single()
    val byPath =
      manifest.subtasks.filter { subtask ->
        request.changedPaths.any { changed ->
          changed == subtask.specPath || changed.startsWith(prefix(subtask.specPath))
        }
      }
    return byPath.singleOrNull()
  }

  private fun none(
    reason: SpecIntentAbsenceReason,
    rung: SpecIntentResolutionRung,
    degradations: MutableList<SpecIntentDegradationRecord>,
  ): SpecIntentResolution {
    degradations +=
      SpecIntentDegradationRecord(
        seam = RESOLVE_SEAM,
        reason = reason.wireValue,
        rung = rung.wireValue,
      )
    return SpecIntentResolution.None(reason, degradations.toList())
  }

  private fun withDegradations(
    resolution: SpecIntentResolution,
    degradations: List<SpecIntentDegradationRecord>,
  ): SpecIntentResolution =
    when (resolution) {
      is SpecIntentResolution.Resolved -> resolution.copy(degradations = degradations.toList())
      is SpecIntentResolution.None -> resolution.copy(degradations = degradations.toList())
    }

  private companion object {
    const val RESOLVE_SEAM = "SpecIntentProjectionResolver.resolve"
    const val MANIFEST_UNREADABLE_SEAM = "SpecIntentProjectionResolver.manifest_unreadable"
    const val MANIFEST_UNREADABLE_REASON = "manifest_unreadable"
    const val PARENT_SPEC_UNAVAILABLE_SEAM = "SpecIntentProjectionResolver.parent_spec_unavailable"
    const val PARENT_SPEC_UNAVAILABLE_REASON = "parent_spec_unavailable"
    val TRACKER_STYLE_ISSUE_KEY = Regex("(?i)$TRACKER_STYLE_ISSUE_KEY_PATTERN")

    fun prefix(specPath: String): String {
      val directory = specPath.substringBeforeLast('/', "")
      return if (directory.isEmpty()) specPath else "$directory/"
    }
  }
}
