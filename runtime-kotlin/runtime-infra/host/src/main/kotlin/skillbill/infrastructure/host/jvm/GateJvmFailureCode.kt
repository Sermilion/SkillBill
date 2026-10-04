package skillbill.infrastructure.host.jvm

import skillbill.error.core.RuntimeFailureCode
import skillbill.error.core.SkillBillRuntimeException

enum class GateJvmFailureCode : RuntimeFailureCode {
  GUARD_RESOURCE_MISSING,
  GUARD_EXECUTION,
  GUARD_OUTPUT,
  GUARD_TIMEOUT,
  UNRESOLVED,
  STARTUP_FAILURE,
}

internal fun gateJvmGuardResourceMissing(resource: String): SkillBillRuntimeException =
  SkillBillRuntimeException(
    GateJvmFailureCode.GUARD_RESOURCE_MISSING,
    "Gate JVM guard is missing from the runtime distribution at classpath resource '$resource'. " +
      "Reinstall the runtime so the Java guard ships inside the image.",
  )

internal fun gateJvmGuardExecution(
  detail: String,
  cause: Throwable? = null,
): SkillBillRuntimeException =
  SkillBillRuntimeException(GateJvmFailureCode.GUARD_EXECUTION, "Gate JVM guard could not be evaluated: $detail", cause)

internal fun gateJvmGuardOutput(detail: String): SkillBillRuntimeException =
  SkillBillRuntimeException(
    GateJvmFailureCode.GUARD_OUTPUT,
    "Gate JVM guard returned no usable resolution output: $detail",
  )

internal fun gateJvmGuardTimeout(timeoutSeconds: Long): SkillBillRuntimeException =
  SkillBillRuntimeException(
    GateJvmFailureCode.GUARD_TIMEOUT,
    "Gate JVM guard evaluation timed out after ${timeoutSeconds}s.",
  )

fun gateJvmUnresolved(
  rejectedCandidate: String,
  requiredMajor: String,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    GateJvmFailureCode.UNRESOLVED,
    "No Java $requiredMajor+ runtime resolved for the pack gate command; rejected candidate: $rejectedCandidate. " +
      "Set SKILL_BILL_JAVA_HOME to a Java $requiredMajor+ installation and retry.",
  )

fun gateJvmStartupFailure(
  resolvedJvm: String,
  excerpt: String?,
): SkillBillRuntimeException =
  SkillBillRuntimeException(
    GateJvmFailureCode.STARTUP_FAILURE,
    "The pack gate command could not start a JVM on the resolved Java home '$resolvedJvm'; " +
      "this is an environment defect, not a repairable gate finding. " +
      "Set SKILL_BILL_JAVA_HOME to a working JDK and retry." +
      (excerpt?.let { "\nGate stdout (head+tail):\n$it" } ?: ""),
  )
