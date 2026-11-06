package dev.skillbill.desktop.buildlogic

import java.io.File
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SourceRunStagingTest {
  @TempDir
  lateinit var directory: File

  @Test
  fun `both staged records carry the same run id and keep their other lines`() {
    val install = directory.resolve("install")
    write(install.resolve("lib/runtime-core.jar"), "jar")
    write(install.resolve("build-identity.properties"), "commit=abc\ninstall_run_id=\ndirty=true\n")
    val resources = directory.resolve("resources")
    write(resources.resolve("build-identity.properties"), "commit=abc\ndirty=true\n")
    val output = directory.resolve("source-run")

    SourceRunStaging.stage(install, resources, output, "source-run-1")

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
    write(runtime.resolve("settings.gradle.kts"), "rootProject.name = \"runtime-kotlin\"\ninclude(\":runtime-cli\")")
    write(runtime.resolve("runtime-cli/build.gradle.kts"), "tasks.register(\"installDist\")")
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

  private fun write(file: File, text: String) {
    file.parentFile.mkdirs()
    file.writeText(text)
  }
}
