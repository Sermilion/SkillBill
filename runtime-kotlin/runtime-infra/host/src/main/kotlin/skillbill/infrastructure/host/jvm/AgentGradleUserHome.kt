package skillbill.infrastructure.host.jvm

import java.nio.file.Path

object AgentGradleUserHome {
  const val GRADLE_OPTS: String = "GRADLE_OPTS"
  const val GRADLE_USER_HOME: String = "GRADLE_USER_HOME"
  const val HOME: String = "HOME"
  const val USER_HOME_PROPERTY: String = "-Dgradle.user.home="
  private const val DEFAULT_DIRECTORY: String = ".gradle"

  fun environment(
    launch: Map<String, String>,
    machine: Map<String, String>,
  ): Map<String, String> {
    val options = launch[GRADLE_OPTS].orEmpty().trim()
    if (USER_HOME_PROPERTY in options) return emptyMap()
    val home = machineGradleUserHome(machine) ?: return emptyMap()
    if (home.any { it == '"' || it == '\'' || it == '\\' }) return emptyMap()
    val pin = if (home.any(Char::isWhitespace)) "$USER_HOME_PROPERTY\"$home\"" else "$USER_HOME_PROPERTY$home"
    return mapOf(GRADLE_OPTS to listOf(options, pin).filter(String::isNotEmpty).joinToString(" "))
  }

  private fun machineGradleUserHome(machine: Map<String, String>): String? =
    machine[GRADLE_USER_HOME]?.takeIf(String::isNotBlank)
      ?: machine[HOME]?.takeIf(String::isNotBlank)?.let { Path.of(it, DEFAULT_DIRECTORY).toString() }
}
