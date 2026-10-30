package dev.skillbill.desktop.buildlogic

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PackagedRuntimeVerificationTest {
  @TempDir
  lateinit var directory: File

  @Test
  fun `Compose runtime jars pass while renamed Skill Bill runtime classes fail`() {
    jar("runtime-desktop-1.12.1.jar", "androidx/compose/runtime/Composer.class")
    jar("renamed-library.jar", "skillbill/infrastructure/sqlite/Migrations.class")

    assertEquals(listOf("lib/app/renamed-library.jar"), packagedRuntimeJars(directory, emptySet()))
  }

  @Test
  fun `declared runtime modules fail even when they contain only resources`() {
    jar("runtime-contracts-0.7.1-SNAPSHOT.jar", "contracts/schema.yaml")

    assertEquals(
      listOf("lib/app/runtime-contracts-0.7.1-SNAPSHOT.jar"),
      packagedRuntimeJars(directory, setOf("runtime-contracts")),
    )
  }

  private fun jar(name: String, entry: String) {
    val file = directory.resolve("lib/app/$name")
    file.parentFile.mkdirs()
    ZipOutputStream(file.outputStream()).use { archive ->
      archive.putNextEntry(ZipEntry(entry))
      archive.write(byteArrayOf(1))
      archive.closeEntry()
    }
  }
}
