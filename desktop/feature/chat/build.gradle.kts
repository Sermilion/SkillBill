plugins {
  id("skillbill.desktop.feature")
}

kotlin {
  sourceSets.commonMain.dependencies {
    implementation(projects.core.common)
    implementation(libs.markdown.renderer)
    implementation(libs.highlights)
    implementation(libs.kotlinx.serialization.json)
  }
  sourceSets.jvmTest.dependencies {
    implementation(projects.core.testing)
  }
}
