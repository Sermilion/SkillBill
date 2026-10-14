plugins {
  id("skillbill.kotlin.inject")
  id("skillbill.room")
  id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
  sourceSets.commonMain.dependencies {
    implementation(projects.core.common)
    implementation(projects.core.domain)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.paging.common)
  }
  sourceSets.jvmMain.dependencies {
    implementation("dev.skillbill:runtime-core")
    implementation("dev.skillbill:runtime-contracts")
  }
  sourceSets.jvmTest.dependencies {
    implementation("dev.skillbill:runtime-infra-host")
    implementation("dev.skillbill:runtime-infra-contracts")
    implementation(projects.core.testing)
    // The packaged smoke proves the data store and the settings store together, as the app composes them.
    implementation(projects.core.datastore)
  }
}

dependencies {
  add("jvmTestImplementation", testFixtures("dev.skillbill:runtime-infra-sqlite"))
}

tasks.named<Test>("jvmTest") {
  // The 5,000-record fixtures and the child-JVM writers need more than the default heap.
  maxHeapSize = "2g"
}
