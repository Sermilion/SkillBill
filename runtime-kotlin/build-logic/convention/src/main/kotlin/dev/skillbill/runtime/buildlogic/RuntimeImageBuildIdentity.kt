package dev.skillbill.runtime.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

internal const val BUILD_IDENTITY_PLUGIN_ID = "skillbill.build-identity"

internal const val WRITE_BUILD_IDENTITY_TASK = "writeBuildIdentity"

internal const val BUILD_IDENTITY_RECORD = "build-identity.properties"

@DisableCachingByDefault(because = "Verification produces no output worth caching.")
abstract class VerifyRuntimeImageBuildIdentityTask : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val buildIdentity: RegularFileProperty

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val packagedIdentities: ConfigurableFileCollection

  @TaskAction
  fun verify() {
    val source = buildIdentity.get().asFile.toPath()
    packagedIdentities.forEach { candidate ->
      if (!RuntimeImageLicense.matches(source, candidate.toPath())) {
        throw GradleException(
          "Packaged $BUILD_IDENTITY_RECORD is missing or differs from $source at ${candidate.toPath()}.",
        )
      }
    }
  }
}
