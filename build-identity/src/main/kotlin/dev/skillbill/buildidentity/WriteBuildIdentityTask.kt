package dev.skillbill.buildidentity

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class WriteBuildIdentityTask : DefaultTask() {
  @get:Input
  abstract val commit: Property<String>

  @get:Input
  abstract val dirty: Property<Boolean>

  @get:Input
  abstract val releaseTag: Property<String>

  @get:Input
  abstract val installRunId: Property<String>

  @get:InputFile
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val capabilityDeclaration: RegularFileProperty

  @get:OutputFile
  abstract val record: RegularFileProperty

  @TaskAction
  fun write() {
    val declaration = capabilityDeclaration.get().asFile.readText()
    val readSchema = declaration.intConstant("VERSION")
    val incidentDetailVersion = declaration.intConstant("INCIDENT_DETAIL_CAPABILITY_VERSION")
    val minimumIncidentReadSchema = declaration.intConstant("MIN_INCIDENT_DETAIL_READ_SCHEMA")
    val lines =
      listOf(
        BuildIdentityKeys.COMMIT to commit.get(),
        BuildIdentityKeys.DIRTY to dirty.get().toString(),
        BuildIdentityKeys.RELEASE_TAG to releaseTag.get(),
        BuildIdentityKeys.INSTALL_RUN_ID to installRunId.get(),
        BuildIdentityKeys.READ_SCHEMA to readSchema,
        BuildIdentityKeys.INCIDENT_DETAIL_VERSION to incidentDetailVersion,
        BuildIdentityKeys.MIN_INCIDENT_DETAIL_READ_SCHEMA to minimumIncidentReadSchema,
      )
    record.get().asFile.apply {
      parentFile.mkdirs()
      writeText(lines.joinToString(separator = "") { (key, value) -> "$key=$value\n" })
    }
  }
}

private fun String.intConstant(name: String): String {
  val token = Regex("const\\s+val\\s+$name\\s*=\\s*([A-Za-z_][A-Za-z_0-9]*|\\d+)").find(this)?.groupValues?.get(1)
    ?: throw IllegalStateException("The runtime capability declaration has no integer $name.")
  return token.toIntOrNull()?.toString() ?: if (token == "VERSION") intConstant(token)
  else throw IllegalStateException("The runtime capability declaration $name is not an integer.")
}
