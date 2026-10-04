package skillbill.infrastructure.skills.install.nativeagent.link

import skillbill.infrastructure.skills.install.apply.tryCreateNewSymlinkWithGuidance
import skillbill.infrastructure.skills.install.apply.tryCreateReplacementSymlinkWithGuidance
import skillbill.infrastructure.skills.install.nativeagent.InstallNativeAgentResult
import skillbill.infrastructure.skills.install.nativeagent.inventory.isCanonicalNativeAgentArtifactTarget
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import skillbill.install.model.AgentTarget
import skillbill.model.toPath
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path

internal fun installNativeAgentFile(
  source: Path,
  agentTarget: AgentTarget,
  managedSourceRoots: List<Path>,
  ownership: NativeAgentLinkOwnership? = null,
  beforeMutation: (Path) -> Unit = {},
): InstallNativeAgentResult {
  val resolvedSource = source.toAbsolutePath().normalize()
  if (!Files.isRegularFile(resolvedSource)) {
    throw FileNotFoundException("Native agent file '$resolvedSource' does not exist.")
  }
  beforeMutation(agentTarget.path.toPath())
  Files.createDirectories(agentTarget.path.toPath())
  val linkPath = agentTarget.path.resolve(resolvedSource.fileName.toString())
  return applyInstallDecision(
    linkPath = linkPath.toPath(),
    resolvedSource = resolvedSource,
    decision = decideInstallAction(linkPath.toPath(), resolvedSource, managedSourceRoots, ownership),
    beforeMutation = beforeMutation,
  )
}

internal fun uninstallNativeAgentFiles(
  sources: List<Path>,
  candidateDirs: List<Path>,
): List<Path> {
  val removed = mutableListOf<Path>()
  sources.forEach { source ->
    val resolvedSource = source.toAbsolutePath().normalize()
    candidateDirs.forEach { targetDir ->
      val linkPath = targetDir.resolve(resolvedSource.fileName)
      val existingTarget = if (Files.isSymbolicLink(linkPath)) resolveSymlinkTarget(linkPath) else null
      if (existingTarget == resolvedSource) {
        Files.deleteIfExists(linkPath)
        removed.add(linkPath)
      }
    }
  }
  return removed
}

private fun applyInstallDecision(
  linkPath: Path,
  resolvedSource: Path,
  decision: InstallAction,
  beforeMutation: (Path) -> Unit,
): InstallNativeAgentResult =
  when (decision) {
    InstallAction.Skip ->
      InstallNativeAgentResult.Skipped(
        linkPath,
        "existing non-managed file at $linkPath was preserved",
      )
    InstallAction.AlreadyLinked -> InstallNativeAgentResult.Skipped(linkPath, "already linked to $resolvedSource")
    InstallAction.Replace, InstallAction.Create -> {
      beforeMutation(linkPath)
      val failure =
        when (decision) {
          InstallAction.Replace -> tryCreateReplacementSymlinkWithGuidance(linkPath, resolvedSource)
          InstallAction.Create -> tryCreateNewSymlinkWithGuidance(linkPath, resolvedSource)
          InstallAction.Skip,
          InstallAction.AlreadyLinked,
          -> error("Unexpected native-agent install decision '$decision'.")
        }
      if (failure == null) {
        InstallNativeAgentResult.Linked(linkPath)
      } else {
        InstallNativeAgentResult.Failed(failure.path, failure.error)
      }
    }
  }

private enum class InstallAction { Skip, AlreadyLinked, Replace, Create }

internal data class NativeAgentLinkOwnership(
  val home: Path,
  val provider: NativeAgentProvider,
  val logicalName: String,
)

private fun decideInstallAction(
  linkPath: Path,
  resolvedSource: Path,
  managedSourceRoots: List<Path>,
  ownership: NativeAgentLinkOwnership?,
): InstallAction {
  if (!Files.isSymbolicLink(linkPath)) {
    return if (Files.exists(linkPath)) InstallAction.Skip else InstallAction.Create
  }
  val existingTarget = resolveSymlinkTarget(linkPath)
  return when {
    existingTarget == resolvedSource -> InstallAction.AlreadyLinked
    existingTarget != null && ownership != null &&
      isCanonicalNativeAgentArtifactTarget(
        ownership.home,
        ownership.provider,
        ownership.logicalName,
        existingTarget,
        managedSourceRoots,
      )
    ->
      InstallAction.Replace
    existingTarget != null &&
      managedSourceRoots.any { root ->
        val normalizedRoot = root.toAbsolutePath().normalize()
        existingTarget == normalizedRoot.resolve(resolvedSource.fileName) ||
          existingTarget == normalizedRoot.resolve(resolvedSource.parent.fileName).resolve(resolvedSource.fileName)
      }
    ->
      InstallAction.Replace
    existingTarget != null && Files.notExists(existingTarget) -> InstallAction.Replace
    else -> InstallAction.Skip
  }
}

internal fun resolveSymlinkTarget(linkPath: Path): Path? =
  runCatching {
    val rawTarget = Files.readSymbolicLink(linkPath)
    val resolvedTarget = if (rawTarget.isAbsolute) rawTarget else linkPath.parent.resolve(rawTarget)
    resolvedTarget.toAbsolutePath().normalize()
  }.getOrNull()
