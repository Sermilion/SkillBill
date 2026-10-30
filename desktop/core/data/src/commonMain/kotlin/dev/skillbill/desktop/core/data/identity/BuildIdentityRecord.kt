package dev.skillbill.desktop.core.data.identity

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
  val readSchema: Int? = null,
  val incidentDetailVersion: Int? = null,
  val minimumIncidentReadSchema: Int? = null,
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
    private const val READ_SCHEMA = "read_schema"
    private const val INCIDENT_DETAIL_VERSION = "incident_detail_version"
    private const val MIN_INCIDENT_DETAIL_READ_SCHEMA = "min_incident_detail_read_schema"
    private const val DISPLAYED_COMMIT_LENGTH = 12

    /** The record in [text], or null when it names no commit or its dirty flag is not `true` or `false`. */
    fun parse(text: String): BuildIdentityRecord? {
      val values = text.lineSequence().mapNotNull { line ->
        val separator = line.indexOf('=')
        if (separator <= 0) null else line.substring(0, separator).trim() to line.substring(separator + 1).trim()
      }.toMap()
      val commit = values[COMMIT]?.takeIf(String::isNotEmpty) ?: return null
      val dirty = values[DIRTY]?.toBooleanStrictOrNull() ?: return null
      val readSchemaText = values[READ_SCHEMA]
      val incidentVersionText = values[INCIDENT_DETAIL_VERSION]
      val minimumIncidentReadSchemaText = values[MIN_INCIDENT_DETAIL_READ_SCHEMA]
      val declarationCount = listOf(readSchemaText, incidentVersionText, minimumIncidentReadSchemaText)
        .count { it != null }
      if (declarationCount != 0 && declarationCount != 3) return null
      val readSchema = readSchemaText?.toIntOrNull()?.takeIf { it > 0 }
      val incidentVersion = incidentVersionText?.toIntOrNull()?.takeIf { it > 0 }
      val minimumIncidentReadSchema = minimumIncidentReadSchemaText?.toIntOrNull()?.takeIf { it > 0 }
      if (declarationCount == 3 &&
        (readSchema == null || incidentVersion == null || minimumIncidentReadSchema == null)
      ) return null
      return BuildIdentityRecord(
        commit, dirty, values[RELEASE_TAG].orEmpty(), values[INSTALL_RUN_ID].orEmpty(), readSchema, incidentVersion,
        minimumIncidentReadSchema,
      )
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

enum class BuildCompatibility { SUPPORTED, INCIDENT_DETAIL_UNAVAILABLE, MISMATCH }

fun assessBuildCompatibility(
  installed: BuildIdentityRecord,
  desktop: BuildIdentityRecord,
): BuildCompatibility {
  val capabilityVersion = desktop.incidentDetailVersion
  val minimumReadSchema = desktop.minimumIncidentReadSchema
  val installedSupports = capabilityVersion != null && minimumReadSchema != null &&
    installed.incidentDetailVersion == capabilityVersion && (installed.readSchema ?: 0) >= minimumReadSchema
  val desktopSupports = capabilityVersion != null && minimumReadSchema != null &&
    (desktop.readSchema ?: 0) >= minimumReadSchema
  if (!installedSupports || !desktopSupports) return BuildCompatibility.INCIDENT_DETAIL_UNAVAILABLE
  return if (installed.sameBuildAs(desktop)) BuildCompatibility.SUPPORTED else BuildCompatibility.MISMATCH
}
