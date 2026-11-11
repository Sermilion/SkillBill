package skillbill.infrastructure.skills.install.nativeagent.inventory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import skillbill.contracts.nativeagent.NATIVE_AGENT_LINK_INVENTORY_CONTRACT_VERSION
import skillbill.error.core.InvalidNativeAgentLinkInventoryReconcileError
import skillbill.error.shellcontent.invalidNativeAgentLinkInventorySchema
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.NativeAgentLinkInventorySchemaPaths
import skillbill.infrastructure.contracts.sha256Hex
import skillbill.infrastructure.host.jvm.resolveEnvironmentMap
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

data class NativeAgentLinkInventoryEntry(
  val logicalName: String,
  val provider: String,
  val installedPath: Path,
  val cacheTargetPath: Path,
  val contentDigest: String,
  val sourceRoot: Path,
)

object NativeAgentLinkInventory {
  private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
  private val schema: JsonSchema
    get() =
      ClasspathContractSchemaLoader.compiledSchema(
        CompiledSchemaRequest(
          cacheKey = NativeAgentLinkInventorySchemaPaths.CLASSPATH_RESOURCE,
          classLoader = javaClass.classLoader,
          classpathResource = NativeAgentLinkInventorySchemaPaths.CLASSPATH_RESOURCE,
          missingResource = {
            invalidNativeAgentLinkInventorySchema(
              "Canonical native-agent link inventory schema resource is missing from the classpath.",
            )
          },
          processingFailure = { cause ->
            invalidNativeAgentLinkInventorySchema(
              cause.message ?: cause::class.simpleName.orEmpty(),
              cause,
            )
          },
          loadFailureLogger = {},
          expectedSchemaId = NativeAgentLinkInventorySchemaPaths.EXPECTED_SCHEMA_ID,
          expectedContractVersion = NATIVE_AGENT_LINK_INVENTORY_CONTRACT_VERSION,
          identityFailure = { reason -> invalidNativeAgentLinkInventorySchema(reason) },
        ),
      )

  internal fun reconcile(request: NativeAgentLinkInventoryReconcileRequest) {
    val path = NativeAgentLinkInventoryPaths.inventoryPath(request.home)
    val environment = request.environment
    Files.createDirectories(requireNotNull(path.parent))
    FileChannel.open(
      lockPath(path),
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
    ).use { channel ->
      channel.lock().use {
        reconcileNativeAgentLinkInventoryLocked(
          NativeAgentLinkInventoryLockedReconcileRequest(
            path = path,
            home = request.home,
            provider = request.provider,
            desired = request.desired,
            managedRoots = request.managedRoots,
            sourceRoot = request.sourceRoot,
            environment = environment,
            expectedPrevious = request.expectedPrevious,
            expectedInventoryPresent = request.expectedInventoryPresent,
            mapper = mapper,
            schema = schema,
            beforeMutation = request.beforeMutation,
            afterTemporaryCreation = request.afterTemporaryCreation,
          ),
        )
      }
    }
  }

  fun read(
    home: Path,
    managedRoots: List<Path>,
    sourceRoot: Path? = null,
  ): List<NativeAgentLinkInventoryEntry> = read(home, managedRoots, sourceRoot, resolveEnvironmentMap(emptyMap()))

  fun read(
    home: Path,
    managedRoots: List<Path>,
    sourceRoot: Path? = null,
    environment: Map<String, String>,
  ): List<NativeAgentLinkInventoryEntry> {
    val path = NativeAgentLinkInventoryPaths.inventoryPath(home)
    if (!inventoryPathExists(path)) {
      return sourceRoot?.let {
        NativeAgentLinkInventoryBootstrap.bootstrap(home, managedRoots, it, environment).retain
      }.orEmpty()
    }
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw invalidNativeAgentLinkInventorySchema(
        "Invalid native-agent link inventory '$path': inventory must be a regular file. Delete it and reinstall.",
      )
    }
    return NativeAgentLinkInventoryDecode.decode(
      path,
      home,
      managedRoots,
      environment,
      InventoryValidation(mapper, schema),
    )
  }

  fun readSelectedArtifact(entry: NativeAgentLinkInventoryEntry): ByteArray {
    val target = verifiedSelectedTarget(entry)
    val bytes = Files.readAllBytes(target)
    if (sha256Hex(bytes) != entry.contentDigest) {
      throw invalidNativeAgentLinkInventorySchema("The selected native worker artifact digest is stale. Reinstall it.")
    }
    return bytes
  }

  private fun verifiedSelectedTarget(entry: NativeAgentLinkInventoryEntry): Path {
    if (!Files.isSymbolicLink(entry.installedPath)) {
      throw invalidNativeAgentLinkInventorySchema("The selected native worker managed link is missing. Reinstall it.")
    }
    val target = entry.installedPath.toRealPath()
    if (target != entry.cacheTargetPath.toRealPath() || !Files.isReadable(target)) {
      throw invalidNativeAgentLinkInventorySchema("The selected native worker managed link is stale. Reinstall it.")
    }
    return target
  }

  internal fun inventoryPathExists(path: Path): Boolean =
    try {
      Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
      true
    } catch (_: NoSuchFileException) {
      false
    } catch (error: IOException) {
      throw invalidNativeAgentLinkInventorySchema(
        "Native-agent link inventory '$path' cannot be inspected.",
        error,
      )
    } catch (error: SecurityException) {
      throw invalidNativeAgentLinkInventorySchema(
        "Native-agent link inventory '$path' cannot be inspected.",
        error,
      )
    }

  internal fun removeEntries(
    home: Path,
    removed: List<NativeAgentLinkInventoryEntry>,
    environment: Map<String, String>,
    managedRoots: List<Path>,
    beforeMutation: (Path) -> Unit,
  ) {
    if (removed.isEmpty()) return
    val path = NativeAgentLinkInventoryPaths.inventoryPath(home)
    beforeMutation(requireNotNull(path.parent))
    Files.createDirectories(path.parent)
    FileChannel.open(lockPath(path), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
      channel.lock().use {
        if (!inventoryPathExists(path)) return
        val current = read(home, managedRoots, environment = environment)
        val removedByPath = removed.associateBy { it.provider to it.installedPath.toAbsolutePath().normalize() }
        val currentByPath = current.associateBy { it.provider to it.installedPath.toAbsolutePath().normalize() }
        removedByPath.forEach { (key, expected) ->
          if (currentByPath[key] != expected) {
            throw InvalidNativeAgentLinkInventoryReconcileError(
              path = path.toString(),
              reason = "native-agent inventory changed during unlink; retry cleanup",
            )
          }
        }
        val entries =
          current.filterNot { entry ->
            val key = entry.provider to entry.installedPath.toAbsolutePath().normalize()
            val expected = removedByPath[key]
            expected != null && entry == expected
          }
        NativeAgentLinkInventoryWrite.write(
          NativeAgentLinkInventoryWriteRequest(
            path = path,
            entries = entries,
            home = home,
            managedRoots = managedRoots,
            environment = environment,
            mapper = mapper,
            schema = schema,
            beforeMutation = beforeMutation,
            afterTemporaryCreation = {},
          ),
        )
      }
    }
  }

  private fun lockPath(inventoryPath: Path): Path = inventoryPath.resolveSibling("${inventoryPath.fileName}.lock")
}

internal fun isCanonicalNativeAgentArtifactTarget(
  home: Path,
  provider: NativeAgentProvider,
  logicalName: String,
  target: Path,
  currentRoots: List<Path>,
): Boolean {
  val normalized = target.toAbsolutePath().normalize()
  val root = normalized.parent?.parent ?: return false
  if (normalized != provider.cacheArtifactPath(root, logicalName)) return false
  if (root in currentRoots.map { it.toAbsolutePath().normalize() }) return true
  return matchesManagedNativeAgentCacheRoot(home, root, logicalName)
}

private fun matchesManagedNativeAgentCacheRoot(
  home: Path,
  root: Path,
  logicalName: String,
): Boolean {
  val normalizedHome = home.toAbsolutePath().normalize()
  val parent = root.parent ?: return false
  val leaf = root.fileName.toString()
  return when (parent) {
    normalizedHome.resolve(".skill-bill/installed-skills") ->
      leaf.startsWith("native-agents-") &&
        NativeAgentLinkInventoryLimits.CACHE_GENERATION.matches(leaf.removePrefix("native-agents-"))
    normalizedHome.resolve(".skill-bill/native-agents") ->
      LEGACY_CACHE_GENERATION.matches(leaf)
    else -> isLegacyGeneratedRepositoryArtifactTarget(root, logicalName)
  }
}

private fun isLegacyGeneratedRepositoryArtifactTarget(
  root: Path,
  logicalName: String,
): Boolean {
  val owner = root.fileName.toString()
  val authoredSurface = root.parent
  val matchesOwner = logicalName == owner || logicalName.startsWith("$owner-")
  val isBaseSkill = authoredSurface?.fileName?.toString() == "skills"
  val isPlatformReview =
    authoredSurface?.fileName?.toString() == "code-review" &&
      authoredSurface.parent?.parent?.fileName?.toString() == "platform-packs"
  return matchesOwner && (isBaseSkill || isPlatformReview)
}

private val LEGACY_CACHE_GENERATION = Regex("[a-z0-9](?:[a-z0-9-]{0,126}[a-z0-9])?")
