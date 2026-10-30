plugins {
  id("skillbill.desktop.kotlin.inject")
}

kotlin {
  sourceSets.commonMain.dependencies {
    implementation(projects.core.common)
    implementation(projects.core.domain)
  }
  sourceSets.jvmMain.dependencies {
    // Runtime classes come from the installed runtime's class loader at run time, never from the app image.
    compileOnly("dev.skillbill:runtime-core")
    compileOnly("dev.skillbill:runtime-contracts")
  }
  sourceSets.jvmTest.dependencies {
    // compileOnly is invisible to tests, which run the runtime types they construct.
    implementation("dev.skillbill:runtime-core")
    implementation("dev.skillbill:runtime-contracts")
    implementation(projects.core.testing)
    implementation(libs.kotlinx.coroutines.test)
  }
}
