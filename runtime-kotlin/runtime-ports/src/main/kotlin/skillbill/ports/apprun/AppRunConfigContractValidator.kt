package skillbill.ports.apprun

/** Validates an app-run config document against its canonical schema. */
fun interface AppRunConfigContractValidator {
  fun validate(
    document: String,
    sourceLabel: String,
  )
}
