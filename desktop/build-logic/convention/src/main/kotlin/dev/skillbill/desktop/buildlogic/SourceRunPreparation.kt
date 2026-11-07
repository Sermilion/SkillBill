package dev.skillbill.desktop.buildlogic

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import org.gradle.api.GradleException

object SourceRunPreparation {
  fun seed(
    checkoutRoot: File,
    outputRoot: File,
    fixtureState: File,
    hostEnvironment: Map<String, String> = System.getenv(),
  ) {
    val root = validateRoot(checkoutRoot, outputRoot, hostEnvironment)
    val expectedFixture = root.checkout.resolve("runtime-kotlin/runtime-core/build/desktop-fixture/home/.skill-bill")
    requireExactPath(fixtureState.toPath(), expectedFixture)
    requireNotContainingOperatorState(expectedFixture, root.operatorState)
    requireSafeTree(expectedFixture)
    val database = expectedFixture.resolve("review-metrics.db")
    if (!Files.isRegularFile(database, NOFOLLOW_LINKS)) {
      throw GradleException(
        "Generated desktop fixture database is missing: $database. " +
          "The source fixture task is :runtime-core:writeDesktopFixtureDb.",
      )
    }
    val home = root.output.resolve("home")
    requireSafeTree(home)
    recreate(home.toFile())
    expectedFixture.toFile().copyRecursively(home.resolve(".skill-bill").toFile(), overwrite = true)
  }

  internal fun validateStaging(
    checkoutRoot: File,
    outputRoot: File,
    runtimeInstall: File,
    appResources: File,
    hostEnvironment: Map<String, String> = System.getenv(),
  ) {
    val root = validateRoot(checkoutRoot, outputRoot, hostEnvironment)
    requireSafeTree(runtimeInstall.toPath().toAbsolutePath().normalize())
    requireSafeTree(appResources.toPath().toAbsolutePath().normalize())
    listOf("runtime", "app-resources", "home").forEach { requireSafeTree(root.output.resolve(it)) }
  }

  internal fun recreate(directory: File) {
    val path = directory.toPath()
    try {
      if (entryExists(path)) {
        Files.walk(path).use { paths ->
          paths.sorted(Comparator.reverseOrder<Path>()).forEach(Files::delete)
        }
      }
      Files.createDirectories(path)
    } catch (error: IOException) {
      throw GradleException("Could not recreate staging directory: $path", error)
    } catch (error: SecurityException) {
      throw GradleException("Could not access staging directory: $path", error)
    }
  }

  private fun validateRoot(
    checkoutRoot: File,
    outputRoot: File,
    hostEnvironment: Map<String, String>,
  ): SourceRunRoot = checkedPath(checkoutRoot.toPath()) {
    val checkout = checkoutRoot.toPath().toRealPath()
    if (checkoutRoot.toPath().toAbsolutePath().normalize() != checkout) {
      throw GradleException("Refusing redirected source-run checkout path: $checkoutRoot resolves to $checkout")
    }
    val marker = checkout.resolve(".git")
    if (!Files.isDirectory(marker, NOFOLLOW_LINKS) && !Files.isRegularFile(marker, NOFOLLOW_LINKS)) {
      throw GradleException("Source run requires a local Git worktree marker: $marker")
    }
    val git = ProcessBuilder("git", "-C", checkout.toString(), "worktree", "list", "--porcelain", "-z")
      .redirectErrorStream(true)
      .apply { listOf("GIT_DIR", "GIT_WORK_TREE", "GIT_COMMON_DIR").forEach { environment().remove(it) } }
      .start()
    val output = git.inputStream.bufferedReader().use { it.readText() }
    val registered = output.split('\u0000').filter { it.startsWith("worktree ") }
    if (git.waitFor() != 0 || registered.none { Path.of(it.removePrefix("worktree ")).normalize() == checkout }) {
      throw GradleException("Source run requires an exact Git worktree root: $checkout")
    }
    listOf("runtime-kotlin/runtime-core/build.gradle.kts", "desktop/settings.gradle.kts").forEach { relative ->
      val file = checkout.resolve(relative)
      requireSafePath(file)
      if (!Files.isRegularFile(file, NOFOLLOW_LINKS)) throw GradleException("Missing source-run checkout input: $file")
    }
    val expected = checkout.resolve("desktop/app/build/source-run")
    requireExactPath(outputRoot.toPath(), expected)
    val operatorHome = hostEnvironment["HOME"]?.takeIf(String::isNotBlank)
      ?: hostEnvironment["USERPROFILE"]?.takeIf(String::isNotBlank)
      ?: throw GradleException("Cannot identify operator home before preparing source run: $expected")
    val operatorState = physicalPath(Path.of(operatorHome).resolve(".skill-bill"))
    requireNotContainingOperatorState(expected, operatorState)
    SourceRunRoot(checkout, expected, operatorState)
  }

  private fun requireNotContainingOperatorState(path: Path, operatorState: Path) {
    if (operatorState.startsWith(path)) {
      throw GradleException("Refusing source-run path $path because it contains operator state $operatorState")
    }
  }

  private fun physicalPath(path: Path): Path {
    val absolute = path.toAbsolutePath().normalize()
    return try {
      absolute.toRealPath()
    } catch (error: NoSuchFileException) {
      if (Files.isSymbolicLink(absolute)) {
        throw GradleException("Cannot resolve dangling operator-state link: $absolute", error)
      }
      val parent = absolute.parent ?: throw error
      physicalPath(parent).resolve(absolute.fileName)
    }
  }

  private fun requireExactPath(requested: Path, expected: Path) {
    if (requested.toAbsolutePath().normalize() != expected) {
      throw GradleException("Refusing source-run path $requested; expected checkout-owned path $expected")
    }
    requireSafePath(expected)
  }

  private fun requireSafePath(path: Path) = checkedPath(path) {
    var current = path.root
    path.forEach { name ->
      current = current.resolve(name)
      if (Files.isSymbolicLink(current)) throw GradleException("Refusing redirected source-run path: $current")
      if (entryExists(current) && current.toRealPath() != current) {
        throw GradleException("Refusing redirected source-run path: $current")
      }
    }
  }

  private fun requireSafeTree(path: Path) = checkedPath(path) {
    requireSafePath(path)
    if (entryExists(path)) {
      if (!Files.isDirectory(path, NOFOLLOW_LINKS)) throw GradleException("Source-run tree is not a directory: $path")
      Files.walk(path).use { paths ->
        paths.forEach { entry ->
          if (Files.isSymbolicLink(entry) || entry.toRealPath() != entry) {
            throw GradleException("Refusing redirected source-run entry: $entry")
          }
        }
      }
    }
  }

  private fun entryExists(path: Path): Boolean =
    try {
      Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
      true
    } catch (_: NoSuchFileException) {
      false
    }

  private fun <T> checkedPath(path: Path, block: () -> T): T =
    try {
      block()
    } catch (error: IOException) {
      throw GradleException("Cannot verify source-run path before mutation: $path", error)
    } catch (error: SecurityException) {
      throw GradleException("Cannot access source-run path before mutation: $path", error)
    }

  private data class SourceRunRoot(val checkout: Path, val output: Path, val operatorState: Path)
}
