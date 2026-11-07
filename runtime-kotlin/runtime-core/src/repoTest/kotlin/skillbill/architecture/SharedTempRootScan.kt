package skillbill.architecture

import java.nio.file.Files

internal object SharedTempRootScan {
  private val importPattern = Regex("""^\s*import\s+(\S+)\s+as\s+(\w+)\s*$""", RegexOption.MULTILINE)
  private val zeroRootArgument =
    Regex("""\(\s*(?:(?:prefix|suffix|directory)\s*=\s*)?(?:\)|"|null\b|[A-Z][A-Z0-9_]*\s*[,)])""")

  data class Source(val path: String, val text: String)

  fun readSources(): List<Source> =
    PrincipleEnforcementInventory.moduleArchitectureScanCases.flatMap { module ->
      val root = ArchitectureScanSupport.runtimeRoot.resolve(module.mainScanRoot)
      ArchitectureScanSupport.kotlinFilesUnder(root).map { path ->
        Source(
          ArchitectureScanSupport.runtimeRoot.relativize(path).toString().replace('\\', '/'),
          Files.readString(path),
        )
      }
    }

  fun violations(
    sources: List<Source>,
    exemptions: Set<String> = PrincipleEnforcementInventory.sharedTempRootExemptions,
  ): List<String> {
    if (sources.isEmpty()) return listOf("SHARED_TEMP_ROOT found no runtime production sources to scan.")
    return sources.filterNot { it.path in exemptions }.flatMap { source ->
      val code = CommentStripper(source.text).strip()
      val codePositions =
        CommentStripper(
          source.text,
          blankStringLiterals = true,
          preserveTemplateExpressions = true,
        ).strip()
      val imports = importPattern.findAll(code).associate { it.groupValues[1] to it.groupValues[2] }
      val violations = mutableListOf<String>()
      val systemNames = names("java.lang.System", imports)
      if (Regex(
          receiverPattern(systemNames, "getProperty") + "\\s*\\(\\s*\"java\\.io\\.tmpdir\"",
        ).containsCodeMatch(code, codePositions)
      ) {
        violations += "System.getProperty(\"java.io.tmpdir\") reads the shared temp root."
      }
      val pathCalls =
        listOf(
          "java.nio.file.Path" to "of",
          "java.nio.file.Paths" to "get",
          "java.io.File" to "",
        )
      pathCalls.forEach { (target, member) ->
        val pattern = receiverPattern(names(target, imports), member) + "\\s*\\(\\s*\"/tmp(?:/[^\"]*)?\""
        if (Regex(
            pattern,
          ).containsCodeMatch(code, codePositions)
        ) {
          violations += "A $target call uses a /tmp path literal."
        }
      }
      val tempCalls =
        listOf(
          "java.nio.file.Files" to setOf("createTempFile", "createTempDirectory"),
          "java.io.File" to setOf("createTempFile"),
        )
      tempCalls.forEach { (target, members) ->
        members.forEach { member ->
          val pattern = receiverPattern(names(target, imports), member) + "\\s*" + zeroRootArgument.pattern
          if (Regex(
              pattern,
            ).containsCodeMatch(code, codePositions)
          ) {
            violations += "$target.$member uses the default temp root."
          }
        }
      }
      val pathFunctions = setOf("createTempFile", "createTempDirectory")
      pathFunctions.forEach { target ->
        val functionNames = names("kotlin.io.path.$target", imports) + target
        functionNames.forEach { name ->
          val pattern = "(?<![\\w.])(?:kotlin\\.io\\.path\\.)?${Regex.escape(name)}\\s*${zeroRootArgument.pattern}"
          if (Regex(
              pattern,
            ).containsCodeMatch(code, codePositions)
          ) {
            violations += "kotlin.io.path.$target uses the default temp root."
          }
        }
      }
      violations.map { violation -> "${source.path}: $violation Pass the host temp-root authority as the root." }
    }
  }

  private fun Regex.containsCodeMatch(
    code: String,
    codePositions: String,
  ): Boolean = findAll(code).any { codePositions[it.range.first] != ' ' }

  private fun names(
    target: String,
    imports: Map<String, String>,
  ): Set<String> = setOf(target, target.substringAfterLast('.')) + listOfNotNull(imports[target])

  private fun receiverPattern(
    names: Set<String>,
    member: String,
  ): String =
    "(?<![\\w.])(?:${names.joinToString("|") { Regex.escape(it) }})\\s*" +
      (if (member.isEmpty()) "" else "\\.\\s*${Regex.escape(member)}")
}
