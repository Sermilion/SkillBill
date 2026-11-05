package skillbill.architecture

internal object TestTaskTempIsolationScan {
  private const val JVM_DEFAULTS =
    "runtime-kotlin/build-logic/convention/src/main/kotlin/dev/skillbill/runtime/buildlogic/Jvm.kt"
  private const val CONVENTION_BUILD = "runtime-kotlin/build-logic/convention/build.gradle.kts"
  private const val WRAPPER = "runtime-kotlin/gradlew"

  fun violations(read: (String) -> String?): List<String> {
    val violations = mutableListOf<String>()
    val jvm = read(JVM_DEFAULTS)?.let(::stripComments)
    if (jvm == null) {
      violations += "$JVM_DEFAULTS is missing."
    } else {
      val function = blockAfter(jvm, Regex("\\bfun\\s+(?:[\\w.]+\\.)?configureKotlinJvmTestDefaults\\b"))
      if (function == null || !Regex("\\bisolateJvmTempDir\\s*\\(").containsMatchIn(function)) {
        violations += "$JVM_DEFAULTS must call isolateJvmTempDir() inside configureKotlinJvmTestDefaults."
      }
      if (!jvm.contains("-Djava.io.tmpdir=")) violations += "$JVM_DEFAULTS must pass -Djava.io.tmpdir=."
    }
    val convention = read(CONVENTION_BUILD)?.let(::stripComments)
    if (convention == null) {
      violations += "$CONVENTION_BUILD is missing."
    } else if (!testBlocks(convention).any { block -> block.contains("java.io.tmpdir") }) {
      violations += "$CONVENTION_BUILD must set java.io.tmpdir inside a Test task block."
    }
    val wrapper = read(WRAPPER)
    if (wrapper == null) {
      violations += "$WRAPPER is missing."
    } else if (!wrapper.contains("skill_bill_jvm_temp_root=")) {
      violations += "$WRAPPER must set skill_bill_jvm_temp_root=."
    }
    return violations
  }

  private fun testBlocks(text: String): List<String> =
    Regex("\\bwithType\\s*<\\s*Test\\s*>[^{]*\\{").findAll(text).mapNotNull {
        match ->
      blockAt(text, match.range.last)
    }.toList()

  private fun blockAfter(
    text: String,
    marker: Regex,
  ): String? =
    marker.find(text)?.let {
        match ->
      text.indexOf('{', match.range.last).takeIf { it >= 0 }?.let { blockAt(text, it) }
    }

  private fun blockAt(
    text: String,
    openBrace: Int,
  ): String? {
    var depth = 0
    for (index in openBrace until text.length) {
      when (text[index]) {
        '{' -> depth += 1
        '}' -> {
          depth -= 1
          if (depth == 0) return text.substring(openBrace + 1, index)
        }
      }
    }
    return null
  }

  private fun stripComments(text: String): String = CommentStripper(text).strip()
}
