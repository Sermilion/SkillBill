package skillbill.infrastructure.sqlite.core.schema

import skillbill.contracts.persistence.RuntimeDatabaseEnvironmentKeys
import skillbill.error.core.UnresolvedEnvironmentContextFieldError
import skillbill.model.EnvironmentContext
import java.nio.file.Path
import java.nio.file.Paths

internal object DatabasePaths {
  fun defaultDbPath(userHome: Path): Path = userHome.resolve(".skill-bill").resolve("review-metrics.db")

  fun resolveDbPath(
    cliValue: String?,
    environment: Map<String, String>,
    userHome: Path,
  ): Path {
    val candidate = cliValue ?: environment[RuntimeDatabaseEnvironmentKeys.DATABASE_PATH]
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
    throw UnresolvedEnvironmentContextFieldError("userHome")
  }
  if (context.environment === EnvironmentContext.UnspecifiedEnvironment) {
    throw UnresolvedEnvironmentContextFieldError("environment")
  }
  return context.copy(
    userHome = context.userHome.toAbsolutePath().normalize(),
  )
}
