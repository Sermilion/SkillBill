package skillbill.infrastructure.skills.install.nativeagent.inventory

import skillbill.error.core.InvalidNativeAgentLinkInventoryReconcileError
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import java.io.IOException
import java.nio.file.Path

internal fun reconcileNativeAgentLinkInventoryLocked(request: NativeAgentLinkInventoryLockedReconcileRequest) {
  val trustedRoots = NativeAgentLinkInventoryPaths.canonicalManagedCacheRoots(request.home, request.managedRoots)
  val currentExists = NativeAgentLinkInventory.inventoryPathExists(request.path)
  val current =
    if (currentExists) {
      NativeAgentLinkInventory.read(request.home, trustedRoots, request.sourceRoot, request.environment)
    } else {
      emptyList()
    }
  verifyExpectedInventory(request, currentExists, current)
  val previous =
    if (currentExists) current else bootstrapPreviousNativeAgentLinkInventory(request, trustedRoots)
  val desiredPaths = request.desired.map { it.installedPath.normalize() }.toSet()
  previous.filter { it.provider == request.provider && it.installedPath.normalize() !in desiredPaths }
    .forEach { stale ->
      NativeAgentLinkInventoryBootstrap.removeIfStillManaged(stale, request.home, trustedRoots, request.beforeMutation)
    }
  val retained = previous.filter { it.provider != request.provider }
  try {
    NativeAgentLinkInventoryWrite.write(
      NativeAgentLinkInventoryWriteRequest(
        path = request.path,
        entries = (retained + request.desired).sortedWith(compareBy({ it.provider }, { it.installedPath.toString() })),
        home = request.home,
        managedRoots = trustedRoots,
        environment = request.environment,
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

private fun verifyExpectedInventory(
  request: NativeAgentLinkInventoryLockedReconcileRequest,
  currentExists: Boolean,
  current: List<NativeAgentLinkInventoryEntry>,
) {
  if (currentExists != request.expectedInventoryPresent || current != request.expectedPrevious) {
    throw reconcileError(
      request.path,
      "native-agent inventory changed after link ownership was read; retry installation",
    )
  }
}

private fun bootstrapPreviousNativeAgentLinkInventory(
  request: NativeAgentLinkInventoryLockedReconcileRequest,
  trustedRoots: List<Path>,
): List<NativeAgentLinkInventoryEntry> {
  val bootstrap =
    NativeAgentLinkInventoryBootstrap.bootstrap(request.home, trustedRoots, request.sourceRoot, request.environment)
  bootstrap.remove.filter { it.provider == request.provider }.forEach { entry ->
    NativeAgentLinkInventoryBootstrap.removeIfStillManaged(entry, request.home, trustedRoots, request.beforeMutation)
  }
  return bootstrap.retain
}

private fun reconcileError(
  path: Path,
  reason: String,
  cause: Throwable? = null,
): InvalidNativeAgentLinkInventoryReconcileError =
  InvalidNativeAgentLinkInventoryReconcileError(path = path.toString(), reason = reason, cause = cause)
