package dev.skillbill.runtime.buildlogic

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.jvm.application.tasks.CreateStartScripts
import org.gradle.kotlin.dsl.withType

private const val GUARD_ANCHOR = "# Determine the Java command to use to start the JVM."

private const val GUARD_MARKER = "skill_bill_required_java_major="

private const val EMBEDDED_JRE_MARKER = "JAVA_HOME=\"\$APP_HOME\""

private const val RUN_TEMP_ROOT_MARKER = "skill_bill_run_temp_root="

private const val RUN_TEMP_ROOT_SNIPPET = """skill_bill_run_temp_root=
case "${'$'}{SKILL_BILL_RUN_SCRATCH_DIR:-}" in
  ''|*[\"\$\`\\]*) ;;
  *)
    if [ -d "${'$'}SKILL_BILL_RUN_SCRATCH_DIR" ]; then
      skill_bill_run_temp_root=${'$'}SKILL_BILL_RUN_SCRATCH_DIR
    fi
    ;;
esac
if [ -z "${'$'}skill_bill_run_temp_root" ] && [ "$(uname -s)" = Linux ]; then
  case "${'$'}{JAVA_TOOL_OPTIONS:-} ${'$'}{JAVA_OPTS:-}" in
    *java.io.tmpdir=*) ;;
    *)
      skill_bill_state_temp_root="${'$'}{SKILL_BILL_STATE_DIR:-${'$'}HOME/.skill-bill}/scratch/jvm"
      case "${'$'}skill_bill_state_temp_root" in
        *[\"\$\`\\]*) ;;
        *)
          if mkdir -p "${'$'}skill_bill_state_temp_root" 2>/dev/null; then
            skill_bill_run_temp_root=${'$'}skill_bill_state_temp_root
          fi
          ;;
      esac
      ;;
  esac
fi
if [ -n "${'$'}skill_bill_run_temp_root" ]; then
  JAVA_OPTS="${'$'}{JAVA_OPTS:-} \"-Djava.io.tmpdir=${'$'}skill_bill_run_temp_root\""
fi
"""

internal fun startScriptWithRunTempRoot(
  generated: String,
  scriptName: String,
): String {
  if (generated.contains(RUN_TEMP_ROOT_MARKER)) return generated
  val firstLineEnd = generated.indexOf('\n')
  if (!generated.startsWith("#!") || firstLineEnd < 0) {
    throw GradleException(
      "Start script $scriptName does not begin with a shebang line, so the run temp root snippet has no " +
        "insertion point. Gradle changed the generated launcher; update startScriptWithRunTempRoot.",
    )
  }
  val afterFirstLine = firstLineEnd + 1
  return generated.substring(0, afterFirstLine) + RUN_TEMP_ROOT_SNIPPET + generated.substring(afterFirstLine)
}

internal fun startScriptWithJavaGuard(
  generated: String,
  guard: String,
  scriptName: String,
): String {
  if (generated.contains(GUARD_MARKER)) return generated
  if (generated.contains(GUARD_ANCHOR)) return generated.replace(GUARD_ANCHOR, guard + GUARD_ANCHOR)
  if (generated.contains(EMBEDDED_JRE_MARKER)) return generated
  throw GradleException(
    "Start-script Java guard anchor is absent from $scriptName. Gradle changed the " +
      "generated launcher; update GUARD_ANCHOR in StartScriptJavaGuard.kt.",
  )
}

internal fun Project.configureStartScriptJavaGuard(guard: RegularFileProperty) {
  tasks.withType<CreateStartScripts>().configureEach {
    inputs.file(guard).withPropertyName("skillBillJavaGuard")
    doLast {
      val script = unixScript
      val generated = script.readText()
      val guarded = startScriptWithJavaGuard(generated, guard.get().asFile.readText(), script.name)
      val patched = startScriptWithRunTempRoot(guarded, script.name)
      if (patched != generated) script.writeText(patched)
    }
  }
}
