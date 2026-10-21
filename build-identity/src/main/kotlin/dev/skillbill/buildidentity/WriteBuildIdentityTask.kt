package dev.skillbill.buildidentity

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
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

  @get:OutputFile
  abstract val record: RegularFileProperty

  @TaskAction
  fun write() {
    val lines =
      listOf(
        BuildIdentityKeys.COMMIT to commit.get(),
        BuildIdentityKeys.DIRTY to dirty.get().toString(),
        BuildIdentityKeys.RELEASE_TAG to releaseTag.get(),
        BuildIdentityKeys.INSTALL_RUN_ID to installRunId.get(),
      )
    record.get().asFile.apply {
      parentFile.mkdirs()
      writeText(lines.joinToString(separator = "") { (key, value) -> "$key=$value\n" })
    }
  }
}
