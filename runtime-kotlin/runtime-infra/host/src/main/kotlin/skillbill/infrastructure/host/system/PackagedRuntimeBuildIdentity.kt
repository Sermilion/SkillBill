package skillbill.infrastructure.host.system

import me.tatarka.inject.annotations.Inject
import skillbill.ports.diagnostics.RuntimeDiagnostics
import skillbill.ports.system.HostPlatformPort
import skillbill.ports.system.RuntimeBuildIdentityPort
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

@Inject
class PackagedRuntimeBuildIdentity(
  private val host: HostPlatformPort,
  private val diagnostics: RuntimeDiagnostics,
) : RuntimeBuildIdentityPort {
  override fun readIdentity(): String? {
    val path = candidates().firstOrNull { Files.isRegularFile(it) } ?: return null
    return try {
      val size = Files.size(path)
      if (size !in 1..MAX_RECORD_BYTES) return malformed()
      val properties = Properties()
      Files.newInputStream(path).use { input ->
        val bytes = input.readNBytes(MAX_RECORD_BYTES + 1)
        if (bytes.size > MAX_RECORD_BYTES) return malformed()
        properties.load(ByteArrayInputStream(bytes))
      }
      formattedIdentity(properties)
    } catch (error: IOException) {
      diagnostics.warning("Packaged runtime build identity is unreadable; omitting the optional build value.", error)
      null
    }
  }

  private fun formattedIdentity(properties: Properties): String? {
    val commit = properties.getProperty("commit")?.takeIf { it.matches(COMMIT) } ?: return malformed()
    val dirty = properties.getProperty("dirty")?.takeIf { it == "true" || it == "false" } ?: return malformed()
    val release = properties.getProperty("release_tag")?.takeIf(RELEASE_TAG::matches)
    return listOfNotNull("commit=$commit", release?.let { "release_tag=$it" }, "dirty=$dirty").joinToString(" ")
  }

  private fun candidates(): Sequence<Path> =
    host.jvmClassPath.split(host.pathSeparator).asSequence().mapNotNull { entry ->
      val classPathEntry = Path.of(entry)
      val libDirectory = classPathEntry.parent
      libDirectory?.parent?.resolve(RECORD_NAME)
    }

  private fun malformed(): String? {
    diagnostics.warning("Packaged runtime build identity is malformed; omitting the optional build value.")
    return null
  }

  private companion object {
    const val RECORD_NAME = "build-identity.properties"
    const val MAX_RECORD_BYTES = 4096
    const val MAX_RELEASE_CHARS = 128
    val COMMIT = Regex("[A-Za-z0-9._-]{1,80}")
    val RELEASE_TAG = Regex("[A-Za-z0-9._+-]{1,$MAX_RELEASE_CHARS}")
  }
}
