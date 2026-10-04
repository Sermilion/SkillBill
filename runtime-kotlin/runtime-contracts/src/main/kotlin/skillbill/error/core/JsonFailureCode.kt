package skillbill.error.core

enum class JsonFailureCode : RuntimeFailureCode {
  MALFORMED_TEXT,
  WRONG_ROOT_TYPE,
  UNSUPPORTED_VALUE,
  ;

  companion object {
    fun malformedJsonText(cause: Throwable): SkillBillRuntimeException =
      SkillBillRuntimeException(
        MALFORMED_TEXT,
        "JSON text is malformed: ${cause.message.orEmpty()}",
        cause,
      )

    fun jsonWrongRootType(expectedRoot: String): SkillBillRuntimeException =
      SkillBillRuntimeException(WRONG_ROOT_TYPE, "JSON root must be $expectedRoot")

    fun unsupportedJsonValue(message: String): SkillBillRuntimeException =
      SkillBillRuntimeException(UNSUPPORTED_VALUE, message)
  }
}
