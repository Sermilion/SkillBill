plugins {
  id("skillbill.desktop.feature")
}

tasks.withType<Test>().configureEach {
  inputs.property("updatePhaseSnapshots", providers.environmentVariable("SONA_UPDATE_PHASE_SNAPSHOTS").orElse("0"))
}

kotlin {
  sourceSets.commonMain.dependencies {
    implementation(projects.core.common)
  }
  sourceSets.jvmTest.dependencies {
    implementation(projects.core.testing)
    implementation(libs.compose.ui.test)
  }
}
