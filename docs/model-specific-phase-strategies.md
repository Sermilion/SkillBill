# Model-independent phase execution

Sona passes the configured model and effort to the selected harness. Model names do
not select prompts, strategies, phase contracts, retries, or validation behavior.
The harness resolves aliases and provider configuration. Sona does not rewrite a
requested model based on its name or the provider endpoint.

All models receive the shared phase instructions. There are no model directive
supplements or supplement environment switches. Repository phase instructions
and operator model configuration still apply.

## Existing runs

Execution-plan contracts 0.1, 0.2 and 0.3 remain readable. Recorded model assignments,
profiles, provider metadata and historical strategy aliases retain their original
values. Profiles are historical metadata only. Aliases dispatch to the shared
strategy and never add model-specific instructions.

The removal broadens accepted profile metadata without changing the stored shape
or contract version. No data rewrite is needed. New launches record the configured
model unchanged and the canonical profile. Resuming an accepted attempt preserves
its recorded model assignment and identity while using the shared instructions.
Completed work, commits, checkpoints and history remain intact.

## Validation

Validation runs the required project checks and repairs check failures. It does not
request mutation probes, deliberately broken production copies, or test rewrites
based on those experiments. Historical probe artifacts remain readable; new
validation outputs need no probe artifact and produce no missing-probe warning.
