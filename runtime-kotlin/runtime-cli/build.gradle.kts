plugins {
  alias(libs.plugins.ksp)
  application
  id("skillbill.jvm-library")
  id("skillbill.repo-test")
  id("skillbill.quality")

  id("skillbill.runtime-image")
}

dependencies {

  implementation(project(":runtime-application"))
  implementation(project(":runtime-contracts"))
  implementation(project(":runtime-core"))
  implementation(project(":runtime-domain"))
  implementation(project(":runtime-engine"))
  implementation(project(":runtime-ports"))
  implementation(libs.clikt)
  implementation(libs.kotlin.inject.runtime)
  implementation(libs.kotlinx.serialization.json)
  runtimeOnly(libs.slf4j.nop)
  ksp(libs.kotlin.inject.compiler)

  testImplementation(testFixtures(project(":runtime-application")))
  testImplementation(testFixtures(project(":runtime-engine")))
  testImplementation(testFixtures(project(":runtime-ports")))
  testImplementation(project(":runtime-infra:host"))
  testImplementation(project(":runtime-infra:contracts"))
  testImplementation(project(":runtime-infra:skills"))
  testImplementation(project(":runtime-infra:workflow"))
  testImplementation(project(":runtime-infra:sqlite"))
  testImplementation(testFixtures(project(":runtime-infra:sqlite")))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
}

application {
  mainClass.set("skillbill.cli.core.MainKt")
}

tasks.named<JavaExec>("run") {
  standardInput = System.`in`
}

val validateAgentConfigs by tasks.registering(JavaExec::class) {
  group = "verification"
  description = "Validate repository agent configuration and governed generated-output drift."
  classpath = sourceSets.main.get().runtimeClasspath
  mainClass.set(application.mainClass)
  args("validate-agent-configs", "--repo-root", rootProject.projectDir.parentFile.absolutePath)
  mustRunAfter(tasks.withType<Test>())
}

tasks.named("check") {
  dependsOn(validateAgentConfigs)
}

runtimeImage {
  imageBaseName.set("runtime-cli")
}
