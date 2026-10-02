package skillbill.error.core

/** Marker for owner-declared failure code enums. */
interface RuntimeFailureCode

enum class LegacyFailureCode : RuntimeFailureCode {
  UNCLASSIFIED,
}

open class SkillBillRuntimeException(
  val code: RuntimeFailureCode,
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause) {
  constructor(message: String, cause: Throwable? = null) : this(LegacyFailureCode.UNCLASSIFIED, message, cause)
}

open class ShellContentContractException(
  message: String,
  cause: Throwable? = null,
) : SkillBillRuntimeException(message, cause)

/** Throws this failure unless [handled]; lets catch sites rethrow through one call. */
fun SkillBillRuntimeException.rethrowUnless(handled: Boolean): SkillBillRuntimeException {
  if (!handled) throw this
  return this
}

/** `"<CodeEnum>.<ENTRY>"` for a coded runtime failure, or null for any other throwable. */
fun Throwable.failureCodeLabel(): String? {
  val failureCode = (this as? SkillBillRuntimeException)?.code
  return when {
    failureCode == null || failureCode is LegacyFailureCode -> null
    failureCode is Enum<*> -> "${failureCode.declaringJavaClass.simpleName}.${failureCode.name}"
    else -> "${failureCode::class.simpleName}.$failureCode"
  }
}
