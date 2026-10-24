package skillbill.infrastructure.workflow.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.error.core.SkillBillRuntimeException
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.projectstore.ProjectStorePort
import skillbill.ports.taskruntime.AcceptanceCriterionRepairProjection
import skillbill.ports.taskruntime.model.AcceptanceCriterionRepairProjectionRequest
import skillbill.workflow.taskruntime.artifact.asRepairWireMap
import skillbill.workflow.taskruntime.artifact.toAcceptanceCriterionRepairState
import skillbill.workflow.taskruntime.model.core.FeatureTaskRuntimeWorkflowArtifactMap
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import skillbill.contracts.workflow.featuretask.AcceptanceCriterionRepairPayloadKeys as Keys

@Inject
class FileSystemAcceptanceCriterionRepairProjection(
  private val diagnostics: RuntimeDiagnostics,
  private val projectStore: ProjectStorePort,
) : AcceptanceCriterionRepairProjection {
  override fun write(request: AcceptanceCriterionRepairProjectionRequest) {
    val key =
      MessageDigest.getInstance("SHA-256").digest(request.workflowId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    val directory = projectStore.resolve(request.repositoryRoot).acceptanceRepairRoot
    Files.createDirectories(directory)
    val target = directory.resolve("$key.json")
    val temporary = Files.createTempFile(directory, "$key-", ".tmp")
    try {
      val requestedJson = JsonCodec.valueToJsonString(request.state.asRepairWireMap())
      Files.writeString(temporary, requestedJson)
      FileChannel.open(directory.resolve("$key.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use {
          channel ->
        acquireLock(channel).use {
          if (isNewerOrConflicting(target, request.state.revision, requestedJson)) {
            throw IOException("Serial repair projection refused an older or conflicting revision.")
          }
          Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
      }
    } catch (failure: IOException) {
      try {
        Files.deleteIfExists(temporary)
      } catch (cleanup: IOException) {
        failure.addSuppressed(cleanup)
      }
      throw failure
    }
  }

  private fun isNewerOrConflicting(
    target: Path,
    requestedRevision: Long,
    requestedJson: String,
  ): Boolean {
    val current = readProjection(target) ?: return false
    val revision = (current[Keys.REVISION] as Number).toLong()
    return revision > requestedRevision ||
      revision == requestedRevision && JsonCodec.valueToJsonString(current) != requestedJson
  }

  private fun readProjection(target: Path): FeatureTaskRuntimeWorkflowArtifactMap? {
    if (!Files.isRegularFile(target)) return null
    return try {
      FeatureTaskRuntimeWorkflowArtifactMap.from(JsonCodec.parseValue(Files.readString(target))).also {
        it.toAcceptanceCriterionRepairState(target.toString())
      }
    } catch (failure: SkillBillRuntimeException) {
      diagnostics.warning(
        "seam=serial_repair_projection record_kind=recovery value_expected=valid_projection value_used=regenerated",
        failure,
      )
      null
    }
  }

  private fun acquireLock(channel: FileChannel): FileLock =
    try {
      channel.tryLock() ?: throw IOException("Serial repair projection is locked by another writer.")
    } catch (failure: OverlappingFileLockException) {
      throw IOException("Serial repair projection is locked by another local writer.", failure)
    }
}
