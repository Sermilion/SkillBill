package skillbill.di.core

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopFixturePathSafetyTest {
  @TempDir
  lateinit var directory: Path

  @BeforeEach
  fun resolveTemporaryDirectory() {
    directory = directory.toRealPath()
  }

  @Test
  fun `a verified checkout beneath operator state can regenerate only its fixture home`() {
    val operator = directory.resolve("operator")
    val checkout = checkout(operator.resolve(".skill-bill/goals/example/checkout"))
    val home = checkout.resolve("runtime-kotlin/runtime-core/build/desktop-fixture/home")
    val marker = marker(operator.resolve(".skill-bill/keep.txt"))
    val sibling = marker(checkout.parent.resolve("sibling/keep.txt"))
    assertEquals(home, validatedDesktopFixtureHome(home, checkout, environment(operator)))
    Files.createDirectories(home.resolve(".skill-bill"))
    assertEquals(home, validatedDesktopFixtureHome(home, checkout, environment(operator)))
    assertEquals("keep", Files.readString(marker))
    assertEquals("keep", Files.readString(sibling))
  }

  @Test
  fun `writer refuses operator roots ancestors and arbitrary descendants before deletion`() {
    val operator = directory.resolve("operator")
    val checkout = checkout(operator.resolve(".skill-bill/goals/example/checkout"))
    val marker = marker(operator.resolve(".skill-bill/keep.txt"))
    val homes =
      listOf(directory, operator, operator.resolve(".skill-bill"), operator.resolve(".skill-bill/scratch/arbitrary"))
    homes.forEach { home ->
      assertFailsWith<IllegalArgumentException>(home.toString()) {
        writeDesktopFixture(home, checkout, hostEnvironment = environment(operator))
      }
      assertEquals("keep", Files.readString(marker))
    }
  }

  @Test
  fun `another checkout and a forged root cannot authorize a protected destination`() {
    val operator = directory.resolve("operator")
    val checkout = checkout(operator.resolve(".skill-bill/goals/example/checkout"))
    val home = checkout.resolve("runtime-kotlin/runtime-core/build/desktop-fixture/home")
    val marker = marker(home.resolve("keep.txt"))
    listOf(
      checkout(directory.resolve("other")),
      checkout.resolve("runtime-kotlin"),
      directory.resolve("missing"),
    ).forEach {
        root ->
      assertFailsWith<IllegalArgumentException> {
        writeDesktopFixture(
          home,
          root,
          hostEnvironment = environment(operator),
        )
      }
      assertEquals("keep", Files.readString(marker))
    }
  }

  @Test
  fun `a copied git marker cannot claim another repositorys protected fixture directory`() {
    val operator = directory.resolve("operator")
    val real = checkout(directory.resolve("real"))
    val forged = operator.resolve(".skill-bill/goals/forged/checkout")
    listOf("runtime-kotlin/runtime-core/build.gradle.kts", "desktop/settings.gradle.kts").forEach {
      marker(forged.resolve(it))
    }
    Files.writeString(forged.resolve(".git"), "gitdir: ${real.resolve(".git")}\n")
    val home = forged.resolve("runtime-kotlin/runtime-core/build/desktop-fixture/home")
    val preserved = marker(home.resolve("keep.txt"))
    assertFailsWith<IllegalArgumentException> {
      writeDesktopFixture(home, forged, hostEnvironment = environment(operator))
    }
    assertEquals("keep", Files.readString(preserved))
  }

  @Test
  fun `missing destinations behind links and linked fixture entries preserve external files`() {
    val operator = directory.resolve("operator")
    val checkout = checkout(operator.resolve(".skill-bill/goals/example/checkout"))
    val external = directory.resolve("external")
    val marker = marker(external.resolve("keep.txt"))
    val build = checkout.resolve("runtime-kotlin/runtime-core/build")
    Files.createSymbolicLink(build, external)
    val home = build.resolve("desktop-fixture/home")
    assertFailsWith<IllegalArgumentException> {
      writeDesktopFixture(home, checkout, hostEnvironment = environment(operator))
    }
    assertTrue(!Files.exists(external.resolve("desktop-fixture")))
    Files.delete(build)
    Files.createDirectories(home)
    Files.createSymbolicLink(home.resolve("nested"), external)
    assertFailsWith<IllegalArgumentException> {
      writeDesktopFixture(home, checkout, hostEnvironment = environment(operator))
    }
    assertEquals("keep", Files.readString(marker))
    Files.delete(home.resolve("nested"))
    Files.delete(home)
    Files.createSymbolicLink(home, external)
    assertFailsWith<IllegalArgumentException> {
      writeDesktopFixture(home, checkout, hostEnvironment = environment(operator))
    }
    assertEquals("keep", Files.readString(marker))
  }

  @Test
  fun `operator state aliases and unknown operator home cannot bypass deletion protection`() {
    val operator = directory.resolve("operator")
    val checkout = checkout(directory.resolve("checkout"))
    val externalState = directory.resolve("external-state")
    val marker = marker(externalState.resolve("keep.txt"))
    Files.createDirectories(operator)
    Files.createSymbolicLink(operator.resolve(".skill-bill"), externalState)
    assertFailsWith<IllegalArgumentException> {
      writeDesktopFixture(externalState, checkout, hostEnvironment = environment(operator))
    }
    assertFailsWith<IllegalArgumentException> {
      writeDesktopFixture(
        externalState,
        checkout,
        hostEnvironment = emptyMap(),
      )
    }
    val temporary = directory.resolve("temporary-home")
    assertEquals(
      temporary,
      validatedDesktopFixtureHome(temporary, checkout, mapOf("USERPROFILE" to operator.toString())),
    )
    assertEquals("keep", Files.readString(marker))
  }

  @Test
  fun `temporary homes cannot delete checkout source outside operator state`() {
    val operator = directory.resolve("operator")
    val checkout = checkout(directory.resolve("checkout"))
    val preserved = marker(checkout.resolve("runtime-kotlin/keep.txt"))
    listOf(checkout, checkout.parent, checkout.resolve("runtime-kotlin")).forEach { home ->
      assertFailsWith<IllegalArgumentException> {
        writeDesktopFixture(home, checkout, hostEnvironment = environment(operator))
      }
      assertEquals("keep", Files.readString(preserved))
    }
    val expected = checkout.resolve("runtime-kotlin/runtime-core/build/desktop-fixture/home")
    assertEquals(expected, validatedDesktopFixtureHome(expected, checkout, environment(operator)))
  }

  private fun checkout(path: Path): Path {
    Files.createDirectories(path)
    val process = ProcessBuilder("git", "init", path.toString()).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    assertEquals(0, process.waitFor(), output)
    listOf("runtime-kotlin/runtime-core/build.gradle.kts", "desktop/settings.gradle.kts").forEach {
      marker(path.resolve(it))
    }
    return path.toRealPath()
  }

  private fun marker(path: Path): Path {
    Files.createDirectories(path.parent)
    Files.writeString(path, "keep")
    return path
  }

  private fun environment(operator: Path): Map<String, String> = mapOf("HOME" to operator.toString())
}
