package skillbill.scaffold

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseArtifactLicenseVerifierTest {
  private val repoRoot: Path =
    generateSequence(Path.of("").toAbsolutePath().normalize()) { it.parent }
      .first { Files.isRegularFile(it.resolve("LICENSE")) }
  private val rootLicenseBytes: ByteArray = Files.readAllBytes(repoRoot.resolve("LICENSE"))

  @Test
  fun `accepts zip and tar artifacts carrying the root license and valid checksums`() {
    val fixture = Files.createTempDirectory("skillbill-artifact-verifier")
    val zip = fixture.resolve("runtime.zip")
    val tar = fixture.resolve("skills.tar.gz")
    writeZip(zip, listOf("image/LICENSE" to rootLicenseBytes))
    writeTar(tar, rootLicenseBytes)
    writeChecksum(zip)
    writeChecksum(tar)

    assertEquals(0, runVerifier(zip, tar).exitCode)
  }

  @Test
  fun `desktop release archive verifies when checksum tool defaults to binary records`() {
    val fixture = Files.createTempDirectory("skillbill-desktop-release")
    val app = fixture.resolve("desktop-tray/build/compose/binaries/main/app/Skill Bill")
    Files.createDirectories(app)
    Files.write(app.resolve("Skill Bill.exe"), byteArrayOf(0, 10, 13, -1))
    Files.write(fixture.resolve("LICENSE"), rootLicenseBytes)
    val bin = Files.createDirectory(fixture.resolve("bin"))
    val checksumTool = bin.resolve("sha256sum")
    Files.writeString(
      checksumTool,
      """
      |#!/usr/bin/env bash
      |set -euo pipefail
      |if command -v shasum >/dev/null 2>&1; then
      |  exec shasum -a 256 --binary "${'$'}@"
      |else
      |  exec /usr/bin/sha256sum --binary "${'$'}@"
      |fi
      """.trimMargin(),
    )
    assertTrue(checksumTool.toFile().setExecutable(true))
    val workflow = YAMLMapper().readTree(Files.readString(repoRoot.resolve(".github/workflows/release.yml")))
    val bundle =
      workflow.path("jobs").path("build").path("steps")
        .single { it.path("name").asText() == "Bundle desktop tray archive" }
        .path("run").asText()
    val builder = ProcessBuilder("bash", "-c", bundle).directory(fixture.toFile()).redirectErrorStream(true)
    builder.environment()["PATH"] = bin.toString() + File.pathSeparator + builder.environment()["PATH"].orEmpty()
    builder.environment()["RELEASE_VERSION"] = "0.7.0"
    builder.environment()["HOST_TOKEN"] = "windows-x64"
    val process = builder.start()
    val output = process.inputStream.bufferedReader().readText()
    assertEquals(0, process.waitFor(), output)

    val result = runVerifier(fixture.resolve("skill-bill-desktop-0.7.0-windows-x64.tar.gz"))
    assertEquals(0, result.exitCode, result.output)
  }

  @Test
  fun `uses canonical paths without requiring bash four collection helpers`() {
    val verifier = Files.readString(repoRoot.resolve("scripts/verify_release_artifact_licenses"))

    assertTrue(verifier.contains("image/LICENSE"))
    assertTrue(!verifier.contains("mapfile"))
  }

  @Test
  fun `rejects a missing or byte-drifted license entry`() {
    val fixture = Files.createTempDirectory("skillbill-artifact-verifier")
    val absent = fixture.resolve("absent.zip")
    val drifted = fixture.resolve("drifted.zip")
    writeZip(absent, listOf("runtime/README" to "no license".encodeToByteArray()))
    writeZip(drifted, listOf("image/LICENSE" to "different license".encodeToByteArray()))
    writeChecksum(absent)
    writeChecksum(drifted)

    assertFailure(absent, "canonical image/LICENSE")
    assertFailure(drifted, "LICENSE bytes differ")
  }

  @Test
  fun `rejects missing and byte-drifted skills tar licenses`() {
    val fixture = Files.createTempDirectory("skillbill-artifact-verifier")
    val absent = fixture.resolve("absent.tar.gz")
    val drifted = fixture.resolve("drifted.tar.gz")
    writeTar(absent, null)
    writeTar(drifted, "different license".encodeToByteArray())
    writeChecksum(absent)
    writeChecksum(drifted)

    assertFailure(absent, "exactly one canonical LICENSE")
    assertFailure(drifted, "LICENSE bytes differ")
  }

  @Test
  fun `permits third party license entries beside the canonical runtime license`() {
    val fixture = Files.createTempDirectory("skillbill-artifact-verifier")
    val artifact = fixture.resolve("runtime.zip")
    writeZip(
      artifact,
      listOf(
        "image/LICENSE" to rootLicenseBytes,
        "image/legal/java.base/LICENSE" to "JDK license".encodeToByteArray(),
      ),
    )
    writeChecksum(artifact)

    assertEquals(0, runVerifier(artifact).exitCode)
  }

  @Test
  fun `rejects duplicate canonical skills licenses`() {
    val fixture = Files.createTempDirectory("skillbill-artifact-verifier")
    val artifact = fixture.resolve("skills.tar.gz")
    writeTar(artifact, rootLicenseBytes, duplicateLicense = true)
    writeChecksum(artifact)

    assertFailure(artifact, "exactly one canonical LICENSE")
  }

  @Test
  fun `rejects an absent or mismatched checksum sidecar`() {
    val fixture = Files.createTempDirectory("skillbill-artifact-verifier")
    val artifact = fixture.resolve("checksummed.zip")
    writeZip(artifact, listOf("image/LICENSE" to rootLicenseBytes))

    assertFailure(artifact, "Missing checksum")
    Files.writeString(artifact.resolveSibling("${artifact.name}.sha256"), "${"0".repeat(64)}  ${artifact.name}\n")
    assertFailure(artifact, "Checksum digest differs")
  }

  @Test
  fun `rejects a checksum sidecar for a sibling decoy`() {
    val fixture = Files.createTempDirectory("skillbill-artifact-verifier")
    val artifact = fixture.resolve("checksummed.zip")
    writeZip(artifact, listOf("image/LICENSE" to rootLicenseBytes))
    Files.writeString(
      artifact.resolveSibling("${artifact.name}.sha256"),
      "${sha256(artifact)}  decoy.zip\n",
    )

    assertFailure(artifact, "names decoy.zip")
  }

  private fun assertFailure(
    artifact: Path,
    expectedMessage: String,
  ) {
    val result = runVerifier(artifact)
    assertTrue(result.exitCode != 0, result.output)
    assertTrue(result.output.contains(expectedMessage), result.output)
  }

  private fun runVerifier(vararg artifacts: Path): ProcessResult {
    return runVerifier(artifacts.toList())
  }

  private fun runVerifier(
    artifacts: List<Path>,
    environment: Map<String, String> = emptyMap(),
  ): ProcessResult {
    val processBuilder =
      ProcessBuilder(
        listOf("bash", repoRoot.resolve("scripts/verify_release_artifact_licenses").toString()) +
          artifacts.map(Path::toString),
      )
        .directory(repoRoot.toFile())
        .redirectErrorStream(true)
    processBuilder.environment().putAll(environment)
    val process = processBuilder.start()
    val output = process.inputStream.bufferedReader().readText()
    return ProcessResult(process.waitFor(), output)
  }

  private fun writeZip(
    artifact: Path,
    entries: List<Pair<String, ByteArray>>,
  ) {
    ZipOutputStream(Files.newOutputStream(artifact)).use { output ->
      entries.forEach { (name, content) ->
        output.putNextEntry(ZipEntry(name))
        output.write(content)
        output.closeEntry()
      }
    }
  }

  private fun writeTar(
    artifact: Path,
    licenseBytes: ByteArray?,
    duplicateLicense: Boolean = false,
  ) {
    val source = Files.createTempDirectory("skillbill-artifact-tar-source")
    Files.writeString(source.resolve("README.md"), "Skill Bill skills archive fixture\n")
    if (licenseBytes != null) {
      Files.write(source.resolve("LICENSE"), licenseBytes)
    }
    val entries =
      if (licenseBytes == null) {
        listOf("README.md")
      } else {
        listOf("LICENSE", "README.md") + if (duplicateLicense) listOf("LICENSE") else emptyList()
      }
    val process =
      ProcessBuilder(
        listOf("tar", "-czf", artifact.toString(), "-C", source.toString()) + entries,
      )
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    assertEquals(0, process.waitFor(), output)
  }

  private fun writeChecksum(artifact: Path) {
    Files.writeString(artifact.resolveSibling("${artifact.name}.sha256"), "${sha256(artifact)}  ${artifact.name}\n")
  }

  private fun sha256(artifact: Path): String =
    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact))
      .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

  private data class ProcessResult(val exitCode: Int, val output: String)
}
