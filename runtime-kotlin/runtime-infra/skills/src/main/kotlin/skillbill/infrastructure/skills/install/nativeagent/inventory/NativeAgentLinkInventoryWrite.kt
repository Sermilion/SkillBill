package skillbill.infrastructure.skills.install.nativeagent.inventory

import skillbill.contracts.SharedPayloadKeys
import skillbill.contracts.nativeagent.NATIVE_AGENT_LINK_INVENTORY_CONTRACT_VERSION
import skillbill.error.core.InvalidNativeAgentLinkInventoryWriteError
import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.core.rethrowUnless
import skillbill.error.shellcontent.isShellContentContractFailure
import skillbill.infrastructure.host.jvm.atomicMoveReplacing
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

internal object NativeAgentLinkInventoryWrite {
  fun write(request: NativeAgentLinkInventoryWriteRequest) {
    request.path.parent?.let { parent ->
      journalMissingAncestors(parent, request.beforeMutation)
      Files.createDirectories(parent)
    }
    val root =
      request.mapper.createObjectNode()
        .put(SharedPayloadKeys.CONTRACT_VERSION, NATIVE_AGENT_LINK_INVENTORY_CONTRACT_VERSION)
    val array = root.putArray("entries")
    request.entries.forEach { entry ->
      array.addObject()
        .put("logical_name", entry.logicalName)
        .put("provider", entry.provider)
        .put("installed_path", entry.installedPath.toAbsolutePath().normalize().toString())
        .put("cache_target_path", entry.cacheTargetPath.toAbsolutePath().normalize().toString())
        .put("content_digest", entry.contentDigest)
        .put("source_root", entry.sourceRoot.toAbsolutePath().normalize().toString())
    }
    val bytes = request.mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root)
    if (bytes.size > NativeAgentLinkInventoryLimits.MAX_BYTES) {
      invalidWrite(
        request.path,
        "native-agent link inventory exceeds ${NativeAgentLinkInventoryLimits.MAX_BYTES} bytes",
      )
    }
    val schemaErrors = request.schema.validate(request.mapper.readTree(bytes))
    if (schemaErrors.isNotEmpty()) {
      invalidWrite(request.path, schemaErrors.joinToString("; ") { it.message })
    }
    NativeAgentLinkInventoryDecode.validateSemanticEntries(request.entries, request.home, request.managedRoots)
    val temporary = Files.createTempFile(request.path.parent, "${request.path.fileName}.", ".tmp")
    request.afterTemporaryCreation(temporary)
    var initiatingFailure: Throwable? = null
    try {
      Files.write(temporary, bytes)
      request.beforeMutation(request.path)
      atomicMoveReplacing(temporary, request.path)
    } catch (error: CancellationException) {
      throw error
    } catch (error: SkillBillRuntimeException) {
      error.rethrowUnless(error.isShellContentContractFailure())
      initiatingFailure = error
    } catch (error: IOException) {
      initiatingFailure = writeError(request.path, error.message.orEmpty(), error)
    }
    val cleanupFailure = runCatching { Files.deleteIfExists(temporary) }.exceptionOrNull()
    cleanupFailure?.let { initiatingFailure?.addSuppressed(it) }
    val terminalFailure = initiatingFailure ?: cleanupFailure
    terminalFailure?.let { throw it }
  }

  private fun journalMissingAncestors(
    path: Path,
    beforeMutation: (Path) -> Unit,
  ) {
    val missing =
      generateSequence(path.toAbsolutePath().normalize()) { it.parent }
        .takeWhile { !Files.exists(it, LinkOption.NOFOLLOW_LINKS) }.toList().asReversed()
    missing.forEach(beforeMutation)
  }

  private fun writeError(
    path: Path,
    reason: String,
    cause: Throwable? = null,
  ): InvalidNativeAgentLinkInventoryWriteError =
    InvalidNativeAgentLinkInventoryWriteError(path = path.toString(), reason = reason, cause = cause)

  private fun invalidWrite(
    path: Path,
    reason: String,
  ): Nothing = throw writeError(path, reason)
}
