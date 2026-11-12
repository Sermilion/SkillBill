# Codex App Server schema provenance

Captured on 2026-11-09 from `codex-cli 0.166.0` with `codex app-server generate-json-schema --out /tmp/skill-530-codex-schema.q8peGI`. The command omitted `--experimental` and did not launch a provider turn.

The retained files are the exported `ClientRequest.json`, `v2/TurnSteerParams.json`, and `v2/TurnSteerResponse.json`. `TurnSteerParams.json` includes its referenced `UserInput`, `TextElement`, and related definitions. `ClientRequest.json` includes the `TurnSteerParams` binding target and all definitions referenced by its request alternatives.

SHA-256 identities:

- `ClientRequest.json`: `42caef9e6f7a544f2565d7696b03b34cd77771b91b50e639faed673bb276c262`
- `v2/TurnSteerParams.json`: `0ed9b59bc284e8405ca88948a12bd411c39103b19c0797f73236cefedb96771f`
- `v2/TurnSteerResponse.json`: `866ba9a77c12b5d570c837d58f73bf40b24e50fa93404bdf792dd984d313b1aa`

The parameters and response digests match the values recorded in `planning-evidence.md`. This is a fresh capture, not recovery of the earlier files. The earlier ClientRequest digest was not retained and is not inferred here.
