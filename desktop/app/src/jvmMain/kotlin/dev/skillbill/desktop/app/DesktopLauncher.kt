package dev.skillbill.desktop.app

import dev.skillbill.desktop.core.data.identity.BuildIdentityRecord
import dev.skillbill.desktop.core.data.runtime.InstalledRuntimeBuildGate
import dev.skillbill.desktop.core.data.runtime.runtimeInstallRoot
import dev.skillbill.desktop.core.domain.model.RuntimeAvailability
import dev.skillbill.desktop.core.domain.model.RuntimeBuildProblem
import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

private const val DELEGATE_MAIN_CLASS_PROPERTY = "skillbill.desktop.mainClass"
private const val DEFAULT_MAIN_CLASS = "dev.skillbill.desktop.app.ApplicationKt"
private const val RESOURCES_DIRECTORY_PROPERTY = "compose.application.resources.dir"

/**
 * Checks the installed runtime against this build, then starts the app in a class loader over the app's jars plus the runtime's
 * `runtime-cli/lib` jars. A refused runtime is shown in this loader's window, and the user relaunches after reinstalling: loading
 * Compose's native libraries a second time in another loader would fail.
 */
fun main(args: Array<String>) {
  val runtimeRoot = runtimeInstallRoot(System.getenv(), System.getProperty("user.home"))
  val installation = InstalledRuntimeBuildGate(runtimeRoot, appIdentityPath()).check()
  when (val availability = installation.availability) {
    RuntimeAvailability.Available -> launchApplication(applicationClasspath(distributionAppDirectory(), installation.runtimeJars), args)
    is RuntimeAvailability.Unavailable -> showSkillBillWindow(refusalText(availability.problem))
  }
}

private fun refusalText(problem: RuntimeBuildProblem): String {
  val reason = when (problem) {
    is RuntimeBuildProblem.NotInstalled -> "The Skill Bill runtime is not installed."
    is RuntimeBuildProblem.Unreadable -> "The Skill Bill runtime's build identity cannot be read."
    is RuntimeBuildProblem.Mismatched ->
      "The installed Skill Bill runtime (${problem.installedIdentity}) does not match this app (${problem.appIdentity})."
  }
  return "$reason\nRun ${problem.reinstallCommand} from the Skill Bill checkout, then relaunch."
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
  return (appClasspath + runtimeJars).map { it.toUri().toURL() }
}

private fun distributionAppDirectory(): Path? {
  val location = Path.of(LauncherMarker::class.java.protectionDomain.codeSource.location.toURI())
  return location.parent?.takeIf { it.fileName?.toString() == "app" }
}

private fun libraryName(fileName: String): String = fileName.removeSuffix(".jar").replace(Regex("-[0-9].*$"), "")

private fun launchApplication(classpath: List<URL>, args: Array<String>) {
  val loader = URLClassLoader(classpath.toTypedArray(), ClassLoader.getPlatformClassLoader())
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
