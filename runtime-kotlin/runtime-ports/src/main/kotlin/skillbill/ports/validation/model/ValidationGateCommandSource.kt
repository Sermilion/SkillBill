package skillbill.ports.validation.model

enum class ValidationGateCommandSource(val wireValue: String) {
  LEGACY_PACK("legacy_pack"),
  PROJECT_CONFIG("project_config"),
  ;

  companion object {
    fun fromWire(value: String): ValidationGateCommandSource? = entries.firstOrNull { it.wireValue == value }
  }
}
