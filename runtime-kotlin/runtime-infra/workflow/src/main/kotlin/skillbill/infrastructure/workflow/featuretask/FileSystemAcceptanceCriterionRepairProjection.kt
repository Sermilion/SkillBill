package skillbill.infrastructure.workflow.featuretask

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.JsonCodec
import skillbill.ports.taskruntime.AcceptanceCriterionRepairProjection
import skillbill.ports.taskruntime.model.AcceptanceCriterionRepairProjectionRequest
import skillbill.workflow.taskruntime.artifact.asRepairWireMap
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

@Inject
class FileSystemAcceptanceCriterionRepairProjection : AcceptanceCriterionRepairProjection {
  override fun write(request: AcceptanceCriterionRepairProjectionRequest) {
    val key =
      MessageDigest.getInstance("SHA-256").digest(request.workflowId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    val directory = request.repositoryRoot.resolve(".skill-bill/acceptance-repair")
    Files.createDirectories(directory)
    val temporary = Files.createTempFile(directory, "$key-", ".tmp")
    try {
      Files.writeString(temporary, JsonCodec.valueToJsonString(request.state.asRepairWireMap()))
      Files.move(
        temporary,
        directory.resolve("$key.json"),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
      )
    } catch (failure: IOException) {
      try {
        Files.deleteIfExists(temporary)
      } catch (cleanup: IOException) {
        failure.addSuppressed(cleanup)
      }
      throw failure
    }
  }
}
