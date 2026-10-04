package skillbill.error.core

import kotlin.enums.EnumEntries

interface FailureWireCode {
  val wireValue: String
}

enum class FailureWireDecodeCode : RuntimeFailureCode {
  UNRECOGNIZED;

  companion object {
    fun unrecognizedFailureWireCode(
      hierarchy: String,
      rejectedToken: String,
    ): SkillBillRuntimeException =
      SkillBillRuntimeException(
        UNRECOGNIZED,
        "Unrecognized failure wire code '$rejectedToken' for hierarchy '$hierarchy'.",
      )
  }
}

fun <E> EnumEntries<E>.failureWireByValue(
  value: String,
  hierarchy: String,
): E where E : Enum<E>, E : FailureWireCode =
  firstOrNull { it.wireValue == value }
    ?: throw FailureWireDecodeCode.unrecognizedFailureWireCode(hierarchy, value)
