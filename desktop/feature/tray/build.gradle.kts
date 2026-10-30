plugins {
  id("skillbill.desktop.feature")
}

kotlin {
  sourceSets.jvmTest.dependencies {
    implementation(projects.core.testing)
    implementation(libs.kotlinx.coroutines.test)
  }
}
