package skillbill.workflow.model

enum class ValidationDepth(val wireValue: String) {
  FULL("full"),
  ;

  companion object {
    val DEFAULT: ValidationDepth = FULL

    private const val RETIRED_BUILD_ONLY_WIRE_VALUE: String = "build_only"

    private val DECODABLE_WIRE_VALUES: Map<String, ValidationDepth> =
      entries.associateBy(ValidationDepth::wireValue) + (RETIRED_BUILD_ONLY_WIRE_VALUE to FULL)

    fun fromWireOrNull(value: String): ValidationDepth? = DECODABLE_WIRE_VALUES[value]

    fun unknownWireValueMessage(value: String): String =
      "Unknown validation depth '$value'. Allowed: ${entries.joinToString { it.wireValue }}."

    fun fromWire(value: String): ValidationDepth =
      requireNotNull(fromWireOrNull(value)) { unknownWireValueMessage(value) }
  }
}
