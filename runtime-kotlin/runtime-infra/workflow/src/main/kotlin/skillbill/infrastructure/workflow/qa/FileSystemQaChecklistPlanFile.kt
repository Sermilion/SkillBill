package skillbill.infrastructure.workflow.qa

import me.tatarka.inject.annotations.Inject
import skillbill.contracts.qa.QA_CHECKLIST_MAX_ENCODED_BYTES
import skillbill.ports.qa.QaChecklistPlanFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

@Inject
class FileSystemQaChecklistPlanFile : QaChecklistPlanFile {
  override fun read(path: Path): String? =
    Files.newInputStream(path).use { input ->
      val bytes = input.readNBytes(QA_CHECKLIST_MAX_ENCODED_BYTES + 1)
      if (bytes.size > QA_CHECKLIST_MAX_ENCODED_BYTES) {
        null
      } else {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
      }
    }
}
