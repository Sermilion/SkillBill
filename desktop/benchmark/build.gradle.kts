plugins {
  id("skillbill.desktop.kmp.library")
  id("skillbill.desktop.kotlin.inject")
}

kotlin {
  sourceSets.jvmMain.dependencies {
    implementation(projects.core.common)
    implementation(projects.core.domain)
    implementation(projects.core.data)
    implementation(projects.core.datastore)
    implementation(projects.core.ui)
    implementation(libs.kotlinx.coroutines.core)
    implementation("dev.skillbill:runtime-core")
    implementation("dev.skillbill:runtime-contracts")
    implementation("dev.skillbill:runtime-infra-host")
    implementation("dev.skillbill:runtime-infra-sqlite")
  }
}

tasks.register<JavaExec>("run") {
  group = "application"
  mainClass = "dev.skillbill.desktop.benchmark.BenchmarkMainKt"
  classpath = kotlin.targets.getByName("jvm").compilations.getByName("main").output.allOutputs +
    configurations.getByName("jvmRuntimeClasspath")
  maxHeapSize = "2g"
}
