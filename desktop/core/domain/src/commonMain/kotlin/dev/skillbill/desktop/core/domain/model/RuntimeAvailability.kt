package dev.skillbill.desktop.core.domain.model

/** Whether the installed Skill Bill runtime can serve this app build. */
sealed interface RuntimeAvailability {
  data object Available : RuntimeAvailability

  data class Unavailable(val problem: RuntimeBuildProblem) : RuntimeAvailability
}

/** Why the installed runtime cannot serve this app. Every problem names the install that replaces both with a matched pair. */
sealed interface RuntimeBuildProblem {
  val reinstallCommand: String

  /** No runtime jars or no build identity record under the install root. */
  data class NotInstalled(override val reinstallCommand: String) : RuntimeBuildProblem

  /** A build identity record exists but cannot be read or parsed; [installedIdentity] is set when only the app's record failed. */
  data class Unreadable(override val reinstallCommand: String, val installedIdentity: String?) : RuntimeBuildProblem

  /** Both records parse but name different builds or read schemas. */
  data class Mismatched(
    override val reinstallCommand: String,
    val installedIdentity: String,
    val appIdentity: String,
  ) : RuntimeBuildProblem
}
