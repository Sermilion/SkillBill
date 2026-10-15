plugins {
  id("skillbill.feature")
  id("org.jetbrains.kotlin.plugin.serialization")
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
