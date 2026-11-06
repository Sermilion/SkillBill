package skillbill.infrastructure.host.jvm

object AgentGradleMemoryLimits {
  const val GRADLE_OPTS: String = "GRADLE_OPTS"
  const val GRADLE_JVM_ARGS_PROPERTY: String = "org.gradle.jvmargs"
  const val GRADLE_WORKERS_MAX_PROPERTY: String = "org.gradle.workers.max"
  const val KOTLIN_DAEMON_JVM_ARGS_PROPERTY: String = "org.gradle.project.kotlin.daemon.jvmargs"

  private val LIMITS: Map<String, String> =
    linkedMapOf(
      GRADLE_JVM_ARGS_PROPERTY to "-Xmx4g -Xms512m -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8",
      GRADLE_WORKERS_MAX_PROPERTY to "4",
      KOTLIN_DAEMON_JVM_ARGS_PROPERTY to "-Xmx3g -Xms256m -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8",
    )

  fun environment(inherited: Map<String, String>): Map<String, String> {
    val operatorOptions = inherited[GRADLE_OPTS].orEmpty().trim()
    val added =
      LIMITS.filterKeys { property -> "-D$property=" !in operatorOptions }
        .map { (property, value) -> systemProperty(property, value) }
    if (added.isEmpty()) return emptyMap()
    return mapOf(GRADLE_OPTS to (listOf(operatorOptions).filter(String::isNotEmpty) + added).joinToString(" "))
  }

  private fun systemProperty(
    property: String,
    value: String,
  ): String = if (value.any(Char::isWhitespace)) "-D$property=\"$value\"" else "-D$property=$value"
}
