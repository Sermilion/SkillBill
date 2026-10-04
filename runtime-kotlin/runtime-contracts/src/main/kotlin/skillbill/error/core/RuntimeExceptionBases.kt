package skillbill.error.core

/** Marker for owner-declared failure code enums. */
interface RuntimeFailureCode

class SkillBillRuntimeException(
  val code: RuntimeFailureCode,
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)

fun SkillBillRuntimeException.rethrowUnless(handled: Boolean): SkillBillRuntimeException {
  if (!handled) throw this
  return this
}

fun Throwable.failureCodeLabel(): String? {
  val failureCode = (this as? SkillBillRuntimeException)?.code
  return when {
    failureCode == null -> null
    failureCode is Enum<*> -> "${failureCode.declaringJavaClass.simpleName}.${failureCode.name}"
    else -> "${failureCode::class.simpleName}.$failureCode"
  }
}
