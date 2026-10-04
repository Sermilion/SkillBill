package skillbill.infrastructure.skills.install.nativeagent.inventory

import skillbill.error.core.InvalidNativeAgentLinkInventoryReconcileError
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal fun reconcileNativeAgentLinkInventoryLocked(request: NativeAgentLinkInventoryLockedReconcileRequest) {
  val trustedRoots = NativeAgentLinkInventoryPaths.canonicalManagedCacheRoots(request.home, request.managedRoots)
  val previous =
    loadPreviousNativeAgentLinkInventory(
      request.path,
      request.home,
      trustedRoots,
      request.sourceRoot,
      request.beforeMutation,
    )
  val desiredPaths = request.desired.map { it.installedPath.normalize() }.toSet()
  previous.filter { it.provider == request.provider && it.installedPath.normalize() !in desiredPaths }
    .forEach { stale ->
      NativeAgentLinkInventoryBootstrap.removeIfStillManaged(stale, request.home, trustedRoots, request.beforeMutation)
    }
  val retained =
    previous.filter { it.provider != request.provider }.filter { entry ->
      if (NativeAgentLinkInventoryDecode.isSemanticallyValid(entry, request.home, trustedRoots)) {
        true
      } else {
        NativeAgentLinkInventoryBootstrap.removeIfStillManaged(
          entry,
          request.home,
          trustedRoots,
          request.beforeMutation,
        )
        false
      }
    }
  try {
    NativeAgentLinkInventoryWrite.write(
      NativeAgentLinkInventoryWriteRequest(
        path = request.path,
        entries = (retained + request.desired).sortedWith(compareBy({ it.provider }, { it.installedPath.toString() })),
        home = request.home,
        managedRoots = trustedRoots,
        mapper = request.mapper,
        schema = request.schema,
        beforeMutation = request.beforeMutation,
        afterTemporaryCreation = request.afterTemporaryCreation,
      ),
    )
  } catch (error: SkillBillRuntimeException) {
    error.rethrowUnless(error.isShellContentContractFailure())
    throw reconcileError(request.path, error.message.orEmpty(), error)
  } catch (error: IOException) {
    throw reconcileError(request.path, error.message.orEmpty(), error)
  }
}

private fun reconcileError(
  path: Path,
  reason: String,
  cause: Throwable? = null,
): InvalidNativeAgentLinkInventoryReconcileError =
  InvalidNativeAgentLinkInventoryReconcileError(path = path.toString(), reason = reason, cause = cause)

private fun loadPreviousNativeAgentLinkInventory(
  path: Path,
  home: Path,
  trustedRoots: List<Path>,
  sourceRoot: Path,
  beforeMutation: (Path) -> Unit,
): List<NativeAgentLinkInventoryEntry> =
  if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
    NativeAgentLinkInventory.read(home, trustedRoots, sourceRoot)
  } else {
    val bootstrap = NativeAgentLinkInventoryBootstrap.bootstrap(home, trustedRoots, sourceRoot)
    bootstrap.remove.forEach { remove ->
      NativeAgentLinkInventoryBootstrap.removeIfStillManaged(remove, home, trustedRoots, beforeMutation)
    }
    bootstrap.retain
  }
