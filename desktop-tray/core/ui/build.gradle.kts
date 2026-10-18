plugins {
  id("skillbill.kmp.compose")
  id("skillbill.kotlin.serialization")
}

kotlin {
  sourceSets.commonMain.dependencies {
    api(compose.runtime)
    api(compose.foundation)
    api(compose.ui)
    api(compose.material3)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.core)
    api(libs.lifecycle.viewmodel)
    api(libs.lifecycle.viewmodel.compose)
    api(libs.navigation3.runtime)
    api(projects.core.domain)
    implementation(projects.core.common)
    implementation(projects.core.designsystem)
  }
  sourceSets.commonTest.dependencies {
    implementation(projects.core.testing)
    implementation(libs.kotlinx.coroutines.test)
  }
}
