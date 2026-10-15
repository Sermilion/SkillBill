plugins {
  id("skillbill.kmp.compose")
}

kotlin {
  sourceSets.commonMain.dependencies {
    api(compose.runtime)
    api(compose.foundation)
    api(compose.ui)
    api(compose.material3)
    api(libs.kotlinx.coroutines.core)
    api(libs.lifecycle.viewmodel)
    api(libs.lifecycle.viewmodel.compose)
    api(projects.core.domain)
    implementation(projects.core.common)
    implementation(projects.core.designsystem)
  }
  sourceSets.commonTest.dependencies {
    implementation(projects.core.testing)
    implementation(libs.kotlinx.coroutines.test)
  }
}
