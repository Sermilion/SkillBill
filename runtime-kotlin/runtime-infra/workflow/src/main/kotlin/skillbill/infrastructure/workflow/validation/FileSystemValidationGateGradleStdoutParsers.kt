package skillbill.infrastructure.workflow.validation

import skillbill.ports.validation.model.ValidationGateFinding
import skillbill.workflow.taskruntime.model.validation.FeatureTaskRuntimeValidationFailureSubject
import java.nio.file.Path

internal object FileSystemValidationGateGradleStdoutParsers {
  private const val UNPARSEABLE_GATE_RULE_ID = "unparseable_gate_failure"
  private const val COMPILER_LOCATION_PATH_GROUP = 1
  private const val COMPILER_LOCATION_LINE_GROUP = 2
  private const val COMPILER_LOCATION_COLUMN_GROUP = 3
  private const val COMPILER_LOCATION_MESSAGE_GROUP = 4
  private const val QUALITY_TOOL_PATH_GROUP = 1
  private const val QUALITY_TOOL_LINE_GROUP = 2
  private const val QUALITY_TOOL_COLUMN_GROUP = 3
  private const val QUALITY_TOOL_MESSAGE_GROUP = 4
  private const val QUALITY_TOOL_RULE_GROUP = 5
  private const val PROJECT_HEALTH_REQUIRED_CONFIG_GROUP = 1
  private const val PROJECT_HEALTH_COORDINATE_GROUP = 2
  private const val PROJECT_HEALTH_ACTUAL_CONFIG_GROUP = 3
  private const val GRADLE_ROOT_MODULE = "<root>"
  private val GRADLE_SPECIFIC_RULE_IDS =
    setOf(
      "incorrectConfiguration",
      "projectHealth",
      "forbidden_project_dependency",
      "architectureCheck",
    )
  private val COMPILER_E_LINE = Regex("""^e:\s+(.*)$""")
  private val COMPILER_LOCATION = Regex("""^(?:file://)?(.+):(\d+):(\d+)\s+(.*)$""")
  private val QUALITY_TOOL_LINE =
    Regex("""^(?:file://)?(.+\.kt):(\d+):(\d+):\s+(.+?)\s+\[([A-Za-z0-9]+)]\s*$""")
  private val GRADLE_TASK_PREFIX = Regex("""^>\s*Task\s+:\S+\s+""")
  private val GRADLE_TASK_LINE = Regex("""^>\s*Task\s+:(\S+)(?:\s+\S+)?\s*$""")
  private val GRADLE_TASK_FAILED_LINE = Regex("""^>\s*Task\s+:(\S+)\s+FAILED\s*$""")
  private val GRADLE_TASK_FAILURE_HEADER =
    Regex("""Execution failed for task '?([^']+)'?\.""")
  private val GRADLE_PROJECT_HEALTH_ADVICE =
    Regex("""^\s*(\w+)\((.+)\)\s+\(was\s+(\w+)\)\s*$""")
  private val GRADLE_PROJECT_HEALTH_ADVICE_HEADER =
    Regex("""Advice for project :(\S+)""", RegexOption.IGNORE_CASE)
  private val GRADLE_FORBIDDEN_PROJECT_DEPENDENCY =
    Regex("""(?i)forbidden project dependency""")
  private val GRADLE_BANNED_PROJECT_DEPENDENCIES =
    Regex("""^(\S+)\s+has banned project dependencies:\s*(.+)$""")

  fun parseGradleQualityToolStdout(
    repoRoot: Path,
    stdout: String,
  ): List<ValidationGateFinding> {
    val repo = repoRoot.toAbsolutePath().normalize()
    return stdout.lineSequence().mapNotNull { line ->
      val match = QUALITY_TOOL_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
      val rawPath = match.groupValues[QUALITY_TOOL_PATH_GROUP].removePrefix("file://")
      val lineNo = match.groupValues[QUALITY_TOOL_LINE_GROUP]
      val column = match.groupValues[QUALITY_TOOL_COLUMN_GROUP]
      val message = match.groupValues[QUALITY_TOOL_MESSAGE_GROUP].trim()
      val rule = match.groupValues[QUALITY_TOOL_RULE_GROUP].trim()
      val relative = FileSystemValidationGateGradlePaths.repoRelativeQualityPath(repo, rawPath)
      val module = relative.substringBefore('/').ifBlank { "<quality>" }
      ValidationGateFinding(
        module = module,
        ruleOrTestId = rule,
        message = message,
        location = "$relative:$lineNo:$column",
        failureSubject =
          if (FileSystemValidationGateGradlePaths.isProjectOwnedPath(repo, rawPath)) {
            FeatureTaskRuntimeValidationFailureSubject.PROJECT_SOURCE
          } else {
            FeatureTaskRuntimeValidationFailureSubject.UNKNOWN
          },
      )
    }.toList()
  }

  fun parseGradleExecutedTaskIdentities(stdout: String): List<String> =
    buildList {
      stdout.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        val taskPath =
          GRADLE_TASK_FAILED_LINE.matchEntire(line)?.groupValues?.get(1)
            ?: GRADLE_TASK_LINE.matchEntire(line)?.groupValues?.get(1)
            ?: return@forEach
        val (module, task) = FileSystemValidationGateGradlePaths.parseGradleTaskPath(taskPath)
        if (task.isBlank()) return@forEach
        add("${module.ifBlank { GRADLE_ROOT_MODULE }}|$task")
      }
    }.distinct()

  fun coveredGradleTaskKeys(findings: List<ValidationGateFinding>): Set<String> =
    buildSet {
      findings.forEach { finding ->
        when (finding.ruleOrTestId) {
          "kotlin_compiler" -> add("${finding.module}|compileKotlin")
          "incorrectConfiguration", "projectHealth" -> add("${finding.module}|projectHealth")
          "forbidden_project_dependency", "architectureCheck" -> add("${finding.module}|architectureCheck")
        }
        val location = finding.location.orEmpty()
        if (location.contains("/reports/detekt/")) {
          add("${finding.module}|detekt")
        }
        if (looksLikeJUnitFindingWithoutLocation(finding)) {
          add("${finding.module}|test")
        }
        if (
          finding.location?.contains(".kt:") == true &&
          finding.ruleOrTestId.firstOrNull()?.isUpperCase() == true
        ) {
          add("${finding.module}|spotlessCheck")
          add("${finding.module}|spotlessKotlinCheck")
        }
        if (finding.ruleOrTestId == "spotless") {
          add("${finding.module}|spotlessCheck")
          add("${finding.module}|spotlessKotlinCheck")
        }
      }
    }

  fun parseGradleProjectHealthStdout(stdout: String): List<ValidationGateFinding> {
    var currentModule: String? = null
    return buildList {
      stdout.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        GRADLE_TASK_FAILED_LINE.matchEntire(line)?.let { match ->
          val (module, task) = FileSystemValidationGateGradlePaths.parseGradleTaskPath(match.groupValues[1])
          if (task == "projectHealth") {
            currentModule = module
          }
          return@forEach
        }
        GRADLE_TASK_FAILURE_HEADER.matchEntire(line)?.let { match ->
          val (module, task) = FileSystemValidationGateGradlePaths.parseGradleTaskPath(match.groupValues[1])
          if (task == "projectHealth") {
            currentModule = module
          }
          return@forEach
        }
        GRADLE_PROJECT_HEALTH_ADVICE_HEADER.matchEntire(line)?.let { match ->
          currentModule = match.groupValues[1]
          return@forEach
        }
        val advice = GRADLE_PROJECT_HEALTH_ADVICE.matchEntire(line) ?: return@forEach
        val module = currentModule ?: return@forEach
        val requiredConfiguration = advice.groupValues[PROJECT_HEALTH_REQUIRED_CONFIG_GROUP]
        val dependencyCoordinate = advice.groupValues[PROJECT_HEALTH_COORDINATE_GROUP].trim()
        val actualConfiguration = advice.groupValues[PROJECT_HEALTH_ACTUAL_CONFIG_GROUP]
        add(
          ValidationGateFinding(
            module = module,
            ruleOrTestId = "incorrectConfiguration",
            message = "$requiredConfiguration($dependencyCoordinate) (was $actualConfiguration)",
            location =
              FileSystemValidationGateGradlePaths.filePathFromAdviceBlock(line)?.let { path ->
                FileSystemValidationGateGradlePaths.repoRelativeAdvicePath(path)
              },
            failureSubject = FeatureTaskRuntimeValidationFailureSubject.PROJECT_SOURCE,
          ),
        )
      }
    }
  }

  fun parseGradleArchitectureCheckStdout(stdout: String): List<ValidationGateFinding> {
    var currentModule: String? = null
    return buildList {
      stdout.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        GRADLE_TASK_FAILED_LINE.matchEntire(line)?.let { match ->
          val (module, task) = FileSystemValidationGateGradlePaths.parseGradleTaskPath(match.groupValues[1])
          if (task == "architectureCheck") {
            currentModule = module
          }
          return@forEach
        }
        GRADLE_TASK_FAILURE_HEADER.matchEntire(line)?.let { match ->
          val (module, task) = FileSystemValidationGateGradlePaths.parseGradleTaskPath(match.groupValues[1])
          if (task == "architectureCheck") {
            currentModule = module
          }
          return@forEach
        }
        if (!GRADLE_FORBIDDEN_PROJECT_DEPENDENCY.containsMatchIn(line) &&
          GRADLE_BANNED_PROJECT_DEPENDENCIES.matchEntire(line) == null
        ) {
          return@forEach
        }
        val module =
          currentModule
            ?: GRADLE_BANNED_PROJECT_DEPENDENCIES.matchEntire(line)?.groupValues?.get(1)
            ?: line.substringAfter("from :", "").substringBefore(' ').removePrefix(":")
              .ifBlank { null }
            ?: return@forEach
        add(
          ValidationGateFinding(
            module = module,
            ruleOrTestId = "forbidden_project_dependency",
            message = line,
            location =
              FileSystemValidationGateGradlePaths.filePathFromAdviceBlock(line)?.let { path ->
                FileSystemValidationGateGradlePaths.repoRelativeAdvicePath(path)
              },
            failureSubject = FeatureTaskRuntimeValidationFailureSubject.PROJECT_SOURCE,
          ),
        )
      }
    }
  }

  fun parseGradleTaskFailureHeaders(
    stdout: String,
    coveredTaskKeys: Set<String>,
  ): List<ValidationGateFinding> =
    buildList {
      stdout.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        val match = GRADLE_TASK_FAILURE_HEADER.matchEntire(line) ?: return@forEach
        val (module, task) = FileSystemValidationGateGradlePaths.parseGradleTaskPath(match.groupValues[1])
        val taskKey = "${module.ifBlank { GRADLE_ROOT_MODULE }}|$task"
        if (taskKey in coveredTaskKeys) return@forEach
        add(
          ValidationGateFinding(
            module = module.ifBlank { GRADLE_ROOT_MODULE },
            ruleOrTestId = task,
            message = line,
            location = null,
            failureSubject = FeatureTaskRuntimeValidationFailureSubject.UNKNOWN,
          ),
        )
      }
    }

  fun parseGradleKotlinCompilerStdout(
    repoRoot: Path,
    stdout: String,
  ): List<ValidationGateFinding> {
    val repo = repoRoot.toAbsolutePath().normalize()
    val canonicalRepo = FileSystemValidationGateGradlePaths.canonicalizeExisting(repo)
    val repoPrefixes =
      listOf(
        canonicalRepo.toUri().toString().removeSuffix("/"),
        repo.toUri().toString().removeSuffix("/"),
        "file://$canonicalRepo",
        "file://$repo",
        canonicalRepo.toString(),
        repo.toString(),
        "file://",
      ).distinct()
    return stdout.lineSequence().mapNotNull { line ->
      val eMatch = COMPILER_E_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
      val rest = eMatch.groupValues[1].trim()
      val locationMatch = COMPILER_LOCATION.matchEntire(rest) ?: return@mapNotNull null
      val rawPath = locationMatch.groupValues[COMPILER_LOCATION_PATH_GROUP].removePrefix("file://")
      val lineNo = locationMatch.groupValues[COMPILER_LOCATION_LINE_GROUP]
      val column = locationMatch.groupValues[COMPILER_LOCATION_COLUMN_GROUP]
      var message =
        locationMatch.groupValues[COMPILER_LOCATION_MESSAGE_GROUP].trim()
          .replace(GRADLE_TASK_PREFIX, "")
      for (prefix in repoPrefixes) {
        message = message.replace(prefix, "")
      }
      message = message.trim()
      val relative =
        FileSystemValidationGateGradlePaths.repoRelativeCompilerPath(
          repo,
          canonicalRepo,
          Path.of(rawPath),
          rawPath,
        )
      val module = relative.substringBefore('/').ifBlank { "<compiler>" }
      ValidationGateFinding(
        module = module,
        ruleOrTestId = "kotlin_compiler",
        message = message,
        location = "$relative:$lineNo:$column",
        failureSubject =
          if (FileSystemValidationGateGradlePaths.isProjectOwnedPath(repo, rawPath)) {
            FeatureTaskRuntimeValidationFailureSubject.PROJECT_SOURCE
          } else {
            FeatureTaskRuntimeValidationFailureSubject.UNKNOWN
          },
      )
    }.toList()
  }

  private fun looksLikeJUnitFindingWithoutLocation(finding: ValidationGateFinding): Boolean =
    finding.ruleOrTestId != "kotlin_compiler" &&
      finding.ruleOrTestId != UNPARSEABLE_GATE_RULE_ID &&
      finding.ruleOrTestId !in GRADLE_SPECIFIC_RULE_IDS &&
      finding.location == null &&
      finding.message.isNotBlank()
}
