package skillbill.infrastructure.skills.install.nativeagent.inventory

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.JsonSchema
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import skillbill.contracts.nativeagent.NATIVE_AGENT_LINK_INVENTORY_CONTRACT_VERSION
import skillbill.error.shellcontent.invalidNativeAgentLinkInventorySchema
import skillbill.infrastructure.contracts.ClasspathContractSchemaLoader
import skillbill.infrastructure.contracts.CompiledSchemaRequest
import skillbill.infrastructure.contracts.locator.NativeAgentLinkInventorySchemaPaths
import skillbill.infrastructure.skills.nativeagent.rendering.NativeAgentProvider

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
  ): List<NativeAgentLinkInventoryEntry> {
    val path = NativeAgentLinkInventoryPaths.inventoryPath(home)
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
      return sourceRoot?.let {
        NativeAgentLinkInventoryBootstrap.bootstrap(home, managedRoots, it).retain
      }.orEmpty()
    }
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw invalidNativeAgentLinkInventorySchema(
        "Invalid native-agent link inventory '$path': inventory must be a regular file. Delete it and reinstall.",
      )
    }
    return NativeAgentLinkInventoryDecode.decode(path, home, managedRoots, mapper, schema)
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
