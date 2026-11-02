package skillbill.engine.featuretask.qa.checklist

import skillbill.contracts.JsonCodec
import skillbill.contracts.qa.QA_CHECKLIST_CONTRACT_VERSION
import skillbill.error.core.catchingNonCancellation
import skillbill.error.featuretask.qa.qaProgressFailed
import skillbill.ports.idestatus.model.StandaloneQaChecklistCase
import skillbill.ports.idestatus.model.StandaloneQaChecklistStep
import skillbill.contracts.qa.QaChecklistPayloadKeys as Keys

internal object QaChecklistPlanCodec {
  fun decode(json: String): List<StandaloneQaChecklistCase> {
    val parsed =
      catchingNonCancellation { JsonCodec.parseValue(json) }
        .getOrElse { failure -> throw qaProgressFailed("the plan file is not valid JSON", failure) }
    val root = exactObject(parsed, Keys.PLAN_FIELDS, "the plan")
    if (root[Keys.CONTRACT_VERSION] != QA_CHECKLIST_CONTRACT_VERSION) {
      invalid("the plan contract_version is not $QA_CHECKLIST_CONTRACT_VERSION")
    }
    val cases = nonEmptyList(root, Keys.CASES, "the plan has no cases").map(::decodeCase)
    if (cases.map { it.id }.distinct().size != cases.size) invalid("the plan repeats a case id")
    return cases
  }

  private fun decodeCase(value: Any?): StandaloneQaChecklistCase {
    val case = exactObject(value, Keys.CASE_FIELDS, "a plan case")
    return StandaloneQaChecklistCase(
      id = nonBlankText(case, Keys.ID, "a plan case has no id"),
      title = nonBlankText(case, Keys.TITLE, "a plan case has no title"),
      steps = nonEmptyList(case, Keys.STEPS, "a plan case has no steps").map(::decodeStep),
    )
  }

  private fun decodeStep(value: Any?): StandaloneQaChecklistStep {
    val step = exactObject(value, Keys.PLAN_STEP_FIELDS, "a plan step")
    return StandaloneQaChecklistStep(nonBlankText(step, Keys.TEXT, "a plan step has no text"))
  }

  private fun exactObject(
    value: Any?,
    fields: Set<String>,
    label: String,
  ): Map<String, Any?> {
    val map = JsonCodec.anyToStringAnyMap(value) ?: invalid("$label is not an object")
    if (map.keys != fields) invalid("$label does not have exactly its required keys")
    return map
  }

  private fun nonEmptyList(
    parent: Map<String, Any?>,
    key: String,
    reason: String,
  ): List<Any?> = (parent[key] as? List<*>)?.takeIf(List<*>::isNotEmpty) ?: invalid(reason)

  private fun nonBlankText(
    parent: Map<String, Any?>,
    key: String,
    reason: String,
  ): String = (parent[key] as? String)?.takeIf(String::isNotBlank) ?: invalid(reason)

  private fun invalid(reason: String): Nothing = throw qaProgressFailed(reason)
}
