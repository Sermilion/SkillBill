package dev.skillbill.desktop.buildlogic

import java.io.File
import java.nio.file.Files
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.gradle.api.GradleException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SourceRunStagingTest {
  @TempDir
  lateinit var directory: File

  @BeforeEach
  fun prepareCheckout() {
    directory = directory.canonicalFile
    val git = ProcessBuilder("git", "init", directory.absolutePath).redirectErrorStream(true).start()
    val output = git.inputStream.bufferedReader().use { it.readText() }
    assertEquals(0, git.waitFor(), output)
    write(directory.resolve("desktop/settings.gradle.kts"), "")
    write(directory.resolve("runtime-kotlin/runtime-core/build.gradle.kts"), "")
  }

  @Test
  fun `both staged records carry the same run id and keep their other lines`() {
    val install = directory.resolve("install")
    write(install.resolve("lib/runtime-core.jar"), "jar")
    write(install.resolve("build-identity.properties"), "commit=abc\ninstall_run_id=\ndirty=true\n")
    val resources = directory.resolve("resources")
    write(resources.resolve("build-identity.properties"), "commit=abc\ndirty=true\n")
    val output = directory.resolve("desktop/app/build/source-run")

    SourceRunStaging.stage(directory, install, resources, output, "source-run-1")

    val runtime = output.resolve("runtime/runtime-cli")
    assertEquals("commit=abc\ninstall_run_id=source-run-1\ndirty=true\n", runtime.resolve("build-identity.properties").readText())
    assertEquals("commit=abc\ndirty=true\ninstall_run_id=source-run-1\n", output.resolve("app-resources/build-identity.properties").readText())
    assertTrue(runtime.resolve("lib/runtime-core.jar").isFile)
  }

  @Test
  fun `runFromSource overrides an exported database and leaves the operator file untouched`() {
    val desktop = File(System.getProperty("skillbill.desktop.catalog")).parentFile.parentFile
    val fixture = directory.resolve("desktop").apply { mkdirs() }
    val operatorDatabase = directory.resolve("operator.db").apply { writeText("operator data") }
    val report = directory.resolve("launch.txt")
    val probe = directory.resolve("DatabaseProbe.java")
    probe.writeText(
      """
      import java.nio.file.*;
      public class DatabaseProbe {
        public static void main(String[] args) throws Exception {
          Path database = Path.of(System.getenv("SKILL_BILL_DB"));
          if (!Files.readString(database).equals("fixture data")) throw new IllegalStateException("Fixture was not seeded");
          Files.writeString(database, "scratch data");
          Files.writeString(Path.of(args[0]), database + "\n" + System.getenv("TERM") + "\n" + System.getProperty("user.home"));
        }
      }
      """.trimIndent(),
    )
    val classes = directory.resolve("classes").apply { mkdirs() }
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.path, probe.path))
    write(fixture.resolve("settings.gradle.kts"), "rootProject.name = \"source-run-test\"\nincludeBuild(\"../runtime-kotlin\")")
    val runtime = directory.resolve("runtime-kotlin")
    write(runtime.resolve("settings.gradle.kts"), "rootProject.name = \"runtime-kotlin\"\ninclude(\":runtime-cli\", \":runtime-core\")")
    write(runtime.resolve("runtime-cli/build.gradle.kts"), "tasks.register(\"installDist\")")
    write(runtime.resolve("runtime-core/build.gradle.kts"), """
      tasks.register("writeDesktopFixtureDb") {
        doLast {
          val state = layout.buildDirectory.dir("desktop-fixture/home/.skill-bill").get().asFile
          state.mkdirs()
          state.resolve("review-metrics.db").writeText("fixture data")
        }
      }
    """.trimIndent())
    write(runtime.resolve("runtime-cli/build/install/runtime-cli/lib/runtime-core.jar"), "jar")
    write(runtime.resolve("runtime-cli/build/install/runtime-cli/build-identity.properties"), "install_run_id=\n")
    write(fixture.resolve("resources/build-identity.properties"), "install_run_id=\n")
    val registration = desktop.resolve("app/build.gradle.kts").readText().substringAfter("// Compose registers run and prepareAppResources in its afterEvaluate callback.")
    val taskClasses = StageSourceRunTask::class.java.protectionDomain.codeSource.location.toURI().let(::File)
    fun File.literal() = absolutePath.replace("\\", "\\\\").replace("\"", "\\\"")
    write(
      fixture.resolve("build.gradle.kts"),
      """
      import org.gradle.api.tasks.JavaExec
      buildscript { dependencies { classpath(files("${taskClasses.literal()}")) } }
      layout.buildDirectory.set(file("app/build"))
      afterEvaluate {
        tasks.register<Sync>("prepareAppResources") { from("resources"); into(layout.buildDirectory.dir("prepared")) }
        tasks.register<JavaExec>("run") {
          mainClass.set("DatabaseProbe")
          classpath = files("${classes.literal()}")
        }
      }
      $registration
      afterEvaluate { tasks.named<JavaExec>("runFromSource") { args("${report.literal()}") } }
      """.trimIndent(),
    )
    val log = directory.resolve("gradle.log")
    val wrapper = if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "gradlew"
    val process = ProcessBuilder(
      desktop.resolve(wrapper).absolutePath, "-p", fixture.absolutePath,
      "runFromSource", "--no-build-cache", "--no-configuration-cache", "--console=plain",
    ).redirectErrorStream(true).redirectOutput(log).apply {
      environment()["SKILL_BILL_DB"] = operatorDatabase.absolutePath
      environment()["TERM"] = ""
    }.start()
    assertEquals(0, process.waitFor(), log.readText())
    val home = fixture.resolve("app/build/source-run/home")
    val database = home.resolve(".skill-bill/review-metrics.db")
    assertEquals(listOf(database.absolutePath, "dumb", home.absolutePath), report.readLines())
    assertEquals("scratch data", database.readText())
    assertEquals("operator data", operatorDatabase.readText())
  }

  @Test
  fun `staging refuses redirected outputs before deleting any existing output`() {
    val install = directory.resolve("install")
    val resources = directory.resolve("resources")
    write(install.resolve("build-identity.properties"), "install_run_id=\n")
    write(install.resolve("lib/runtime.jar"), "jar")
    write(resources.resolve("build-identity.properties"), "install_run_id=\n")
    val root = directory.resolve("desktop/app/build/source-run")
    val preserved = root.resolve("runtime/keep.txt")
    write(preserved, "keep")
    val external = directory.resolve("operator")
    write(external.resolve("keep.txt"), "operator")
    Files.createSymbolicLink(root.resolve("app-resources").toPath(), external.toPath())
    assertFailsWith<GradleException> { SourceRunStaging.stage(directory, install, resources, root, "run") }
    assertEquals("keep", preserved.readText())
    assertEquals("operator", external.resolve("keep.txt").readText())
  }

  @Test
  fun `seeding rejects missing or linked fixtures and redirected homes before cleanup`() {
    val root = directory.resolve("desktop/app/build/source-run")
    val preserved = root.resolve("home/keep.txt")
    write(preserved, "keep")
    val fixture = directory.resolve("runtime-kotlin/runtime-core/build/desktop-fixture/home/.skill-bill")
    assertFailsWith<GradleException> { SourceRunPreparation.seed(directory, root, fixture) }
    assertEquals("keep", preserved.readText())
    write(fixture.resolve("review-metrics.db"), "fixture")
    val external = directory.resolve("operator")
    write(external.resolve("keep.txt"), "operator")
    val link = fixture.resolve("nested").toPath()
    Files.createSymbolicLink(link, external.toPath())
    assertFailsWith<GradleException> { SourceRunPreparation.seed(directory, root, fixture) }
    assertEquals("keep", preserved.readText())
    Files.delete(link)
    Files.createSymbolicLink(root.resolve("home/nested").toPath(), external.toPath())
    assertFailsWith<GradleException> { SourceRunPreparation.seed(directory, root, fixture) }
    assertEquals("keep", preserved.readText())
    assertEquals("operator", external.resolve("keep.txt").readText())
  }

  @Test
  fun `source run rejects arbitrary output roots and missing paths behind redirected build directories`() {
    val fixture = directory.resolve("runtime-kotlin/runtime-core/build/desktop-fixture/home/.skill-bill")
    write(fixture.resolve("review-metrics.db"), "fixture")
    val external = directory.resolve("operator")
    write(external.resolve("keep.txt"), "operator")
    assertFailsWith<GradleException> { SourceRunPreparation.seed(directory, external, fixture) }
    val build = directory.resolve("desktop/app/build")
    build.parentFile.mkdirs()
    Files.createSymbolicLink(build.toPath(), external.toPath())
    assertFailsWith<GradleException> { SourceRunPreparation.seed(directory, build.resolve("source-run"), fixture) }
    assertTrue(!external.resolve("source-run").exists())
    assertEquals("operator", external.resolve("keep.txt").readText())
  }

  @Test
  fun `a copied git marker cannot authorize source run cleanup`() {
    val forged = directory.resolve("forged")
    write(forged.resolve(".git"), "gitdir: ${directory.resolve(".git")}\n")
    write(forged.resolve("desktop/settings.gradle.kts"), "")
    write(forged.resolve("runtime-kotlin/runtime-core/build.gradle.kts"), "")
    val root = forged.resolve("desktop/app/build/source-run")
    val preserved = root.resolve("home/keep.txt")
    write(preserved, "keep")
    val fixture = forged.resolve("runtime-kotlin/runtime-core/build/desktop-fixture/home/.skill-bill")
    write(fixture.resolve("review-metrics.db"), "fixture")
    assertFailsWith<GradleException> { SourceRunPreparation.seed(forged, root, fixture) }
    assertEquals("keep", preserved.readText())
  }

  @Test
  fun `a fixed output slot cannot authorize deleting the current operator state`() {
    val root = directory.resolve("desktop/app/build/source-run")
    val operator = root.resolve("home")
    val preserved = operator.resolve(".skill-bill/keep.txt")
    write(preserved, "operator")
    val fixture = directory.resolve("runtime-kotlin/runtime-core/build/desktop-fixture/home/.skill-bill")
    write(fixture.resolve("review-metrics.db"), "fixture")
    assertFailsWith<GradleException> {
      SourceRunPreparation.seed(directory, root, fixture, mapOf("HOME" to operator.absolutePath))
    }
    assertFailsWith<GradleException> {
      SourceRunPreparation.validateStaging(directory, root, directory, directory, mapOf("HOME" to operator.absolutePath))
    }
    assertEquals("operator", preserved.readText())
  }

  private fun write(file: File, text: String) {
    file.parentFile.mkdirs()
    file.writeText(text)
  }
}
