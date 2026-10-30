package dev.skillbill.desktop.core.data.runtime

private val KEBAB_TITLE = Regex("[a-z0-9]+(?:-[a-z0-9]+)+")

/**
 * A runtime title as people read it: a kebab-case feature name such as `linear-progress-sync` becomes "Linear progress sync",
 * as desktop-tray shows it. Issue keys and phase titles such as "plan SKILL-452" pass through unchanged.
 */
internal fun displayTitle(value: String): String =
  if (KEBAB_TITLE.matches(value)) value.replace('-', ' ').replaceFirstChar(Char::uppercase) else value
