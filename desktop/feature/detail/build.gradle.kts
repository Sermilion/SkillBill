plugins {
  id("skillbill.desktop.feature")
}

kotlin {
  sourceSets.commonMain.dependencies {
    implementation(projects.core.common)
  }
  sourceSets.jvmTest.dependencies {
    implementation(projects.core.testing)
  }
}
