package skillbill.infrastructure.skills.scaffold.authoring

import java.util.logging.Logger

private val legacyDispatcherLog: Logger = Logger.getLogger("skillbill.scaffold.LegacyDispatcherCompatibility")

internal fun adaptLegacyDispatcherText(text: String): String {
  val adapted =
    text
      .replace(Regex("(?<![\\w-])mcp__skill-bill__(?=\\w)"), "mcp__sona__")
      .replace("`skill-bill`", "`sona`")
      .replace(Regex("(?<![\\w-])/skill-bill(?![\\w-])"), "/sona")
      .replace(Regex("(?<![\\w./-])(?:\\./)?skill-bill(?=\\s|$|[<\\[])"), "sona")
  if (adapted != text) {
    legacyDispatcherLog.info("Adapted legacy skill-bill dispatcher commands to the public sona command.")
  }
  return adapted
}
