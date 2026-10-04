package skillbill.infrastructure.sqlite.core.schema

import skillbill.model.EnvironmentContext
import java.nio.file.Path
import java.nio.file.Paths

internal object DatabasePaths {
  const val DB_ENVIRONMENT_KEY: String = "SKILL_BILL_REVIEW_DB"

  fun defaultDbPath(userHome: Path): Path = userHome.resolve(".skill-bill").resolve("review-metrics.db")

  fun resolveDbPath(
    cliValue: String?,
    environment: Map<String, String>,
    userHome: Path,
  ): Path {
    val candidate = cliValue ?: environment[DB_ENVIRONMENT_KEY]
    return if (candidate != null) {
      expandUserPath(candidate = candidate, userHome = userHome)
    } else {
      defaultDbPath(userHome).toAbsolutePath().normalize()
    }
  }

  private fun expandUserPath(
    candidate: String,
    userHome: Path,
  ): Path {
    val expandedCandidate =
      when {
        candidate == "~" -> userHome.toString()
        candidate.startsWith("~/") || candidate.startsWith("~\\") ->
          userHome.resolve(candidate.drop(2)).toString()
        else -> candidate
      }
    return Paths.get(expandedCandidate).toAbsolutePath().normalize()
  }
}

internal fun requireResolvedEnvironmentContext(context: EnvironmentContext): EnvironmentContext {
  if (context.userHome == EnvironmentContext.UnspecifiedUserHome) {
    error("EnvironmentContext.userHome is unresolved; resolve it in the composition root before opening SQLite.")
  }
  if (context.environment === EnvironmentContext.UnspecifiedEnvironment) {
    error("EnvironmentContext.environment is unresolved; resolve it in the composition root before opening SQLite.")
  }
  return context.copy(
    userHome = context.userHome.toAbsolutePath().normalize(),
  )
}
