package dev.skillbill.tray.core.data.identity

/**
 * One build's `build-identity.properties` record, as the shared `skillbill.build-identity` Gradle plugin writes it for the
 * runtime and for the desktop. It needs nothing but the standard library, so code that runs before the runtime classpath
 * exists can parse and compare records too.
 */
data class BuildIdentityRecord(
  val commit: String,
  val dirty: Boolean,
  val releaseTag: String,
  val installRunId: String,
) {
  /**
   * A dirty record names no exact source, so it matches only another dirty record of the same commit from the same install
   * run. Clean records match when they name the same commit and release tag.
   */
  fun sameBuildAs(other: BuildIdentityRecord): Boolean = if (dirty || other.dirty) {
    dirty && other.dirty && commit == other.commit && installRunId.isNotEmpty() && installRunId == other.installRunId
  } else {
    commit == other.commit && releaseTag == other.releaseTag
  }

  fun display(): String {
    val source = if (releaseTag.isEmpty()) "commit ${commit.take(DISPLAYED_COMMIT_LENGTH)}" else {
      "release $releaseTag, commit ${commit.take(DISPLAYED_COMMIT_LENGTH)}"
    }
    return when {
      !dirty -> source
      installRunId.isEmpty() -> "$source (dirty)"
      else -> "$source (dirty, run $installRunId)"
    }
  }

  companion object {
    const val FILE_NAME = "build-identity.properties"

    private const val COMMIT = "commit"
    private const val DIRTY = "dirty"
    private const val RELEASE_TAG = "release_tag"
    private const val INSTALL_RUN_ID = "install_run_id"
    private const val DISPLAYED_COMMIT_LENGTH = 12

    /** The record in [text], or null when it names no commit or its dirty flag is not `true` or `false`. */
    fun parse(text: String): BuildIdentityRecord? {
      val values = text.lineSequence().mapNotNull { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) null else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
      }.toMap()
      val commit = values[COMMIT]?.takeIf(String::isNotEmpty) ?: return null
      val dirty = values[DIRTY]?.toBooleanStrictOrNull() ?: return null
      return BuildIdentityRecord(commit, dirty, values[RELEASE_TAG].orEmpty(), values[INSTALL_RUN_ID].orEmpty())
    }

    /**
     * The install that replaces both builds with a matched pair: the installed release when both are clean releases, else
     * source. Records hold the release version without the `v` that published tags carry, so the command adds it back.
     */
    fun reinstallCommand(installed: BuildIdentityRecord?, desktop: BuildIdentityRecord?): String {
      val tag = installed?.takeUnless { it.dirty }?.releaseTag.orEmpty()
      val bothReleases = tag.isNotEmpty() && desktop != null && !desktop.dirty && desktop.releaseTag.isNotEmpty()
      return if (bothReleases) "./install.sh --release v${tag.removePrefix("v")}" else "./install.sh --from-source"
    }
  }
}
