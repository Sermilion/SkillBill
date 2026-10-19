package skillbill.workflow.verify.model

enum class VerifyQaSelection(val wireValue: String) {
  ON("on"),
  OFF("off"),
  ;

  companion object {
    val EXPECTED: String = entries.joinToString("|") { it.wireValue }

    fun fromWire(value: String?): VerifyQaSelection? = entries.firstOrNull { it.wireValue == value }

    fun requested(raw: String?): VerifyQaSelection = fromWire(raw?.trim()) ?: OFF
  }
}
