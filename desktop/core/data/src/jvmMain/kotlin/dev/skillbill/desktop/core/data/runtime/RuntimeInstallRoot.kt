package dev.skillbill.desktop.core.data.runtime

import java.nio.file.Path

/** Where `install.sh` puts the runtime: `SKILL_BILL_RUNTIME_DIR` when set and not blank, else `~/.skill-bill/runtime`. */
fun runtimeInstallRoot(variables: Map<String, String>, userHome: String): Path =
  variables["SKILL_BILL_RUNTIME_DIR"]?.takeIf(String::isNotBlank)?.let(Path::of) ?: Path.of(userHome, ".skill-bill", "runtime")
