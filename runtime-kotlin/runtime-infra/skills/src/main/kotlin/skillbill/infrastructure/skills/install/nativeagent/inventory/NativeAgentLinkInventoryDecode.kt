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
import skillbill.infrastructure.skills.nativeagent.support.agentObservedAttributes
import skillbill.infrastructure.skills.nativeagent.support.rethrowAgentOperationInterruption
import skillbill.install.model.SupportedAgent
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.NotLinkException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.cancellation.CancellationException

internal data class InventoryValidation(val mapper: ObjectMapper, val schema: JsonSchema)

internal object NativeAgentLinkInventoryDecode {
  fun decode(
    path: Path,
    home: Path,
    managedRoots: List<Path>,
    environment: Map<String, String>,
    validation: InventoryValidation,
  ): List<NativeAgentLinkInventoryEntry> =
    try {
      val root = readValidatedRoot(path, validation.mapper, validation.schema)
      val entries = decodeEntries(root, path)
      validateDecodedEntries(entries, home, managedRoots, environment, path)
      entries
    } catch (error: CancellationException) {
      rethrow(error)
    } catch (error: SkillBillRuntimeException) {
      error.rethrowUnless(error.isShellContentContractFailure())
      rethrow(error)
    } catch (error: IOException) {
      throwDecodeError(path, error)
    }

  fun validateSemanticEntries(
    entries: List<NativeAgentLinkInventoryEntry>,
    home: Path,
    managedRoots: List<Path>,
    environment: Map<String, String>,
  ) {
    validateDecodedEntries(entries, home, managedRoots, environment, Path.of("<semantic>"))
  }

  fun isSemanticallyValid(
    entry: NativeAgentLinkInventoryEntry,
    home: Path,
    managedRoots: List<Path>,
    environment: Map<String, String>,
  ): Boolean = inspectSemanticEntry(entry, home, managedRoots, environment, requireObserved = false)

  fun isBootstrapSemanticallyValid(
    entry: NativeAgentLinkInventoryEntry,
    home: Path,
    managedRoots: List<Path>,
    environment: Map<String, String>,
  ): Boolean = inspectSemanticEntry(entry, home, managedRoots, environment, requireObserved = true)

  private fun inspectSemanticEntry(
    entry: NativeAgentLinkInventoryEntry,
    home: Path,
    managedRoots: List<Path>,
    environment: Map<String, String>,
    requireObserved: Boolean,
  ): Boolean {
    val provider = NativeAgentLinkInventoryPaths.provider(entry.provider)
    val allowedDirs = provider.homeAgentDirs(home, environment).map { it.toAbsolutePath().normalize() }.toSet()
    val resolved = resolveSemanticTarget(entry.installedPath, provider.name, requireObserved) ?: return false
    val digest = readSemanticDigest(resolved, requireObserved) ?: return false
    val embeddedName = readEmbeddedName(resolved, entry.provider)
    return entry.installedPath.fileName.toString() == provider.fileName(entry.logicalName) &&
      entry.installedPath.parent in allowedDirs &&
      resolved == entry.cacheTargetPath &&
      isCanonicalNativeAgentArtifactTarget(home, provider, entry.logicalName, resolved, managedRoots) &&
      embeddedName == entry.logicalName &&
      digest == entry.contentDigest
  }

  private fun resolveSemanticTarget(
    installedPath: Path,
    providerName: String,
    requireObserved: Boolean,
  ): Path? {
    val linkAttributes =
      readSemanticAttributes(
        installedPath,
        followLinks = false,
        requireObserved = requireObserved,
        operation = "inspect managed $providerName agent link",
      ) ?: return null
    if (!linkAttributes.isSymbolicLink) {
      if (requireObserved) {
        invalidSemanticState(installedPath, "managed link is no longer symbolic")
      }
      return null
    }
    val resolved = semanticLinkPath(installedPath, requireObserved) ?: return null
    return verifiedSemanticTarget(resolved, requireObserved)
  }

  private fun semanticLinkPath(
    installedPath: Path,
    requireObserved: Boolean,
  ): Path? {
    val raw = readSemanticLinkTarget(installedPath, requireObserved) ?: return null
    val parent = installedPath.parent ?: return null
    return parent.resolve(raw).toAbsolutePath().normalize()
  }

  private fun verifiedSemanticTarget(
    resolved: Path,
    requireObserved: Boolean,
  ): Path? {
    val attributes =
      readSemanticAttributes(
        resolved,
        requireObserved = requireObserved,
        operation = "inspect linked native-agent artifact",
      ) ?: return null
    if (!attributes.isRegularFile) {
      if (requireObserved) invalidSemanticState(resolved, "linked artifact is no longer a regular file")
      return null
    }
    return resolved
  }

  private fun readEmbeddedName(
    path: Path,
    provider: String,
  ): String? =
    try {
      parseEmbeddedLogicalName(path, SupportedAgent.fromWire(provider))
    } catch (error: IOException) {
      rethrowAgentOperationInterruption(error)
      throw semanticReadFailure(path, "linked artifact cannot be read", error)
    } catch (error: SecurityException) {
      throw semanticReadFailure(path, "linked artifact cannot be read", error)
    }

  private fun invalidSemanticState(
    path: Path,
    reason: String,
  ): Nothing = throw semanticReadFailure(path, reason)

  private fun readSemanticAttributes(
    path: Path,
    followLinks: Boolean = true,
    requireObserved: Boolean = false,
    operation: String = "inspect native-agent inventory path",
  ): BasicFileAttributes? {
    if (requireObserved) return agentObservedAttributes(path, operation, followLinks)
    try {
      return if (followLinks) {
        Files.readAttributes(path, BasicFileAttributes::class.java)
      } else {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
      }
    } catch (error: NoSuchFileException) {
      rethrowAgentOperationInterruption(error)
      return null
    } catch (error: IOException) {
      rethrowAgentOperationInterruption(error)
      throw semanticReadFailure(path, "path cannot be inspected", error)
    } catch (error: SecurityException) {
      throw semanticReadFailure(path, "path cannot be inspected", error)
    }
  }

  private fun readSemanticLinkTarget(
    path: Path,
    requireObserved: Boolean,
  ): Path? =
    try {
      Files.readSymbolicLink(path)
    } catch (error: NoSuchFileException) {
      rethrowAgentOperationInterruption(error)
      if (requireObserved) {
        throw semanticReadFailure(path, "managed link disappeared after inspection", error)
      }
      null
    } catch (error: NotLinkException) {
      rethrowAgentOperationInterruption(error)
      if (requireObserved) {
        throw semanticReadFailure(path, "managed link changed after inspection", error)
      }
      null
    } catch (error: IOException) {
      rethrowAgentOperationInterruption(error)
      throw semanticReadFailure(path, "managed link target cannot be read", error)
    } catch (error: SecurityException) {
      throw semanticReadFailure(path, "managed link target cannot be read", error)
    }

  private fun readSemanticDigest(
    path: Path,
    requireObserved: Boolean,
  ): String? =
    try {
      sha256Hex(Files.readAllBytes(path))
    } catch (error: NoSuchFileException) {
      rethrowAgentOperationInterruption(error)
      if (requireObserved) {
        throw semanticReadFailure(path, "linked artifact disappeared after inspection", error)
      }
      null
    } catch (error: IOException) {
      rethrowAgentOperationInterruption(error)
      throw semanticReadFailure(path, "linked artifact cannot be hashed", error)
    } catch (error: SecurityException) {
      throw semanticReadFailure(path, "linked artifact cannot be hashed", error)
    }

  private fun semanticReadFailure(
    path: Path,
    reason: String,
    cause: Throwable? = null,
  ): InvalidNativeAgentLinkInventoryDecodeError = decodeError(path, reason, cause)

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
        installedPath = decodePath(node.requiredText("installed_path", path), path),
        cacheTargetPath = decodePath(node.requiredText("cache_target_path", path), path),
        contentDigest = node.requiredText("content_digest", path),
        sourceRoot = decodePath(node.requiredText("source_root", path), path),
      )
    }?.toList() ?: invalid(path, "entries is required")

  private fun validateDecodedEntries(
    entries: List<NativeAgentLinkInventoryEntry>,
    home: Path,
    managedRoots: List<Path>,
    environment: Map<String, String>,
    path: Path,
  ) {
    validateUniqueEntries(entries, path)
    entries.forEach { entry -> validateDecodedEntry(entry, home, managedRoots, environment, path) }
  }

  private fun decodePath(
    value: String,
    source: Path,
  ): Path =
    try {
      Path.of(value)
    } catch (error: InvalidPathException) {
      throwDecodeError(source, error)
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
    environment: Map<String, String>,
    path: Path,
  ) {
    validateEntryShape(entry, path)
    val provider = NativeAgentLinkInventoryPaths.provider(entry.provider)
    val allowedDirs = provider.homeAgentDirs(home, environment).map { it.toAbsolutePath().normalize() }
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
