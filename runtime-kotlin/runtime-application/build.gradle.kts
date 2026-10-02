plugins {
  id("skillbill.jvm-library")
  id("skillbill.repo-test")
  id("skillbill.quality")

  `java-test-fixtures`
}

dependencies {
  api(project(":runtime-contracts"))
  api(project(":runtime-domain"))
  api(project(":runtime-ports"))
  implementation(libs.kotlin.inject.runtime)
  implementation(libs.kotlinx.serialization.json)

  testFixturesImplementation(project(":runtime-infra:host"))
  testFixturesImplementation(project(":runtime-infra:contracts"))
  testFixturesImplementation(project(":runtime-infra:workflow"))
  testFixturesImplementation(testFixtures(project(":runtime-ports")))
  testFixturesImplementation(libs.kotlin.test)
  testFixturesImplementation(libs.jackson.dataformat.yaml)
  testImplementation(testFixtures(project(":runtime-application")))
  testImplementation(testFixtures(project(":runtime-ports")))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
  testImplementation(libs.jackson.dataformat.yaml)
}
