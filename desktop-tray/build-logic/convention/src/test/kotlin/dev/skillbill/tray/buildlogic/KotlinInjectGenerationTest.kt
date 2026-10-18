package dev.skillbill.tray.buildlogic

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KotlinInjectGenerationTest {
  @TempDir
  lateinit var directory: File

  @Test
  fun `kotlin-inject generates one component from commonMain next to lifecycle navigation and saved state`() {
    val fixture = ConventionFixture(
      directory,
      mapOf(
        "lib" to """
          plugins { id("skillbill.kmp.compose"); id("skillbill.kotlin.inject") }
          kotlin {
            sourceSets.commonMain.dependencies {
              implementation(libs.lifecycle.viewmodel.navigation3)
              implementation(libs.navigation3.ui)
              implementation(libs.savedstate)
            }
          }
        """,
      ),
    )
    fixture.write(
      "lib/src/commonMain/kotlin/fixture/AppComponent.kt",
      """
      package fixture

      import me.tatarka.inject.annotations.Component
      import me.tatarka.inject.annotations.Inject

      @Inject class Greeter { fun greet() = "hello" }

      @Component abstract class AppComponent { abstract val greeter: Greeter }
      """.trimIndent(),
    )

    val result = fixture.run(":lib:compileKotlinJvm")

    assertEquals(org.gradle.testkit.runner.TaskOutcome.SUCCESS, result.task(":lib:compileKotlinJvm")?.outcome)
    val generated = fixture.file("lib/build/generated/ksp/jvm").walkTopDown().filter { it.name == "InjectAppComponent.kt" }.toList()
    assertEquals(1, generated.size, "Expected exactly one generated component but found $generated")
    assertTrue(generated.single().readText().contains("class InjectAppComponent"), "Generated component has an unexpected shape.")
  }
}
