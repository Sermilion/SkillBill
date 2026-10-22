package skillbill.di.featuretask

import skillbill.engine.featuretask.slot.ModelDirectiveSupplements
import skillbill.error.shellcontent.invalidModelDirectiveSupplementsSetting

internal object ModelDirectiveSupplementsSetting {
  const val KEY = "SKILL_BILL_MODEL_DIRECTIVE_SUPPLEMENTS"

  fun resolve(environment: Map<String, String>): ModelDirectiveSupplements {
    val value = environment[KEY] ?: return ModelDirectiveSupplements(enabled = true)
    return when (value) {
      "true" -> ModelDirectiveSupplements(enabled = true)
      "false" -> ModelDirectiveSupplements(enabled = false)
      else -> throw invalidModelDirectiveSupplementsSetting(KEY, value)
    }
  }
}
