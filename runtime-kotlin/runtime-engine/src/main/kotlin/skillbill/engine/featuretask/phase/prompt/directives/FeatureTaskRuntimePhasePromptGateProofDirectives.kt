package skillbill.engine.featuretask.phase.prompt.directives

internal fun nonValidatePhaseValidationOwnershipDirective(runsValidationGate: Boolean): String {
  if (runsValidationGate) {
    return ""
  }
  return """
    ## Validation ownership
    Only the validate phase may run the pack validation gate
    (`validation_gate.collect_all_full_gate_command`), `./gradlew check`, `check ${"--"}continue`,
    `skill-bill phase validation`, or any other full repository check suite. The build phase owns the separate
    compile-only gate. This phase must not compile, build,
    execute tests, or run check to prove the work. Ignore any Validation Strategy, plan note,
    acceptance text, review habit, or prior habit that asks you to run check here — that work waits
    for validate. If a receipt carries `tests_executed`, leave it empty.
    """.trimIndent()
}

internal fun nonBuildPhaseBuildOwnershipDirective(runsBuildGate: Boolean): String {
  if (runsBuildGate) {
    return ""
  }
  return """
    ## Build ownership
    The build phase owns the separate compile-only gate (`validation_gate.build_command`). This phase
    must not invoke compile-only proof as a substitute for its own work.
    """.trimIndent()
}
