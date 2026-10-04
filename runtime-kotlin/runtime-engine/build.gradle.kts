plugins {
  id("skillbill.jvm-library")
  id("skillbill.quality")
  `java-test-fixtures`
}

dependencies {
  api(project(":runtime-contracts"))
  api(project(":runtime-domain"))
  api(project(":runtime-ports"))
  implementation(project(":runtime-application"))
  implementation(libs.kotlin.inject.runtime)
  testFixturesImplementation(project(":runtime-application"))
  testFixturesImplementation(project(":runtime-domain"))
  testFixturesImplementation(project(":runtime-ports"))
  testFixturesImplementation(project(":runtime-infra:sqlite"))
  testFixturesImplementation(project(":runtime-infra:contracts"))
  testFixturesImplementation(testFixtures(project(":runtime-application")))
  testFixturesImplementation(testFixtures(project(":runtime-ports")))
  testImplementation(testFixtures(project(":runtime-ports")))
  testImplementation(testFixtures(project(":runtime-application")))
  testImplementation(project(":runtime-infra:contracts"))
  testImplementation(project(":runtime-infra:workflow"))
  testImplementation(project(":runtime-infra:sqlite"))
  testImplementation(testFixtures(project(":runtime-infra:sqlite")))
  testImplementation(libs.junit.jupiter)
  testImplementation(libs.kotlin.test)
  testImplementation(libs.jackson.dataformat.yaml)
}
