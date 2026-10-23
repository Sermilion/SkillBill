package dev.skillbill.tray.composition

import skillbill.contracts.persistence.DesktopReadSchema
import dev.skillbill.tray.core.common.HostEnvironment
import java.nio.file.Files
import java.nio.file.Path

/**
 * A disposable host whose installed runtime and packaged app carry the same build record, so the runtime build gate lets a
 * selection through. The environment is non-empty: an empty one makes the runtime fall back to the host environment.
 */
fun matchedBuildHost(home: Path): HostEnvironment {
  val record = "commit=0123456789abcdef\ndirty=false\nrelease_tag=\ninstall_run_id=\n" +
    "read_schema=${DesktopReadSchema.VERSION}\n" +
    "incident_detail_version=${DesktopReadSchema.INCIDENT_DETAIL_CAPABILITY_VERSION}\n" +
    "min_incident_detail_read_schema=${DesktopReadSchema.MIN_INCIDENT_DETAIL_READ_SCHEMA}\n"
  val appResources = Files.createDirectories(home.resolve("app-resources"))
  Files.writeString(appResources.resolve("build-identity.properties"), record)
  val runtime = Files.createDirectories(home.resolve(".skill-bill").resolve("runtime").resolve("runtime-cli"))
  Files.writeString(runtime.resolve("build-identity.properties"), record)
  return HostEnvironment(mapOf("SKILL_BILL_TEST" to "1"), home.toString(), home.toString(), appResources.toString())
}
