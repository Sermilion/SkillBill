package skillbill.engine.operation.release

import skillbill.engine.operation.core.MissingReleaseBumpError

enum class ReleaseBump(val wireValue: String) {
  PATCH("patch"),
  MINOR("minor"),
  MAJOR("major"),
  ;

  companion object {
    fun parse(raw: String?): ReleaseBump =
      entries.firstOrNull { it.wireValue == raw } ?: throw MissingReleaseBumpError(raw)
  }
}
