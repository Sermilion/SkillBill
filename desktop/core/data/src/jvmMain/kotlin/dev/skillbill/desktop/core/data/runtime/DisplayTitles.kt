package dev.skillbill.desktop.core.data.runtime

private val KEBAB_TITLE = Regex("[a-z0-9]+(?:-[a-z0-9]+)+")

/**
 * A runtime title as people read it: a kebab-case feature name such as `linear-progress-sync` becomes "Linear progress sync",
 * led by its issue key as "[SKILL-452] Linear progress sync" when it has one. Issue keys and phase titles such as
 * "plan SKILL-452" pass through unchanged.
 */
internal fun displayTitle(value: String, issueKey: String? = null): String {
  if (!KEBAB_TITLE.matches(value)) return value
  val name = value.replace('-', ' ').replaceFirstChar(Char::uppercase)
  return issueKey?.takeIf(String::isNotBlank)?.let { "[$it] $name" } ?: name
}
