package skillbill.infrastructure.sqlite.core.schema

import skillbill.error.shellcontent.invalidIdeStatusSchemaError
import skillbill.workflow.taskruntime.model.skeleton.LAUNCH_MODEL_TOKEN_PATTERN
import java.sql.ResultSet

internal const val REPORTED_MODEL_COLUMN: String = "reported_model"

internal fun ResultSet.readStandaloneReportedModel(): String? {
  val raw = getString(REPORTED_MODEL_COLUMN) ?: return null
  if (!LAUNCH_MODEL_TOKEN_PATTERN.matches(raw)) {
    throw invalidIdeStatusSchemaError("standalone-status", REPORTED_MODEL_COLUMN, "Invalid reported model identity.")
  }
  return raw
}
