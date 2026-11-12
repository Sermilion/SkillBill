package skillbill.infrastructure.skills.scaffold

import skillbill.error.core.SkillBillRuntimeException
import skillbill.error.shellcontent.ManifestFailureCode
import skillbill.infrastructure.skills.scaffold.platformpack.loader.apprun.AppRunManifestKeys
import skillbill.infrastructure.skills.scaffold.platformpack.loader.parseAppRun
import skillbill.scaffold.model.QaTargetKind
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ShellContentLoaderAppRunTest {
  private val packRoot = Files.createTempDirectory("skillbill-app-run-pack")

  @AfterTest
  fun cleanUp() {
    packRoot.toFile().deleteRecursively()
  }

  @Test
  fun `absent declaration is null and declared recipe retains platforms and script`() {
    assertNull(parseAppRun(emptyMap<String, Any?>(), "kmp", packRoot))
    Files.createDirectories(packRoot.resolve("app-run"))
    Files.writeString(packRoot.resolve("app-run/android-app-run.sh"), "#!/usr/bin/env bash\n")

    val parsed =
      requireNotNull(
        parseAppRun(
          mapOf(
            AppRunManifestKeys.APP_RUN to
              mapOf(
                AppRunManifestKeys.CONTRACT_VERSION to "0.1",
                AppRunManifestKeys.PLATFORMS to listOf("android"),
                AppRunManifestKeys.SCRIPT to "app-run/android-app-run.sh",
              ),
          ),
          "kmp",
          packRoot,
        ),
      )

    assertEquals(setOf(QaTargetKind("android")), parsed.platforms)
    assertEquals("app-run/android-app-run.sh", parsed.script)
  }

  @Test
  fun `malformed declarations fail with the app_run field and typed manifest error`() {
    Files.createDirectories(packRoot.resolve("app-run"))
    Files.writeString(packRoot.resolve("app-run/script.sh"), "script")
    val outside = Files.createTempFile("skillbill-app-run-outside", ".sh")
    val symlink = packRoot.resolve("app-run/link.sh")
    Files.createSymbolicLink(symlink, outside)
    val malformed =
      listOf<Any?>(
        null,
        mapOf(
          AppRunManifestKeys.CONTRACT_VERSION to "0.1",
          AppRunManifestKeys.PLATFORMS to listOf("android", "android"),
          AppRunManifestKeys.SCRIPT to "app-run/script.sh",
        ),
        mapOf(
          AppRunManifestKeys.CONTRACT_VERSION to "0.1",
          AppRunManifestKeys.PLATFORMS to listOf("android"),
          AppRunManifestKeys.SCRIPT to "../outside.sh",
        ),
        mapOf(
          AppRunManifestKeys.CONTRACT_VERSION to "0.1",
          AppRunManifestKeys.PLATFORMS to listOf("android"),
          AppRunManifestKeys.SCRIPT to "app-run/missing.sh",
        ),
        mapOf(
          AppRunManifestKeys.CONTRACT_VERSION to "0.2",
          AppRunManifestKeys.PLATFORMS to listOf("android"),
          AppRunManifestKeys.SCRIPT to "app-run/script.sh",
        ),
        mapOf(
          AppRunManifestKeys.CONTRACT_VERSION to "0.1",
          AppRunManifestKeys.PLATFORMS to listOf("android"),
          AppRunManifestKeys.SCRIPT to "app-run/link.sh",
        ),
      )

    malformed.forEach { declaration ->
      val failure =
        assertFailsWith<SkillBillRuntimeException> {
          parseAppRun(mapOf(AppRunManifestKeys.APP_RUN to declaration), "kmp", packRoot)
        }
      assertEquals(ManifestFailureCode.INVALID_MANIFEST_SCHEMA, failure.code)
      kotlin.test.assertContains(failure.message.orEmpty(), "app_run")
    }
    Files.deleteIfExists(outside)
  }
}
