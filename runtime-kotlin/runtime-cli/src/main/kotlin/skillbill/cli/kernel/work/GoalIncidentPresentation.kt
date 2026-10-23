package skillbill.cli.kernel.work

import skillbill.engine.work.IdeStatusService
import skillbill.error.core.SkillBillRuntimeException
import skillbill.goalrunner.model.GoalRunnerStatusProjection
import skillbill.ports.idestatus.model.WorkDetailItem
import skillbill.ports.idestatus.model.WorkDetailRequest
import java.io.IOException

internal object GoalIncidentPresentationKeys {
  const val INCIDENT_DETAILS = "incident_details"
  const val OWNER = "owner"
  const val RECOVERY_WORK_ID = "recovery_work_id"
  const val DETAIL = "detail"
  const val PROBLEM = "problem"
}

internal data class GoalIncidentDetail(
  val owner: String,
  val item: WorkDetailItem?,
  val snapshot: Map<String, Any?> = emptyMap(),
  val problem: String? = null,
)

internal fun GoalRunnerStatusProjection?.goalIncidentReferences(): List<Pair<String, String?>> =
  this?.let { listOf("parent" to it.parentWorkflowId, "child" to it.currentChildWorkflowId) }.orEmpty()

internal fun readGoalIncidentDetails(
  service: IdeStatusService,
  references: List<Pair<String, String?>>,
  retainFailures: Boolean = false,
): List<GoalIncidentDetail> =
  references.filter { !it.second.isNullOrBlank() }
    .groupBy { it.second!! }
    .map { (id, owners) ->
      val owner = owners.joinToString("+") { it.first }
      try {
        val result = service.detail(WorkDetailRequest(id))
        GoalIncidentDetail(
          owner,
          result.item,
          result.item?.let { service.toWireMap(it.snapshot) }.orEmpty(),
          result.problem?.let { "${it.code.wireValue}: ${it.message}" },
        )
      } catch (error: SkillBillRuntimeException) {
        if (!retainFailures) throw error
        GoalIncidentDetail(owner, null, problem = "${error.code}: ${error.message.orEmpty()}")
      } catch (error: IOException) {
        if (!retainFailures) throw error
        GoalIncidentDetail(owner, null, problem = "read_failed: ${error.message.orEmpty()}")
      }
    }

internal fun List<GoalIncidentDetail>.incidentText(verbose: Boolean): List<String> =
  flatMap { detail ->
    when {
      detail.item == null ->
        listOf("Incident detail unavailable (${detail.owner})${detail.problem?.let { ": $it" }.orEmpty()}")
      detail.item.incident == null -> emptyList()
      else -> listOf("Incident (${detail.owner}):") + incidentTextLines(detail.item, verbose)
    }
  }

internal fun List<GoalIncidentDetail>.incidentPayload(): List<Map<String, Any?>> =
  map { detail ->
    linkedMapOf<String, Any?>(
      GoalIncidentPresentationKeys.OWNER to detail.owner,
    ).apply {
      detail.item?.let { item ->
        put(GoalIncidentPresentationKeys.RECOVERY_WORK_ID, item.recoveryWorkId)
        put(GoalIncidentPresentationKeys.DETAIL, workDetailItemWireMap(item, detail.snapshot))
      }
      detail.problem?.let { put(GoalIncidentPresentationKeys.PROBLEM, it) }
    }
  }

internal fun MutableMap<String, Any?>.putIncidentDetails(details: List<GoalIncidentDetail>) {
  put(GoalIncidentPresentationKeys.INCIDENT_DETAILS, details.incidentPayload())
}
