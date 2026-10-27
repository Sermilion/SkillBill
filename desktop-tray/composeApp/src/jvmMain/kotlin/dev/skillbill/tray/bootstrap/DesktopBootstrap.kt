package dev.skillbill.tray.bootstrap

import dev.skillbill.tray.core.data.identity.BuildIdentityRecord
import dev.skillbill.tray.core.data.identity.BuildCompatibility
import dev.skillbill.tray.core.data.identity.assessBuildCompatibility
import dev.skillbill.tray.core.data.runtime.runtimeInstallRoot
import dev.skillbill.tray.core.domain.model.RuntimeBuildReason
import java.io.File
import java.io.IOException
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

private const val DELEGATE_MAIN_CLASS_PROPERTY = "skillbill.desktop.mainClass"
private const val DEFAULT_MAIN_CLASS = "dev.skillbill.tray.composition.MainKt"
private const val RESOURCES_DIRECTORY_PROPERTY = "compose.application.resources.dir"
private const val UNKNOWN_DESKTOP_BUILD = "unknown"

internal sealed interface BootstrapOutcome {
  data class Launch(val classpath: List<URL>) : BootstrapOutcome

  data class Refused(
    val reason: RuntimeBuildReason,
    val installedIdentity: String?,
    val desktopIdentity: String,
    val reinstallCommand: String,
  ) : BootstrapOutcome
}

fun main(args: Array<String>) {
  System.setProperty("compose.interop.blending", "true")
  while (true) {
    when (val outcome = resolveBootstrapOutcome()) {
      is BootstrapOutcome.Launch -> {
        launchApplication(outcome.classpath, args)
        return
      }
      is BootstrapOutcome.Refused -> {
        val retry = if (outcome.reason == RuntimeBuildReason.INCIDENT_DETAIL_UNAVAILABLE) {
          System.getLogger("dev.skillbill.tray.bootstrap").log(System.Logger.Level.WARNING, "runtime:incident_detail_unavailable")
          RuntimeIncidentDetailsUnavailableWindow.show(outcome)
        } else RuntimeMissingWindow.show(outcome)
        if (!retry) return
      }
    }
  }
}

internal fun resolveBootstrapOutcome(): BootstrapOutcome {
  val runtimeRoot = runtimeInstallRoot(System.getenv(), System.getProperty("user.home"))
  return resolveBootstrapOutcome(
    runtimeRoot = runtimeRoot,
    desktopRecordPath = desktopIdentityPath(),
    desktopAppDirectory = distributionAppDirectory(),
  )
}

internal fun resolveBootstrapOutcome(
  runtimeRoot: Path,
  desktopRecordPath: Path,
  desktopAppDirectory: Path?,
): BootstrapOutcome {
  val runtimeDirectory = runtimeRoot.resolve("runtime-cli")
  val runtimeLib = runtimeDirectory.resolve("lib")
  val desktopRecord = readIdentity(desktopRecordPath)
  val runtimeRecordPath = runtimeDirectory.resolve(BuildIdentityRecord.FILE_NAME)
  val runtimeJars = if (Files.isDirectory(runtimeLib)) {
    try {
      Files.list(runtimeLib).use { paths -> paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }.sorted().toList() }
    } catch (_: IOException) {
      emptyList()
    }
  } else {
    emptyList()
  }
  val installedRecord = readIdentity(runtimeRecordPath)
  val compatibility = if (installedRecord != null && desktopRecord != null) {
    assessBuildCompatibility(installedRecord, desktopRecord)
  } else null
  val reason = when {
    runtimeJars.isEmpty() -> RuntimeBuildReason.NOT_INSTALLED
    installedRecord == null && !Files.exists(runtimeRecordPath) -> RuntimeBuildReason.MISSING
    installedRecord == null -> RuntimeBuildReason.UNREADABLE
    desktopRecord == null -> RuntimeBuildReason.UNREADABLE
    compatibility == BuildCompatibility.INCIDENT_DETAIL_UNAVAILABLE -> RuntimeBuildReason.INCIDENT_DETAIL_UNAVAILABLE
    compatibility != BuildCompatibility.SUPPORTED -> RuntimeBuildReason.MISMATCH
    else -> return BootstrapOutcome.Launch(applicationClasspath(desktopAppDirectory, runtimeJars))
  }
  return BootstrapOutcome.Refused(
    reason = reason,
    installedIdentity = installedRecord?.display(),
    desktopIdentity = desktopRecord?.display() ?: UNKNOWN_DESKTOP_BUILD,
    reinstallCommand = BuildIdentityRecord.reinstallCommand(installedRecord, desktopRecord),
  )
}

private fun desktopIdentityPath(): Path {
  val resources = System.getProperty(RESOURCES_DIRECTORY_PROPERTY)?.takeIf(String::isNotBlank)?.let(Path::of)
  if (resources != null) return resources.resolve(BuildIdentityRecord.FILE_NAME)
  val resource = DesktopBootstrapMarker::class.java.classLoader.getResource(BuildIdentityRecord.FILE_NAME)
  return resource?.let { Path.of(it.toURI()) } ?: Path.of(BuildIdentityRecord.FILE_NAME)
}

private object DesktopBootstrapMarker

private fun readIdentity(path: Path): BuildIdentityRecord? = if (!Files.isRegularFile(path)) null else {
  try {
    BuildIdentityRecord.parse(Files.readString(path))
  } catch (_: IOException) {
    null
  }
}

private fun applicationClasspath(desktopAppDirectory: Path?, runtimeJars: List<Path>): List<URL> {
  val runtimeLibraries = runtimeJars.mapTo(mutableSetOf()) { libraryName(it.fileName.toString()) }
  val desktopEntries = if (desktopAppDirectory == null) {
    System.getProperty("java.class.path").split(File.pathSeparator).map(Path::of)
  } else if (!Files.isDirectory(desktopAppDirectory)) emptyList() else Files.list(desktopAppDirectory).use { paths ->
    paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
      .sorted()
      .toList()
  }
  val desktopClasspath = desktopEntries.filter { entry ->
    Files.isDirectory(entry) || libraryName(entry.fileName.toString()) !in runtimeLibraries
  }
  return (desktopClasspath + runtimeJars).map { it.toUri().toURL() }
}

private fun distributionAppDirectory(): Path? {
  val location = Path.of(DesktopBootstrapMarker::class.java.protectionDomain.codeSource.location.toURI())
  val appDirectory = location.parent
  return appDirectory?.takeIf { it.fileName?.toString() == "app" }
}

private fun libraryName(fileName: String): String = fileName.removeSuffix(".jar").replace(Regex("-[0-9].*$"), "")

private fun launchApplication(classpath: List<URL>, args: Array<String>) {
  val loader = URLClassLoader(classpath.toTypedArray(), ClassLoader.getPlatformClassLoader())
  Thread.currentThread().contextClassLoader = loader
  val mainClass = System.getProperty(DELEGATE_MAIN_CLASS_PROPERTY, DEFAULT_MAIN_CLASS)
  val entryPoint = loader.loadClass(mainClass)
  val lookup = MethodHandles.publicLookup()
  val main = try {
    lookup.findStatic(entryPoint, "main", MethodType.methodType(Void.TYPE, Array<String>::class.java))
  } catch (_: NoSuchMethodException) {
    lookup.findStatic(entryPoint, "main", MethodType.methodType(Void.TYPE))
  }
  if (main.type().parameterCount() == 0) main.invokeWithArguments(emptyList<Any>())
  else main.invokeWithArguments(listOf(args))
}
