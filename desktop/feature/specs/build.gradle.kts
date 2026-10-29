plugins {
  id("skillbill.desktop.feature")
}

kotlin {
  sourceSets.commonMain.dependencies {
    implementation(projects.core.common)
    implementation(projects.core.domain)
    implementation(projects.core.designsystem)
    implementation(projects.core.ui)
  }
  sourceSets.jvmMain.dependencies {
    implementation(projects.core.data)
  }
  sourceSets.jvmTest.dependencies {
    implementation(projects.core.testing)
    implementation(libs.kotlinx.coroutines.test)
    implementation(libs.kotlin.test)
    implementation(libs.junit.jupiter)
  }
}
