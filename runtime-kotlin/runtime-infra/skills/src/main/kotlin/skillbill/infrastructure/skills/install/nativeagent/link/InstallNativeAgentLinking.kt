package skillbill.infrastructure.skills.install.nativeagent.link

import skillbill.infrastructure.skills.install.nativeagent.InstallNativeAgentResult
import skillbill.infrastructure.skills.install.nativeagent.inventory.isCanonicalNativeAgentArtifactTarget
import skillbill.infrastructure.skills.install.symlink.createNewSymlinkWithGuidance
import skillbill.infrastructure.skills.install.symlink.createReplacementSymlinkWithGuidance
import skillbill.infrastructure.skills.install.symlink.resolveSymlinkTarget
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
      when (decision) {
        InstallAction.Replace -> createReplacementSymlinkWithGuidance(linkPath, resolvedSource)
        InstallAction.Create -> createNewSymlinkWithGuidance(linkPath, resolvedSource)
        InstallAction.Skip,
        InstallAction.AlreadyLinked,
        -> error("Unexpected native-agent install decision '$decision'.")
      }
      InstallNativeAgentResult.Linked(linkPath)
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
