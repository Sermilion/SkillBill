plugins {
  id("skillbill.feature")
  id("skillbill.kotlin.serialization")
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
