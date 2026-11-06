package skillbill.engine.goalrunner.history

import skillbill.goalrunner.model.history.GoalHistoryWriterInput
import java.security.MessageDigest

internal fun goalHistoryFingerprint(
  input: GoalHistoryWriterInput,
  preWriterTree: String,
): String {
  val values =
    buildList {
      add(input.parentWorkflowId)
      input.entries.forEach { entry ->
        add(entry.subtaskId.toString())
        add(entry.childWorkflowId)
        add(entry.definitionId)
        add(entry.legacyDisposition.orEmpty())
        entry.draft?.let { draft ->
          add(draft.disposition.wireValue)
          add(draft.boundary.orEmpty())
          add(draft.history.orEmpty())
          add(draft.decisions.orEmpty())
          add(draft.skipReason.orEmpty())
        }
      }
      input.repairContext.outputs.forEach { output ->
        add(output.phaseId)
        add(output.invocationId)
        add(output.occurrence.toString())
        add(output.output)
      }
      add(input.repairContext.warning.orEmpty())
      add(preWriterTree)
    }
  val digest = MessageDigest.getInstance("SHA-256")
  values.forEach { value ->
    val bytes = value.toByteArray(Charsets.UTF_8)
    digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
    digest.update(byteArrayOf(0))
    digest.update(bytes)
  }
  return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
