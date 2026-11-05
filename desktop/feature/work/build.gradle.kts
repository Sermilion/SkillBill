plugins {
  id("skillbill.desktop.feature")
}

kotlin {
  sourceSets.commonMain.dependencies {
    implementation(compose.material3)
  }
  sourceSets.jvmTest.dependencies {
    implementation(projects.core.testing)
  }
}
