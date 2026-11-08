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
    compileOnly("dev.skillbill:runtime-infra-launcher")
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.filekit.dialogs)
  }
  sourceSets.jvmTest.dependencies {
    // compileOnly is invisible to tests, which run the runtime types they construct.
    implementation("dev.skillbill:runtime-core")
    implementation("dev.skillbill:runtime-contracts")
    implementation("dev.skillbill:runtime-infra-launcher")
    implementation("dev.skillbill:runtime-domain")
    implementation(projects.core.testing)
    implementation(libs.kotlinx.coroutines.test)
  }
}

dependencies {
  constraints {
    add("jvmMainImplementation", libs.kotlinx.serialization.core) {
      version { strictly(libs.versions.serialization.get()) }
      because("The installed runtime bundles kotlinx-serialization-core 1.9.0, and FileKit requests a newer one.")
    }
    add("jvmMainImplementation", libs.jna) {
      version { strictly(libs.versions.jna.get()) }
      because("The installed runtime bundles JNA 5.14.0, and FileKit requests a newer one.")
    }
    add("jvmMainImplementation", libs.jna.platform) {
      version { strictly(libs.versions.jna.get()) }
      because("The installed runtime bundles JNA 5.14.0, and FileKit requests a newer jna-platform.")
    }
  }
}
