package dev.skillbill.desktop.app

import dev.skillbill.desktop.core.data.identity.BuildIdentityRecord
import dev.skillbill.desktop.core.data.runtime.InstalledRuntimeBuildGate
import dev.skillbill.desktop.core.data.runtime.runtimeInstallRoot
import dev.skillbill.desktop.core.domain.model.RuntimeAvailability
import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path

private const val DELEGATE_MAIN_CLASS_PROPERTY = "skillbill.desktop.mainClass"
private const val DEFAULT_MAIN_CLASS = "dev.skillbill.desktop.app.ApplicationKt"
private const val REFUSAL_CLASS = "dev.skillbill.desktop.app.RuntimeUnavailableAppKt"
private const val RESOURCES_DIRECTORY_PROPERTY = "compose.application.resources.dir"

/**
 * Checks the installed runtime against this build, then starts the app in one class loader over the app's jars plus the runtime's
 * `runtime-cli/lib` jars. A refused runtime is shown by the refusal window inside that same loader, without the runtime's jars;
 * when Retry finds a matched runtime, its jars join the loader and the app starts there, so Compose's native libraries load once.
 */
fun main(args: Array<String>) {
  val runtimeRoot = runtimeInstallRoot(System.getenv(), System.getProperty("user.home"))
  val appRecord = appIdentityPath()
  val installation = InstalledRuntimeBuildGate(runtimeRoot, appRecord).check()
  val appDirectory = distributionAppDirectory()
  when (installation.availability) {
    RuntimeAvailability.Available -> launchApplication(AppClassLoader(applicationClasspath(appDirectory, installation.runtimeJars)), args)
    is RuntimeAvailability.Unavailable -> {
      val loader = AppClassLoader(applicationClasspath(appDirectory, emptyList()))
      val runtimeJars = showRefusal(loader, runtimeRoot, appRecord) ?: return
      loader.addJars(runtimeJars.map(Path::of))
      launchApplication(loader, args)
    }
  }
}

private fun showRefusal(loader: AppClassLoader, runtimeRoot: Path, appRecord: Path): Array<String>? {
  Thread.currentThread().contextClassLoader = loader
  val refusal = MethodHandles.publicLookup().findStatic(
    loader.loadClass(REFUSAL_CLASS),
    "runRuntimeUnavailable",
    MethodType.methodType(Array<String>::class.java, String::class.java, String::class.java),
  )
  return refusal.invokeWithArguments(runtimeRoot.toString(), appRecord.toString()) as Array<String>?
}

private fun appIdentityPath(): Path {
  val resources = System.getProperty(RESOURCES_DIRECTORY_PROPERTY)?.takeIf(String::isNotBlank)?.let(Path::of)
  if (resources != null) return resources.resolve(BuildIdentityRecord.FILE_NAME)
  val resource = LauncherMarker::class.java.classLoader.getResource(BuildIdentityRecord.FILE_NAME)
  return resource?.let { Path.of(it.toURI()) } ?: Path.of(BuildIdentityRecord.FILE_NAME)
}

private object LauncherMarker

private fun applicationClasspath(appDirectory: Path?, runtimeJars: List<Path>): List<URL> {
  val runtimeLibraries = runtimeJars.mapTo(mutableSetOf()) { libraryName(it.fileName.toString()) }
  val appEntries = if (appDirectory == null) {
    System.getProperty("java.class.path").split(File.pathSeparator).map(Path::of)
  } else if (!Files.isDirectory(appDirectory)) emptyList() else Files.list(appDirectory).use { paths ->
    paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }.sorted().toList()
  }
  val appClasspath = appEntries.filter { entry -> Files.isDirectory(entry) || libraryName(entry.fileName.toString()) !in runtimeLibraries }
  val launcher = Path.of(LauncherMarker::class.java.protectionDomain.codeSource.location.toURI())
  return (applicationEntriesFirst(appClasspath, launcher) + runtimeJars).map { it.toUri().toURL() }
}

internal fun applicationEntriesFirst(entries: List<Path>, launcher: Path): List<Path> =
  entries.filter { it == launcher } + entries.filterNot { it == launcher }

private fun distributionAppDirectory(): Path? {
  val location = Path.of(LauncherMarker::class.java.protectionDomain.codeSource.location.toURI())
  return location.parent?.takeIf { it.fileName?.toString() == "app" }
}

private fun launchApplication(loader: AppClassLoader, args: Array<String>) {
  Thread.currentThread().contextClassLoader = loader
  val entryPoint = loader.loadClass(System.getProperty(DELEGATE_MAIN_CLASS_PROPERTY, DEFAULT_MAIN_CLASS))
  val lookup = MethodHandles.publicLookup()
  val main = try {
    lookup.findStatic(entryPoint, "main", MethodType.methodType(Void.TYPE, Array<String>::class.java))
  } catch (_: NoSuchMethodException) {
    lookup.findStatic(entryPoint, "main", MethodType.methodType(Void.TYPE))
  }
  if (main.type().parameterCount() == 0) main.invokeWithArguments(emptyList<Any>()) else main.invokeWithArguments(listOf(args))
}
