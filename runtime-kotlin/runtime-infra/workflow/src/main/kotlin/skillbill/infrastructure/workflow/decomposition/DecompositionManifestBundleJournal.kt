package skillbill.infrastructure.workflow.decomposition

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import skillbill.contracts.decomposition.BUNDLE_JOURNAL_CONTRACT_VERSION
import skillbill.infrastructure.contracts.sha256Hex
import skillbill.infrastructure.host.jvm.JdkHostPlatformPort
import skillbill.infrastructure.host.jvm.atomicWriteString
import skillbill.infrastructure.workflow.review.broker.digest
import skillbill.ports.system.HostPlatformPort
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

internal class DecompositionManifestBundleJournal(
  internal val hostPlatform: HostPlatformPort = JdkHostPlatformPort,
) {
  private val yamlMapper = YAMLMapper()

  fun create(
    parent: Path,
    writes: List<Pair<Path, String>>,
  ): DecompositionManifestBundleTransaction =
    DecompositionManifestBundleJournalCreate.create(parent, writes, yamlMapper, this)

  fun apply(transaction: DecompositionManifestBundleTransaction) =
    DecompositionManifestBundleJournalIo.apply(transaction)

  fun recoverPending(parent: Path?) = DecompositionManifestBundleJournalRecovery.recoverPending(parent, this)

  fun recoverPendingUnlocked(parent: Path?) =
    DecompositionManifestBundleJournalRecovery.recoverPendingUnlocked(parent, this)

  fun failIfPending(parent: Path?) = DecompositionManifestBundleJournalRecovery.failIfPending(parent)

  fun cleanup(transaction: DecompositionManifestBundleTransaction) =
    DecompositionManifestBundleJournalIo.cleanup(transaction)

  fun writeAtomically(
    target: Path,
    content: String,
  ) {
    atomicWriteString(target, content)
  }

  internal fun read(marker: Path): DecompositionManifestBundleTransaction =
    DecompositionManifestBundleJournalIo.read(marker)

  internal companion object {
    const val BUNDLE_CONTRACT_VERSION = BUNDLE_JOURNAL_CONTRACT_VERSION
    const val BUNDLE_PREFIX = ".decomposition-manifest-bundle-"
    const val MARKER_SUFFIX = ".commit"
    const val STAGING_SUFFIX = ".staging"
  }
}

private val processBundleLocks = ConcurrentHashMap<Path, ReentrantLock>()

internal fun <T> withDecompositionManifestBundleLock(
  parent: Path?,
  hostPlatform: HostPlatformPort = JdkHostPlatformPort,
  action: () -> T,
): T {
  if (parent == null) return action()
  val normalizedParent = parent.toAbsolutePath().normalize()
  val lockPath = decompositionManifestLockPath(normalizedParent, hostPlatform)
  val processLock = processBundleLocks.computeIfAbsent(lockPath) { ReentrantLock() }
  val outermost = !processLock.isHeldByCurrentThread
  processLock.lock()
  try {
    if (!outermost) return action()
    Files.createDirectories(requireNotNull(lockPath.parent))
    return FileChannel.open(
      lockPath,
      StandardOpenOption.CREATE,
      StandardOpenOption.WRITE,
    ).use { channel ->
      channel.lock().use { action() }
    }
  } finally {
    processLock.unlock()
  }
}

private fun decompositionManifestLockPath(
  parent: Path,
  hostPlatform: HostPlatformPort,
): Path {
  val owner = lockOwner(parent)
  val digest = sha256Hex(owner.toString().toByteArray(Charsets.UTF_8))
  return hostPlatform.resolveTemporaryDirectory()
    .resolve(LOCK_DIRECTORY_NAME)
    .resolve("$digest$LOCK_FILE_SUFFIX")
}

internal fun cleanupValidatedBundleJournal(transaction: DecompositionManifestBundleTransaction) =
  DecompositionManifestBundleJournalIo.cleanupValidated(transaction)

private fun lockOwner(parent: Path): Path {
  var current = parent
  while (true) {
    if (current.fileName?.toString() == FEATURE_SPECS_DIRECTORY_NAME) return current
    current = current.parent ?: return parent
  }
}

private const val FEATURE_SPECS_DIRECTORY_NAME = ".feature-specs"
private const val LOCK_DIRECTORY_NAME = "skill-bill-decomposition-manifest-locks"
private const val LOCK_FILE_SUFFIX = ".lock"
