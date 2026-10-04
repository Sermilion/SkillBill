
package skillbill.cli.skillremove

import skillbill.cli.kernel.cli.CliRunState
import skillbill.cli.kernel.payload.CliPayloadStatus
import skillbill.contracts.SharedPayloadKeys
import skillbill.skillremove.SkillRemoveErrorSanitizer
import skillbill.skillremove.model.SkillRemovalRefusalReason
import skillbill.skillremove.model.SkillRemovalRequest
import skillbill.skillremove.model.SkillRemovalResult
import skillbill.skillremove.model.SkillRemovalTarget
import java.nio.file.Path

internal fun executeRemoveCommand(
  request: RemoveCommandExecutionRequest,
  state: CliRunState,
) {
  val format = request.format
  if (request.rawTarget == null) {
    return state.complete(errorPayload(removeUsageMessage()), format, exitCode = 1)
  }
  val parsed =
    parseRemoveTarget(request.rawTarget, request.allowShipped)
      ?: return state.complete(
        errorPayload("Invalid remove target: '${request.rawTarget}'.\n\n${removeUsageMessage()}"),
        format,
        exitCode = 1,
      )
  val absoluteRepoRoot = Path.of(request.repoRoot).toAbsolutePath().normalize().toString()
  val removalRequest =
    SkillRemovalRequest(
      target = parsed,
      repoRootAbsolutePath = absoluteRepoRoot,
      userHomeAbsolutePath = request.inputs.userHome.toAbsolutePath().normalize().toString(),
      environment = request.inputs.environment,
    )
  val outcome =
    if (request.dryRun) {
      request.skillRemove.previewRemoval(removalRequest)
    } else {
      request.skillRemove.executeRemoval(removalRequest)
    }
  when (outcome) {
    is SkillRemovalResult.Preview -> state.complete(previewPayload(outcome), format)
    is SkillRemovalResult.Success -> state.complete(successPayload(outcome), format)
    is SkillRemovalResult.Refused ->
      state.complete(
        errorPayload(refusalErrorMessage(outcome, request.rawTarget, absoluteRepoRoot)),
        format,
        exitCode = 1,
      )
    is SkillRemovalResult.Failed -> state.complete(failedPayload(outcome, absoluteRepoRoot), format, exitCode = 1)
  }
}

internal fun parseRemoveTarget(
  raw: String,
  allowShipped: Boolean,
): SkillRemovalTarget? {
  val (kind, value) =
    raw.substringBefore(':', missingDelimiterValue = "") to
      raw.substringAfter(':', missingDelimiterValue = "")
  if (kind.isBlank() || value.isBlank()) return null
  return when (kind) {
    "skill" -> SkillRemovalTarget.HorizontalSkill(skillName = value, allowShipped = allowShipped)
    "platform" -> SkillRemovalTarget.PlatformPack(platform = value, allowShipped = allowShipped)
    "addon" -> SkillRemovalTarget.AddOn(relativePath = value)
    else -> null
  }
}

internal fun refusalErrorMessage(
  refusal: SkillRemovalResult.Refused,
  rawTarget: String,
  repoRootAbsolutePath: String,
): String {
  val sanitized = SkillRemoveErrorSanitizer.sanitize(refusal.message, repoRootAbsolutePath)
  if (refusal.reason != SkillRemovalRefusalReason.SHIPPED_REQUIRES_ALLOW_SHIPPED) {
    return sanitized
  }
  return """
    $sanitized

    Why this is protected:
      bill-* skills are shipped product surfaces.
      Removing them is a maintainer-only operation because it changes the workflow set installed for every agent.

    To preview the maintainer removal:
      skill-bill remove $rawTarget --dry-run --allow-shipped

    To remove it after reviewing the preview:
      skill-bill remove $rawTarget --allow-shipped
    """.trimIndent()
}

internal fun previewPayload(preview: SkillRemovalResult.Preview): Map<String, Any?> =
  mapOf(
    SharedPayloadKeys.STATUS to "preview",
    "filesystem_paths" to preview.preview.filesystemPaths,
    "manifest_edits" to
      preview.preview.manifestEdits.map {
        mapOf("manifest" to it.manifestPath, "kind" to it.editKind.name, "detail" to it.detail)
      },
    "agent_symlink_unlinks" to
      preview.preview.agentSymlinkUnlinks.map {
        mapOf("provider" to it.provider.name, "path" to it.path)
      },
    "readme_catalog_edits" to
      preview.preview.readmeCatalogEdits.map {
        mapOf("readme" to it.readmePath, "kind" to it.kind.name)
      },
    "cascaded_skill_names" to preview.preview.cascadedSkillNames,
    "skill_dir_root" to preview.preview.skillDirRoot,
  )

internal fun successPayload(success: SkillRemovalResult.Success): Map<String, Any?> =
  mapOf(
    SharedPayloadKeys.STATUS to CliPayloadStatus.OK,
    "removed_paths" to success.removedPaths,
    "edited_manifests" to success.editedManifests,
    "unlinked_symlinks" to success.unlinkedSymlinks,
  )

internal fun failedPayload(
  failed: SkillRemovalResult.Failed,
  repoRootAbsolutePath: String,
): Map<String, Any?> =
  mapOf(
    SharedPayloadKeys.STATUS to "error",
    "exception" to failed.exceptionName,
    "error" to SkillRemoveErrorSanitizer.sanitize(failed.exceptionMessage, repoRootAbsolutePath),
    "rollback_complete" to failed.rollbackComplete,
  )

internal fun errorPayload(message: String): Map<String, Any?> =
  mapOf(SharedPayloadKeys.STATUS to "error", "error" to message)

internal fun removeUsageMessage(): String =
  """
  Missing remove target.

  Examples:
    skill-bill remove skill:bill-my-skill --dry-run
    skill-bill remove platform:my-platform --dry-run
    skill-bill remove addon:platform-packs/kmp/addons/my-addon.md --dry-run

  Target forms:
    skill:<name>
    platform:<slug>
    addon:<path>

  Use --dry-run first to preview the exact files, README edits, and agent links that will be removed.
  """.trimIndent()
