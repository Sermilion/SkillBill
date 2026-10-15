plugins {
  id("skillbill.feature")
}

kotlin {
  sourceSets.commonMain.dependencies {
    implementation(projects.core.domain)
    implementation(projects.core.designsystem)
    implementation(projects.core.ui)
  }
  sourceSets.commonTest.dependencies {
    implementation(projects.core.testing)
  }
}
