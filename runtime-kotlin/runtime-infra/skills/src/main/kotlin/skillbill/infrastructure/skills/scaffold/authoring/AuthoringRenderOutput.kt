package skillbill.infrastructure.skills.scaffold.authoring

import skillbill.agentaddon.model.AgentAddonConsumer
import skillbill.error.shellcontent.ContractVersionMismatchError
import skillbill.infrastructure.skills.agentaddon.AgentAddonDeliveryResolver
import skillbill.infrastructure.skills.scaffold.platformpack.catalog.PlatformPackDiscoveryContext
import skillbill.infrastructure.skills.scaffold.platformpack.loader.loadPlatformManifest
import skillbill.infrastructure.skills.scaffold.pointer.renderPointer
import skillbill.infrastructure.skills.scaffold.runtime.service.contract.SHELL_CONTRACT_VERSION
import skillbill.model.toPath
import skillbill.scaffold.model.PlatformManifest
import skillbill.scaffold.model.PointerSpec
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class AuthoringRenderBlock(
  val header: String,
  val content: String,
)

data class AuthoringRenderResult(
  val repoRoot: Path,
  val skillName: String,
  val blocks: List<AuthoringRenderBlock>,
) {
  val stdout: String = renderBlocks(blocks)

  val payload: Map<String, Any?> =
    mapOf(
      "repo_root" to repoRoot.toString(),
      "skill_name" to skillName,
      "blocks" to
        blocks.map { block ->
          mapOf(
            "header" to block.header,
            "content" to block.content,
          )
        },
    )
}

private fun renderBlocks(blocks: List<AuthoringRenderBlock>): String =
  buildString {
    blocks.forEachIndexed { index, block ->
      if (index > 0) {
        appendLine()
      }
      appendLine(block.header)
      append(block.content.trimEnd('\r', '\n'))
      appendLine()
    }
  }

fun renderAuthoringTarget(
  repoRoot: Path,
  skillName: String,
  externalDiscovery: PlatformPackDiscoveryContext? = null,
): AuthoringRenderResult {
  val resolvedRoot = repoRoot.toAbsolutePath().normalize()
  val target = resolveTarget(resolvedRoot, skillName, externalDiscovery)
  return renderAuthoringTarget(resolvedRoot, target)
}

internal fun renderAuthoringTarget(
  repoRoot: Path,
  target: AuthoringTarget,
): AuthoringRenderResult {
  val resolvedRoot = repoRoot.toAbsolutePath().normalize()
  val relativeSkillFile = normalizedRelativePath(resolvedRoot, target.skillFile)
  val wrapperBlock =
    AuthoringRenderBlock(
      header = "===== SKILL.md: $relativeSkillFile =====",
      content = renderWrapper(target),
    )
  val pointerBlocks = renderPointerBlocks(resolvedRoot, target)
  return AuthoringRenderResult(
    repoRoot = resolvedRoot,
    skillName = target.skillName,
    blocks = listOf(wrapperBlock) + pointerBlocks,
  )
}

private fun renderPointerBlocks(
  repoRoot: Path,
  target: AuthoringTarget,
): List<AuthoringRenderBlock> {
  return renderPlatformPointerBlocks(repoRoot, target) + renderAgentAddonPointerBlocks(repoRoot, target)
}

private fun renderPlatformPointerBlocks(
  repoRoot: Path,
  target: AuthoringTarget,
): List<AuthoringRenderBlock> {
  val packRoot = targetPlatformPackRoot(target) ?: return emptyList()
  val pack = loadPlatformManifest(packRoot)
  requireMatchingRenderContractVersion(pack)
  val skillRelativeDir = normalizedRelativePath(packRoot, target.skillFile.parent)
  return pack.pointers
    .filter { spec -> spec.skillRelativeDir == skillRelativeDir }
    .map { spec -> renderPointerBlock(repoRoot, pack, spec) }
}

private fun renderAgentAddonPointerBlocks(
  repoRoot: Path,
  target: AuthoringTarget,
): List<AuthoringRenderBlock> {
  if (target.internalFor != null) return emptyList()
  val consumer =
    runCatching { AgentAddonConsumer.fromId(target.skillName) }.getOrNull()
      ?: return emptyList()
  val outputDir = target.skillFile.parent
  return AgentAddonDeliveryResolver().resolve(repoRoot, consumer).map { pointer ->
    val outputFile = outputDir.resolve(pointer.name)
    AuthoringRenderBlock(
      header = "===== pointer: ${normalizedRelativePath(repoRoot, outputFile)} =====",
      content = pointer.renderedBytes.toString(StandardCharsets.UTF_8),
    )
  }
}

private fun renderPointerBlock(
  repoRoot: Path,
  pack: PlatformManifest,
  spec: PointerSpec,
): AuthoringRenderBlock {
  val pointerFile = pack.packRoot.resolve(spec.skillRelativeDir).resolve(spec.name).toPath().normalize()
  val relativePointerFile = normalizedRelativePath(repoRoot, pointerFile)
  return AuthoringRenderBlock(
    header = "===== pointer: $relativePointerFile =====",
    content = renderPointer(repoRoot, pack.packRoot.toPath(), spec),
  )
}

private fun targetPlatformPackRoot(target: AuthoringTarget): Path? =
  generateSequence(target.skillFile.toAbsolutePath().normalize().parent) { path -> path.parent }
    .firstOrNull { candidate -> Files.isRegularFile(candidate.resolve("platform.yaml")) }

private fun requireMatchingRenderContractVersion(pack: PlatformManifest) {
  if (pack.contractVersion != SHELL_CONTRACT_VERSION) {
    throw ContractVersionMismatchError(
      "Platform pack '${pack.slug}': declares contract_version '${pack.contractVersion}' " +
        "but the shell expects '$SHELL_CONTRACT_VERSION'.",
    )
  }
}

private fun normalizedRelativePath(
  root: Path,
  path: Path,
): String = root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/')
