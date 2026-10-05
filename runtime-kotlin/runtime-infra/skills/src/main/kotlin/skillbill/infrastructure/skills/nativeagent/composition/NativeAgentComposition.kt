package skillbill.infrastructure.skills.nativeagent.composition

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.InstallFailureCode
import skillbill.infrastructure.skills.nativeagent.platformpack.NativeAgentPlatformPack
import skillbill.infrastructure.skills.nativeagent.platformpack.NativeAgentPlatformPackLoader
import skillbill.infrastructure.skills.nativeagent.rendering.composeGovernedAgentBody
import skillbill.infrastructure.skills.nativeagent.rendering.enforceAddonProjectionParity
import skillbill.infrastructure.skills.nativeagent.rendering.enforceComposedAgentBudget
import java.nio.file.Path

internal data class NativeAgentCompositionTarget(
  val contentPath: Path,
  val source: NativeAgentCompositionTargetSource,
  val manifest: NativeAgentPlatformPack? = null,
)

enum class NativeAgentCompositionTargetSource {
  PlatformManifest,
  SiblingContent,
}

internal fun parseCompositionDirective(
  rawValue: String?,
  label: String,
): NativeAgentCompositionDirective? =
  rawValue?.let { value ->
    if (value.isBlank()) {
      invalidCompositionInput("$label: native agent compose directive is required when the compose key is present")
    }
    val kind =
      NativeAgentCompositionKind.entries.firstOrNull { it.wireValue == value }
        ?: invalidCompositionInput("$label: unsupported native agent compose directive '$value'")
    NativeAgentCompositionDirective(kind)
  }

internal fun invalidCompositionInput(message: String): Nothing =
  throw SkillBillRuntimeException(InstallFailureCode.INVALID_NATIVE_AGENT_COMPOSITION_SCHEMA, message)

internal fun resolveNativeAgentCompositionTarget(
  repoRoot: Path,
  source: NativeAgentSource,
  packLoader: NativeAgentPlatformPackLoader,
  additionalPackRoots: List<Path> = emptyList(),
): NativeAgentCompositionTarget? {
  if (source.composition == null) {
    return null
  }
  require(source.composition.kind == NativeAgentCompositionKind.GovernedContent) {
    "unsupported native agent compose directive '${source.composition.kind.wireValue}'"
  }
  val sourcePath =
    requireNotNull(source.path) {
      "native agent composition resolution requires a source path"
    }.toAbsolutePath().normalize()
  val root = repoRoot.toAbsolutePath().normalize()
  val packRoot = platformPackRoot(root, sourcePath, additionalPackRoots)
  return if (packRoot != null) {
    resolvePlatformManifestContentTarget(
      root,
      packRoot,
      sourcePath,
      source,
      packLoader.withAdditionalPackRoots(additionalPackRoots),
    )
  } else {
    resolveSiblingContentTarget(sourcePath, source)
  } ?: invalidCompositionInput(
    "${displayPath(root, sourcePath)}: native agent compose directive 'governed-content' " +
      "could not resolve a corresponding content.md for '${source.name}'",
  )
}

internal fun nativeAgentCompositionRepoRoot(
  platformPacksRoot: Path,
  skillsRoot: Path?,
): Path {
  val platformRoot = platformPacksRoot.toAbsolutePath().normalize()
  val skillRoot = skillsRoot?.toAbsolutePath()?.normalize()
  return if (skillRoot != null && platformRoot.parent == skillRoot.parent) {
    platformRoot.parent
  } else if (platformRoot.fileName?.toString() == "platform-packs") {
    platformRoot.parent ?: platformRoot
  } else {
    platformRoot
  }
}

internal fun composeNativeAgentSource(
  repoRoot: Path,
  source: NativeAgentSource,
  context: NativeAgentCompositionContext,
): NativeAgentSource {
  val target =
    resolveNativeAgentCompositionTarget(
      repoRoot,
      source,
      context.packLoader,
      context.additionalPackRoots,
    ) ?: return source
  val governedBody = context.renderGovernedBody(target.contentPath, source.name).trimEnd()
  val localFraming = source.body.trim()
  val composedBody =
    buildString {
      if (localFraming.isNotBlank()) {
        append(localFraming)
        append("\n\n")
      }
      append(governedBody)
    }.trimEnd()
  val governed = composeGovernedAgentBody(repoRoot, target, composedBody, context.additionalPackRoots)
  val composed =
    source.copy(
      body = governed.body,
      composition = null,
      composedAddonSlugs = governed.composedAddonSlugs,
    )
  target.manifest?.let { pack ->
    enforceAddonProjectionParity(pack, source.name, composed.composedAddonSlugs)
  }
  enforceComposedAgentBudget(
    repoRoot.toAbsolutePath().normalize(),
    target,
    renderNativeAgentSource(composed),
    context.reviewContextBudgetBytes,
    context.additionalPackRoots,
  )
  return composed
}

internal fun renderComposedNativeAgentSource(
  repoRoot: Path,
  source: NativeAgentSource,
  context: NativeAgentCompositionContext,
): String =
  renderNativeAgentSource(
    composeNativeAgentSource(
      repoRoot,
      source,
      context,
    ),
  )
