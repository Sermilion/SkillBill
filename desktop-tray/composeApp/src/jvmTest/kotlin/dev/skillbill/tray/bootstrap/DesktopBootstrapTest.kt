package dev.skillbill.tray.bootstrap

import skillbill.contracts.persistence.DesktopReadSchema
import dev.skillbill.tray.core.domain.model.RuntimeBuildReason
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopBootstrapTest {
  @Test
  fun `an empty runtime install is refused before any runtime jar enters the launch classpath`() {
    val root = Files.createTempDirectory("desktop-bootstrap-missing")
    val runtimeRoot = Files.createDirectory(root.resolve("runtime"))
    val resources = Files.createDirectory(root.resolve("resources"))
    val desktopIdentity = resources.resolve("build-identity.properties")
    Files.writeString(desktopIdentity, identity("same-build"))

    val outcome = resolveBootstrapOutcome(runtimeRoot, desktopIdentity, root.resolve("app"))

    val refused = assertIs<BootstrapOutcome.Refused>(outcome)
    assertEquals(RuntimeBuildReason.NOT_INSTALLED, refused.reason)
    assertEquals("./install.sh --from-source", refused.reinstallCommand)
  }

  @Test
  fun `a matching runtime wins for shared libraries in the application classpath`() {
    val root = Files.createTempDirectory("desktop-bootstrap-matched")
    val runtimeRoot = Files.createDirectory(root.resolve("runtime"))
    val runtimeCli = Files.createDirectory(runtimeRoot.resolve("runtime-cli"))
    val runtimeLib = Files.createDirectory(runtimeCli.resolve("lib"))
    Files.writeString(runtimeCli.resolve("build-identity.properties"), identity("same-build"))
    val runtimeStdlib = Files.createFile(runtimeLib.resolve("kotlin-stdlib-2.2.20.jar"))
    val runtimeCliJar = Files.createFile(runtimeLib.resolve("runtime-cli.jar"))
    val app = Files.createDirectory(root.resolve("app"))
    val desktopStdlib = Files.createFile(app.resolve("kotlin-stdlib-2.2.20.jar"))
    val desktopJar = Files.createFile(app.resolve("composeApp.jar"))
    val desktopIdentity = Files.createDirectory(root.resolve("resources")).resolve("build-identity.properties")
    Files.writeString(desktopIdentity, identity("same-build"))

    val outcome = resolveBootstrapOutcome(runtimeRoot, desktopIdentity, app)

    val launch = assertIs<BootstrapOutcome.Launch>(outcome)
    val classpath = launch.classpath.map { it.toURI() }
    assertEquals(1, classpath.count { it == runtimeStdlib.toUri() })
    assertEquals(1, classpath.count { it == runtimeCliJar.toUri() })
    assertEquals(1, classpath.count { it == desktopJar.toUri() })
    assertFalse(classpath.any { it == desktopStdlib.toUri() })

    val developmentLaunch = assertIs<BootstrapOutcome.Launch>(resolveBootstrapOutcome(runtimeRoot, desktopIdentity, null))
    assertTrue(DesktopBootstrapTest::class.java.protectionDomain.codeSource.location in developmentLaunch.classpath)
    assertTrue(runtimeCliJar.toUri().toURL() in developmentLaunch.classpath)
  }

  private fun identity(commit: String) = "commit=$commit\ndirty=false\nrelease_tag=\ninstall_run_id=\n" +
    "read_schema=${DesktopReadSchema.VERSION}\n" +
    "incident_detail_version=${DesktopReadSchema.INCIDENT_DETAIL_CAPABILITY_VERSION}\n" +
    "min_incident_detail_read_schema=${DesktopReadSchema.MIN_INCIDENT_DETAIL_READ_SCHEMA}\n"
}
