package skillbill.infrastructure.skills.scaffold.authoring

import java.util.logging.Logger

private val legacyDispatcherLog: Logger = Logger.getLogger("skillbill.scaffold.LegacyDispatcherCompatibility")

internal fun adaptLegacyDispatcherExecutionBody(body: String): String {
  val adapted =
    body
      .replace(Regex("(?<![\\w-])/skill-bill(?![\\w-])"), "/sona")
      .replace(Regex("(?<![\\w./-])(?:\\./)?skill-bill(?=\\s|$|[<\\[])"), "sona")
  if (adapted != body) {
    legacyDispatcherLog.info("Adapted legacy skill-bill dispatcher commands to the public sona command.")
  }
  return adapted
}
