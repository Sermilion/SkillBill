package skillbill.workflow.taskruntime.model.skeleton

import skillbill.error.featuretask.qa.unknownQaSelection

enum class FeatureTaskRuntimeQaSelection(val wireValue: String) {
  AUTO("auto"),
  OFF("off"),
  ;

  companion object {
    fun fromWire(value: String): FeatureTaskRuntimeQaSelection =
      entries.firstOrNull { it.wireValue == value } ?: throw unknownQaSelection(value, entries.map { it.wireValue })
  }
}
