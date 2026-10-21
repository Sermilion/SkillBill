package dev.skillbill.tray.core.data.runtime

import java.nio.file.Path

/** Where `install.sh` puts the runtime: `SKILL_BILL_RUNTIME_DIR` when set, else `~/.skill-bill/runtime`. */
fun runtimeInstallRoot(variables: Map<String, String>, userHome: String): Path =
  variables["SKILL_BILL_RUNTIME_DIR"]?.takeIf(String::isNotEmpty)?.let(Path::of) ?: Path.of(userHome, ".skill-bill", "runtime")
