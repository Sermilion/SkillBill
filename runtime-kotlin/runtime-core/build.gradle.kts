plugins {
  alias(libs.plugins.ksp)
  id("skillbill.jvm-library")
  id("skillbill.repo-test")
  id("skillbill.quality")

  `java-test-fixtures`
}

tasks.named<Test>("repoTest") {
  inputs
    .files(
      fileTree(rootProject.layout.projectDirectory) {
        include(
          "**/src/**",
          "**/*.gradle.kts",
          "**/ARCHITECTURE.md",
          "**/agent/**",
          "config/**",
          ".editorconfig",
        )
        exclude("**/build/**", "**/.gradle/**", ".kotlin/**")
      },
    ).withPathSensitivity(PathSensitivity.RELATIVE)
    .withPropertyName("runtimeKotlinArchitectureSources")
}

tasks.named<ProcessResources>("processResources") {
  val skillBillVersion = project.version.toString()
  inputs.property("skillBillVersion", skillBillVersion)
  filesMatching("skillbill/version.properties") {
    expand("skillBillVersion" to skillBillVersion)
  }
}

dependencies {
  testFixturesImplementation(testFixtures(project(":runtime-application")))
  testFixturesImplementation(project(":runtime-infra:host"))
  testFixturesImplementation(project(":runtime-infra:workflow"))
  testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:${libs.versions.kotlin.get()}")
  api(project(":runtime-application"))
  api(project(":runtime-ports"))
  api(project(":runtime-engine"))
  api(project(":runtime-goalrunner"))
  implementation(project(":runtime-domain"))
  implementation(project(":runtime-contracts"))
  testImplementation(libs.kotlinx.serialization.json)
  implementation(project(":runtime-infra:host"))
  implementation(project(":runtime-infra:contracts"))
  implementation(project(":runtime-infra:skills"))
  implementation(project(":runtime-infra:launcher"))
  implementation(project(":runtime-infra:workflow"))
  implementation(project(":runtime-infra:http"))
  implementation(project(":runtime-infra:sqlite"))
  implementation(libs.kotlin.inject.runtime)
  ksp(libs.kotlin.inject.compiler)
  kspTest(libs.kotlin.inject.compiler)

  testImplementation(testFixtures(project(":runtime-application")))
  testImplementation(testFixtures(project(":runtime-engine")))
  testImplementation(testFixtures(project(":runtime-goalrunner")))
  testImplementation(testFixtures(project(":runtime-ports")))
  testImplementation(testFixtures(project(":runtime-infra:sqlite")))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
  testImplementation(libs.jackson.dataformat.yaml)
}

tasks.register<JavaExec>("writeDesktopFixtureDb") {
  group = "application"
  description = "Writes a fresh desktop QA fixture database and spec bundle tree under build/."
  val fixtureHome = layout.buildDirectory.dir("desktop-fixture/home")
  val checkoutPath = rootDir.parentFile.absolutePath
  classpath = sourceSets.named("testFixtures").get().runtimeClasspath
  mainClass.set("skillbill.di.core.DesktopFixtureDbMainKt")
  outputs.dir(fixtureHome)
  outputs.upToDateWhen { false }
  doFirst {
    val homePath = fixtureHome.get().asFile.absolutePath
    setArgs(listOf(homePath, checkoutPath))
    setJvmArgs(listOf("-Duser.home=$homePath"))
  }
}
