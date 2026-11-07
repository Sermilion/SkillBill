package skillbill.di.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal fun validatedDesktopFixtureHome(
  home: Path,
  checkoutRoot: Path,
  hostEnvironment: Map<String, String>,
): Path =
  fixturePathCheck(home) {
    val checkout = verifiedFixtureCheckout(checkoutRoot)
    val operatorHome =
      hostEnvironment["HOME"]?.takeIf(String::isNotBlank)
        ?: hostEnvironment["USERPROFILE"]?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("Cannot identify operator home before writing desktop fixture at $home")
    val operatorState = fixturePhysicalPath(Path.of(operatorHome).resolve(".skill-bill"))
    val target = home.toAbsolutePath().normalize()
    val physicalTarget = fixturePhysicalPath(target)
    require(!operatorState.startsWith(physicalTarget)) {
      "Refusing to delete desktop fixture home $target because it contains operator state $operatorState"
    }
    require(!checkout.startsWith(physicalTarget)) {
      "Refusing desktop fixture home $target because it contains the verified checkout $checkout"
    }
    if (physicalTarget.startsWith(operatorState) || physicalTarget.startsWith(checkout)) {
      val expected = checkout.resolve("runtime-kotlin/runtime-core/build/desktop-fixture/home")
      require(target == expected && physicalTarget == expected) {
        "Refusing desktop fixture at $target inside protected state or checkout $checkout; " +
          "operator state $operatorState; expected checkout-owned home $expected"
      }
    }
    requireFixturePathWithoutLinks(target)
    requireFixtureTreeWithoutLinks(target)
    target
  }

private fun verifiedFixtureCheckout(checkoutRoot: Path): Path {
  val checkout = checkoutRoot.toRealPath()
  require(checkoutRoot.toAbsolutePath().normalize() == checkout) {
    "Refusing redirected desktop fixture checkout path: $checkoutRoot resolves to $checkout"
  }
  require(Files.isDirectory(checkout)) { "Desktop fixture checkout is not a directory: $checkout" }
  val gitMarker = checkout.resolve(".git")
  require(Files.isDirectory(gitMarker, NOFOLLOW_LINKS) || Files.isRegularFile(gitMarker, NOFOLLOW_LINKS)) {
    "Desktop fixture requires a local Git worktree marker: $gitMarker"
  }
  val process =
    ProcessBuilder("git", "-C", checkout.toString(), "worktree", "list", "--porcelain", "-z")
      .redirectErrorStream(true)
      .apply { listOf("GIT_DIR", "GIT_WORK_TREE", "GIT_COMMON_DIR").forEach { environment().remove(it) } }
      .start()
  val output = process.inputStream.bufferedReader().use { it.readText() }
  val registered = output.split('\u0000').filter { it.startsWith("worktree ") }
  require(process.waitFor() == 0 && registered.any { Path.of(it.removePrefix("worktree ")).normalize() == checkout }) {
    "Desktop fixture requires an exact Git worktree root: $checkout"
  }
  listOf("runtime-kotlin/runtime-core/build.gradle.kts", "desktop/settings.gradle.kts").forEach { relative ->
    val file = checkout.resolve(relative)
    requireFixturePathWithoutLinks(file)
    require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "Missing desktop fixture checkout input: $file" }
  }
  return checkout
}

private fun fixturePhysicalPath(path: Path): Path {
  val absolute = path.toAbsolutePath().normalize()
  return try {
    absolute.toRealPath()
  } catch (error: NoSuchFileException) {
    require(!Files.isSymbolicLink(absolute)) { "Refusing dangling fixture path link: $absolute" }
    val parent = absolute.parent ?: throw error
    fixturePhysicalPath(parent).resolve(absolute.fileName)
  }
}

private fun requireFixturePathWithoutLinks(path: Path) {
  var current = path.root
  path.forEach { name ->
    current = current.resolve(name)
    require(!Files.isSymbolicLink(current)) { "Refusing redirected desktop fixture path: $current" }
    if (fixtureEntryExists(current)) {
      require(current.toRealPath() == current) { "Refusing redirected desktop fixture path: $current" }
    }
  }
}

private fun requireFixtureTreeWithoutLinks(home: Path) {
  if (!fixtureEntryExists(home)) return
  require(Files.isDirectory(home, NOFOLLOW_LINKS)) { "Desktop fixture home is not a directory: $home" }
  Files.walk(home).use { paths ->
    paths.forEach { path ->
      require(!Files.isSymbolicLink(path) && path.toRealPath() == path) {
        "Refusing redirected desktop fixture entry: $path"
      }
    }
  }
}

private fun fixtureEntryExists(path: Path): Boolean =
  try {
    Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    true
  } catch (_: NoSuchFileException) {
    false
  }

private fun <T> fixturePathCheck(
  home: Path,
  block: () -> T,
): T =
  try {
    block()
  } catch (error: IOException) {
    throw IllegalArgumentException("Cannot verify desktop fixture paths before modifying $home", error)
  } catch (error: SecurityException) {
    throw IllegalArgumentException("Cannot access desktop fixture paths before modifying $home", error)
  }
