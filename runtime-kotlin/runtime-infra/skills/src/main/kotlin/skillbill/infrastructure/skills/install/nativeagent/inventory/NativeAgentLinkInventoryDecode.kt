package skillbill.infrastructure.skills.install.nativeagent.inventory

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import skillbill.error.core.InvalidNativeAgentLinkInventoryDecodeError
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.infrastructure.contracts.sha256Hex
import skillbill.infrastructure.skills.install.nativeagent.parseEmbeddedLogicalName
import skillbill.install.model.SupportedAgent
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

internal object NativeAgentLinkInventoryDecode {
  fun decode(
    path: Path,
    home: Path,
    managedRoots: List<Path>,
    mapper: ObjectMapper,
    schema: JsonSchema,
  ): List<NativeAgentLinkInventoryEntry> =
    try {
      val root = readValidatedRoot(path, mapper, schema)
      val entries = decodeEntries(root, path)
      validateDecodedEntries(entries, home, managedRoots, path)
      entries
    } catch (error: CancellationException) {
      rethrow(error)
    } catch (error: SkillBillRuntimeException) {
      error.rethrowUnless(error.isShellContentContractFailure())
      rethrow(error)
    } catch (error: IOException) {
      throwDecodeError(path, error)
    } catch (error: IllegalArgumentException) {
      throwDecodeError(path, error)
    }

  fun validateSemanticEntries(
    entries: List<NativeAgentLinkInventoryEntry>,
    home: Path,
    managedRoots: List<Path>,
  ) {
    validateDecodedEntries(entries, home, managedRoots, Path.of("<semantic>"))
  }

  fun isSemanticallyValid(
    entry: NativeAgentLinkInventoryEntry,
    home: Path,
    managedRoots: List<Path>,
  ): Boolean {
    return runCatching {
      val provider = NativeAgentLinkInventoryPaths.provider(entry.provider)
      val raw = Files.readSymbolicLink(entry.installedPath)
      val resolved = entry.installedPath.parent.resolve(raw).toAbsolutePath().normalize()
      entry.installedPath.fileName.toString() == provider.fileName(entry.logicalName) &&
        Files.isSymbolicLink(entry.installedPath) &&
        resolved == entry.cacheTargetPath &&
        isCanonicalNativeAgentArtifactTarget(home, provider, entry.logicalName, resolved, managedRoots) &&
        Files.isRegularFile(resolved) &&
        Files.isReadable(resolved) &&
        parseEmbeddedLogicalName(resolved, SupportedAgent.fromWire(entry.provider)) == entry.logicalName &&
        sha256Hex(Files.readAllBytes(resolved)) == entry.contentDigest
    }.getOrDefault(false)
  }

  private fun readValidatedRoot(
    path: Path,
    mapper: ObjectMapper,
    schema: JsonSchema,
  ): JsonNode {
    if (Files.size(path) > NativeAgentLinkInventoryLimits.MAX_BYTES) {
      invalid(path, "inventory exceeds ${NativeAgentLinkInventoryLimits.MAX_BYTES} bytes")
    }
    val root = mapper.readTree(path.toFile()) ?: invalid(path, "<root> must be an object")
    if (!root.isObject) invalid(path, "<root> must be an object")
    val schemaErrors = schema.validate(root)
    if (schemaErrors.isNotEmpty()) {
      invalid(path, schemaErrors.joinToString("; ") { it.message })
    }
    return root
  }

  private fun decodeEntries(
    root: JsonNode,
    path: Path,
  ): List<NativeAgentLinkInventoryEntry> =
    root["entries"]?.elements()?.asSequence()?.map { node ->
      NativeAgentLinkInventoryEntry(
        logicalName = node.requiredText("logical_name", path),
        provider = node.requiredText("provider", path),
        installedPath = Path.of(node.requiredText("installed_path", path)),
        cacheTargetPath = Path.of(node.requiredText("cache_target_path", path)),
        contentDigest = node.requiredText("content_digest", path),
        sourceRoot = Path.of(node.requiredText("source_root", path)),
      )
    }?.toList() ?: invalid(path, "entries is required")

  private fun validateDecodedEntries(
    entries: List<NativeAgentLinkInventoryEntry>,
    home: Path,
    managedRoots: List<Path>,
    path: Path,
  ) {
    validateUniqueEntries(entries, path)
    entries.forEach { entry -> validateDecodedEntry(entry, home, managedRoots, path) }
  }

  private fun validateUniqueEntries(
    entries: List<NativeAgentLinkInventoryEntry>,
    path: Path,
  ) {
    if (entries.map { it.provider to it.installedPath.normalize() }.distinct().size != entries.size) {
      invalid(path, "duplicate provider/installed_path entry")
    }
    if (
      entries.groupBy { Triple(it.provider, it.installedPath.parent.normalize(), it.logicalName) }
        .values.any { it.size > 1 }
    ) {
      invalid(path, "duplicate provider/directory/logical_name entry")
    }
  }

  private fun validateDecodedEntry(
    entry: NativeAgentLinkInventoryEntry,
    home: Path,
    managedRoots: List<Path>,
    path: Path,
  ) {
    validateEntryShape(entry, path)
    val provider = NativeAgentLinkInventoryPaths.provider(entry.provider)
    val allowedDirs = provider.homeAgentDirs(home).map { it.toAbsolutePath().normalize() }
    if (entry.installedPath.parent !in allowedDirs) {
      invalid(path, "installed_path is outside provider directory")
    }
    if (entry.installedPath.fileName.toString() != provider.fileName(entry.logicalName)) {
      invalid(path, "installed_path does not match provider/logical_name identity")
    }
    if (!isCanonicalNativeAgentArtifactTarget(home, provider, entry.logicalName, entry.cacheTargetPath, managedRoots)) {
      invalid(path, "cache_target_path does not match a trusted provider artifact")
    }
  }

  private fun validateEntryShape(
    entry: NativeAgentLinkInventoryEntry,
    path: Path,
  ) {
    if (entry.provider !in NativeAgentLinkInventoryLimits.PROVIDERS) {
      invalid(path, "unsupported provider '${entry.provider}'")
    }
    if (!entry.contentDigest.matches(Regex("[0-9a-f]{${NativeAgentLinkInventoryLimits.DIGEST_HEX_LENGTH}}"))) {
      invalid(path, "invalid content_digest")
    }
    validateAbsolutePaths(entry, path)
    validateNormalizedPaths(entry, path)
    if (!NativeAgentLinkInventoryLimits.LOGICAL_NAME.matches(entry.logicalName)) {
      invalid(path, "logical_name must be a single filename stem")
    }
  }

  private fun validateAbsolutePaths(
    entry: NativeAgentLinkInventoryEntry,
    path: Path,
  ) {
    if (!entry.installedPath.isAbsolute) invalid(path, "installed_path must be absolute")
    if (!entry.cacheTargetPath.isAbsolute) invalid(path, "cache_target_path must be absolute")
    if (
      !entry.sourceRoot.isAbsolute ||
      entry.sourceRoot.toString().length > NativeAgentLinkInventoryLimits.MAX_SOURCE_ROOT_LENGTH
    ) {
      invalid(path, "source_root must be an absolute bounded path")
    }
  }

  private fun validateNormalizedPaths(
    entry: NativeAgentLinkInventoryEntry,
    path: Path,
  ) {
    if (entry.sourceRoot != entry.sourceRoot.normalize()) invalid(path, "source_root must be normalized")
    if (entry.installedPath != entry.installedPath.normalize()) invalid(path, "installed_path must be normalized")
    if (entry.cacheTargetPath != entry.cacheTargetPath.normalize()) {
      invalid(path, "cache_target_path must be normalized")
    }
  }

  private fun <T> rethrow(error: Throwable): T = throw error

  private fun throwDecodeError(
    path: Path,
    error: Throwable,
  ): Nothing = throw decodeError(path, error.message.orEmpty(), error)

  private fun invalid(
    path: Path,
    reason: String,
  ): Nothing = throw decodeError(path, reason)

  private fun JsonNode.requiredText(
    field: String,
    path: Path,
  ): String =
    get(field)?.asText()?.takeIf(String::isNotBlank)
      ?: throw decodeError(path, "$field is required")

  private fun decodeError(
    path: Path,
    reason: String,
    cause: Throwable? = null,
  ): InvalidNativeAgentLinkInventoryDecodeError =
    InvalidNativeAgentLinkInventoryDecodeError(
      path = path.toString(),
      reason = "$reason. Delete it and reinstall.",
      cause = cause,
    )
}
