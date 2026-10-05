package skillbill.infrastructure.skills.install.staging.content

import skillbill.infrastructure.contracts.newSha256Digest
import skillbill.infrastructure.host.jvm.pathContainedIn
import skillbill.infrastructure.skills.agentaddon.AgentAddonPointer
import skillbill.infrastructure.skills.install.staging.GeneratedSupportPointer
import skillbill.infrastructure.skills.install.staging.InternalSidecarTarget
import skillbill.infrastructure.skills.install.staging.applicablePointers
import skillbill.infrastructure.skills.install.staging.invalidInstallStaging
import skillbill.model.toPath
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

internal const val INSTALL_CACHE_KEY_BYTES = 8
private const val INSTALL_STAGING_RECIPE_VERSION = "install-staging-v6-source-path"

internal data class InstallContentHashInputs(
  val sourceSkillDir: Path,
  val authored: List<Path>,
  val applicablePointers: List<Pair<PlatformManifest, PointerSpec>>,
  val generatedSupportPointers: List<GeneratedSupportPointer> = emptyList(),
  val internalChildren: List<InternalSidecarTarget> = emptyList(),
  val agentAddonPointers: List<AgentAddonPointer> = emptyList(),
  val checkoutRepoRoot: Path? = null,
)

internal fun computeInstallContentHash(
  sourceSkillDir: Path,
  authored: List<Path>,
  applicablePointers: List<Pair<PlatformManifest, PointerSpec>>,
  generatedSupportPointers: List<GeneratedSupportPointer> = emptyList(),
): String =
  computeInstallContentHash(
    InstallContentHashInputs(
      sourceSkillDir = sourceSkillDir,
      authored = authored,
      applicablePointers = applicablePointers,
      generatedSupportPointers = generatedSupportPointers,
    ),
  )

internal fun computeInstallContentHash(inputs: InstallContentHashInputs): String {
  return computeContentHash(inputs, includeSourcePath = true)
}

internal fun computeReconciliationContentHash(inputs: InstallContentHashInputs): String {
  return computeContentHash(inputs, includeSourcePath = false)
}

private fun computeContentHash(
  inputs: InstallContentHashInputs,
  includeSourcePath: Boolean,
): String {
  val digest = newSha256Digest()
  val newline = byteArrayOf('\n'.code.toByte())
  digest.update(INSTALL_STAGING_RECIPE_VERSION.toByteArray(StandardCharsets.UTF_8))
  digest.update(newline)
  if (includeSourcePath) {
    digest.update(inputs.sourceSkillDir.toAbsolutePath().normalize().toString().toByteArray(StandardCharsets.UTF_8))
    digest.update(newline)
  }
  inputs.authored.forEach { file ->
    val rel = inputs.sourceSkillDir.relativize(file).toString().replace(File.separatorChar, '/')
    digest.update(rel.toByteArray(StandardCharsets.UTF_8))
    digest.update(newline)
    digest.update(Files.readAllBytes(file))
    digest.update(newline)
  }
  updatePointerHash(digest, newline, inputs)
  updateInternalSidecarHash(digest, newline, inputs.internalChildren)
  updateAgentAddonHash(digest, newline, inputs.agentAddonPointers)
  return digest.digest().take(INSTALL_CACHE_KEY_BYTES).joinToString("") { byte -> "%02x".format(byte) }
}

private fun updatePointerHash(
  digest: MessageDigest,
  newline: ByteArray,
  inputs: InstallContentHashInputs,
) {
  digest.update("--pointers--".toByteArray(StandardCharsets.UTF_8))
  digest.update(newline)
  inputs.applicablePointers
    .sortedWith(compareBy({ it.second.skillRelativeDir }, { it.second.name }))
    .forEach { (manifest, spec) ->
      val line = "${spec.skillRelativeDir}|${spec.name}|${spec.target}"
      digest.update(line.toByteArray(StandardCharsets.UTF_8))
      digest.update(newline)
      val repoRoot =
        inputs.checkoutRepoRoot?.toAbsolutePath()?.normalize()
          ?: manifest.packRoot.toPath().toAbsolutePath().normalize().parent?.parent
          ?: error("Platform pack '${manifest.slug}' root '${manifest.packRoot}' has no repo root parent.")
      val targetFile = repoRoot.resolve(spec.target).normalize()
      if (!pathContainedIn(targetFile, repoRoot)) {
        invalidInstallStaging(
          inputs.sourceSkillDir.toString(),
          "Pointer '${spec.name}' under '${spec.skillRelativeDir}' targets '${spec.target}' " +
            "outside repoRoot '$repoRoot'.",
        )
      }
      if (!Files.isRegularFile(targetFile, LinkOption.NOFOLLOW_LINKS)) {
        invalidInstallStaging(
          inputs.sourceSkillDir.toString(),
          "Pointer '${spec.name}' under '${spec.skillRelativeDir}' targets '${spec.target}' " +
            "which does not exist at '$targetFile'.",
        )
      }
      digest.update(Files.readAllBytes(targetFile))
      digest.update(newline)
    }
  inputs.generatedSupportPointers.sortedBy { it.name }.forEach { pointer ->
    digest.update("${pointer.name}|".toByteArray(StandardCharsets.UTF_8))
    digest.update(Files.readAllBytes(pointer.target))
    digest.update(newline)
  }
}

private fun updateInternalSidecarHash(
  digest: MessageDigest,
  newline: ByteArray,
  internalChildren: List<InternalSidecarTarget>,
) {
  if (internalChildren.isEmpty()) return
  digest.update("--internal-sidecars--".toByteArray(StandardCharsets.UTF_8))
  digest.update(newline)
  internalChildren.sortedBy { child -> child.skillName }.forEach { child ->
    digest.update("${child.skillName}.md|".toByteArray(StandardCharsets.UTF_8))
    digest.update(child.renderedWrapper.toByteArray(StandardCharsets.UTF_8))
    digest.update(newline)
    child.authoredCompanions.sortedBy { companion -> companion.name }.forEach { companion ->
      digest.update("${companion.name}|".toByteArray(StandardCharsets.UTF_8))
      digest.update(companion.bytes)
      digest.update(newline)
    }
  }
}

private fun updateAgentAddonHash(
  digest: MessageDigest,
  newline: ByteArray,
  pointers: List<AgentAddonPointer>,
) {
  if (pointers.isEmpty()) return
  digest.update("--agent-addons--".toByteArray(StandardCharsets.UTF_8))
  digest.update(newline)
  pointers.sortedBy { it.slug }.forEach { pointer ->
    val declaration =
      "${pointer.consumer.id}|${pointer.slug}|${pointer.name}|" +
        "${pointer.manifestRelativePath}|${pointer.contentRelativePath}"
    digest.update(declaration.toByteArray(StandardCharsets.UTF_8))
    digest.update(newline)
    digest.update(pointer.manifestBytes)
    digest.update(newline)
    digest.update(pointer.contentBytes)
    digest.update(newline)
    digest.update(pointer.renderedBytes)
    digest.update(newline)
  }
}
