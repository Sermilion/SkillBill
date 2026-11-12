package skillbill.contracts.apprun

object AppRunScriptContract {
  const val BOOT = "boot"
  const val RUN = "run"
  const val ENV_AVD = "APP_RUN_AVD"
  const val ENV_CHECKOUT = "APP_RUN_CHECKOUT"
  const val ENV_SERIAL = "ANDROID_SERIAL"
  const val ENV_PLATFORM = "APP_RUN_PLATFORM"
  const val ENV_MODULE = "APP_RUN_MODULE"
  const val ENV_VARIANT = "APP_RUN_VARIANT"
  const val MARKER_PREFIX = "##app-run"
  const val SERIAL = "serial"
  const val STEP = "step"
  const val FAILED_TASK = "failed-task"
  const val CANDIDATES = "candidates"
  const val LAUNCHED = "launched"
  const val FAILURE = "failure"
  const val STEP_BUILD = "build"
  const val STEP_LAUNCH = "launch"
  const val EXIT_OK = 0
  const val EXIT_MODULE_UNRESOLVED = 2
  const val EXIT_BUILD_FAILED = 3
  const val EXIT_LAUNCH_FAILED = 4
  const val EXIT_BOOT_FAILED = 5
  const val FAILURE_NO_APP_MODULE = "no-app-module"
  const val PLATFORM_ANDROID = "android"
  const val INVOCATION_PREFIX = "app-run-"
}
