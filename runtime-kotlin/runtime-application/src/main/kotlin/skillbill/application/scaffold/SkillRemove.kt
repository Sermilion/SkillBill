package skillbill.application.scaffold

import me.tatarka.inject.annotations.Inject
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.failureCodeLabel
import skillbill.ports.skillremove.SkillRemoveFileSystem
import skillbill.skillremove.SkillBillRollbackException
import skillbill.skillremove.TargetValidation
import skillbill.skillremove.model.SkillRemovalPreview
import skillbill.skillremove.model.SkillRemovalRefusalReason
import skillbill.skillremove.model.SkillRemovalRequest
import skillbill.skillremove.model.SkillRemovalResult
import skillbill.skillremove.model.SkillRemovalTarget
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.coroutines.cancellation.CancellationException

@Inject
class SkillRemove(
  private val fileSystem: SkillRemoveFileSystem,
) {
  fun previewRemoval(request: SkillRemovalRequest): SkillRemovalResult {
    refusalFor(request)?.let { return it }
    val cascadedSkillNames = computeCascadedSkillNames(request)
    val preview =
      SkillRemovalPreview(
        filesystemPaths = fileSystem.resolveCascadeFilesystemPaths(request, cascadedSkillNames),
        manifestEdits = fileSystem.planManifestEdits(request, cascadedSkillNames),
        agentSymlinkUnlinks = fileSystem.planAgentSymlinkUnlinks(request, cascadedSkillNames),
        readmeCatalogEdits = fileSystem.planReadmeCatalogEdits(request),
        skillDirRoot = skillDirRootFor(request.target),
        cascadedSkillNames = cascadedSkillNames,
      )
    return SkillRemovalResult.Preview(preview)
  }

  fun executeRemoval(request: SkillRemovalRequest): SkillRemovalResult =
    tryExecute {
      refusalFor(request)?.let { refused ->
        return@tryExecute SkillRemovalResult.Failed(
          exceptionName = REFUSAL_EXCEPTION_NAME,
          exceptionMessage = refused.message,
          rollbackComplete = true,
        )
      }
      val cascadedSkillNames = computeCascadedSkillNames(request)
      val preview =
        SkillRemovalPreview(
          filesystemPaths = fileSystem.resolveCascadeFilesystemPaths(request, cascadedSkillNames),
          manifestEdits = fileSystem.planManifestEdits(request, cascadedSkillNames),
          agentSymlinkUnlinks = fileSystem.planAgentSymlinkUnlinks(request, cascadedSkillNames),
          readmeCatalogEdits = fileSystem.planReadmeCatalogEdits(request),
          skillDirRoot = skillDirRootFor(request.target),
          cascadedSkillNames = cascadedSkillNames,
        )

      val applied = fileSystem.applyCascade(request, preview)
      SkillRemovalResult.Success(
        preview = preview,
        removedPaths = applied.removedPaths,
        editedManifests = applied.editedManifests,
        unlinkedSymlinks = applied.unlinkedSymlinks,
        readmeWarnings = applied.readmeWarnings,
      )
    }

  private fun refusalFor(request: SkillRemovalRequest): SkillRemovalResult.Refused? =
    TargetValidation.refusal(request) ?: enforceRefusalPolicy(request)

  private fun enforceRefusalPolicy(request: SkillRemovalRequest): SkillRemovalResult.Refused? {
    val repoRoot = Paths.get(request.repoRootAbsolutePath).toAbsolutePath().normalize()
    return when (val target = request.target) {
      is SkillRemovalTarget.HorizontalSkill -> horizontalSkillRefusal(repoRoot, target)
      is SkillRemovalTarget.PlatformPack ->
        if (target.platform == BILL_SHARED_NAME) {
          SkillRemovalResult.Refused(
            SkillRemovalRefusalReason.BILL_SHARED_PROTECTED,
            "Removal of platform pack '$BILL_SHARED_NAME' is not allowed — it is a built-in shared surface.",
          )
        } else {
          null
        }
      is SkillRemovalTarget.AddOn,
      is SkillRemovalTarget.ExternalAddOn,
      -> null
    }
  }

  private fun horizontalSkillRefusal(
    repoRoot: Path,
    target: SkillRemovalTarget.HorizontalSkill,
  ): SkillRemovalResult.Refused? {
    val billSharedSkillRoot = repoRoot.resolve("skills/$BILL_SHARED_NAME").normalize()
    val candidate = repoRoot.resolve("skills/${target.skillName}").normalize()
    val protectedShipped = target.skillName.startsWith(SkillRemovalTarget.HORIZONTAL_PRODUCT_PREFIX)
    return when {
      candidate.startsWith(billSharedSkillRoot) ->
        SkillRemovalResult.Refused(
          SkillRemovalRefusalReason.BILL_SHARED_PROTECTED,
          "Removal of '$BILL_SHARED_NAME' is not allowed — it is a built-in shared surface.",
        )
      !target.allowShipped && protectedShipped ->
        SkillRemovalResult.Refused(
          SkillRemovalRefusalReason.SHIPPED_REQUIRES_ALLOW_SHIPPED,
          "Refusing to remove shipped surface '${target.skillName}' without --allow-shipped.",
        )
      else -> null
    }
  }

  private fun computeCascadedSkillNames(request: SkillRemovalRequest): List<String> =
    when (val target = request.target) {
      is SkillRemovalTarget.HorizontalSkill ->
        listOf(target.skillName) +
          fileSystem.discoverCascadedSkillNames(request)
            .filter { it != target.skillName }
      is SkillRemovalTarget.PlatformPack -> emptyList()
      is SkillRemovalTarget.AddOn -> emptyList()
      is SkillRemovalTarget.ExternalAddOn -> emptyList()
    }

  private fun skillDirRootFor(target: SkillRemovalTarget): String =
    when (target) {
      is SkillRemovalTarget.HorizontalSkill -> "skills/${target.skillName}"
      is SkillRemovalTarget.PlatformPack -> "platform-packs/${target.platform}"
      is SkillRemovalTarget.AddOn -> target.relativePath
      is SkillRemovalTarget.ExternalAddOn ->
        Paths.get(target.sourceRootAbsolutePath).resolve(target.fileName).normalize().toString().replace('\\', '/')
    }

  private inline fun tryExecute(block: () -> SkillRemovalResult): SkillRemovalResult {
    val outcome = runCatching(block)
    if (outcome.isSuccess) return outcome.getOrThrow()
    return mapSkillRemovalFailure(outcome.exceptionOrNull()!!)
  }

  private fun mapSkillRemovalFailure(error: Throwable): SkillRemovalResult {
    if (error is CancellationException) throw error
    if (error is Error) throw error
    if (error is SkillBillRollbackException) return removalFailed(error, rollbackComplete = false)
    if (error is SkillBillRuntimeException) return removalFailed(error, rollbackComplete = true)
    return removalFailed(error, rollbackComplete = false)
  }

  private fun removalFailed(
    error: Throwable,
    rollbackComplete: Boolean,
  ): SkillRemovalResult.Failed =
    SkillRemovalResult.Failed(
      exceptionName = error.failureCodeLabel() ?: error::class.simpleName.orEmpty().ifBlank { "Exception" },
      exceptionMessage = error.message.orEmpty(),
      rollbackComplete = rollbackComplete,
    )

  companion object {
    const val BILL_SHARED_NAME: String = ".bill-shared"

    private const val REFUSAL_EXCEPTION_NAME: String = "SkillRemovalRefusedException"
  }
}
