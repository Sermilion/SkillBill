#!/usr/bin/env python3
"""Rebuild FQN import map from git rename pairs and apply import rewrites."""
from __future__ import annotations

import re
import subprocess
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SCAN_ROOTS = [REPO / "runtime-kotlin"]

SYMBOL_RE = re.compile(
    r"^(?:(?:private|internal|public)\s+)?(?:data\s+|sealed\s+|open\s+|abstract\s+|enum\s+)?"
    r"(?:class|object|interface|enum class|val|var)\s+(\w+)",
    re.M,
)
EXT_FUN_RE = re.compile(
    r"^(?:(?:private|internal|public)\s+)?(?:tailrec\s+|suspend\s+)?fun\s+(?:[\w?]+\.)*(\w+)",
    re.M,
)
CONST_RE = re.compile(
    r"^const\s+val\s+(\w+)",
    re.M,
)
PKG_RE = re.compile(r"^package\s+([\w.]+)\s*$", re.M)


def renamed_pairs() -> list[tuple[str, str]]:
    out: list[tuple[str, str]] = []
    text = subprocess.check_output(
        ["git", "diff", "--name-status", "HEAD"],
        cwd=REPO,
        text=True,
    )
    for line in text.splitlines():
        if not line.startswith("R"):
            continue
        parts = line.split("\t")
        if len(parts) != 3:
            continue
        old, new = parts[1], parts[2]
        if old.endswith(".kt"):
            out.append((old, new))
    return out


def symbols_in_source(source: str) -> set[str]:
    symbols = {match.group(1) for match in SYMBOL_RE.finditer(source)}
    symbols.update(match.group(1) for match in EXT_FUN_RE.finditer(source))
    symbols.update(match.group(1) for match in CONST_RE.finditer(source))
    return symbols


def build_fqn_map() -> dict[str, str]:
    mapping: dict[str, str] = {}
    for old_rel, new_rel in renamed_pairs():
        old_text = subprocess.check_output(
            ["git", "show", f"HEAD:{old_rel}"],
            cwd=REPO,
            text=True,
        )
        new_path = REPO / new_rel
        new_text = new_path.read_text(encoding="utf-8")
        old_pkg = PKG_RE.search(old_text)
        new_pkg = PKG_RE.search(new_text)
        if not old_pkg or not new_pkg:
            continue
        old_package = old_pkg.group(1)
        new_package = new_pkg.group(1)
        for symbol in symbols_in_source(old_text):
            mapping[f"{old_package}.{symbol}"] = f"{new_package}.{symbol}"
    return mapping


def apply_imports(fqn_map: dict[str, str]) -> int:
    touched = 0
    ordered = sorted(fqn_map.items(), key=lambda item: -len(item[0]))
    kotlin_paths: list[Path] = []
    for root in SCAN_ROOTS:
        for path in root.rglob("*.kt"):
            if "/build/" in path.as_posix():
                continue
            kotlin_paths.append(path)
    for path in kotlin_paths:
        text = path.read_text(encoding="utf-8")
        original = text
        for old, new in ordered:
            text = text.replace(f"import {old}", f"import {new}")
        if text != original:
            path.write_text(text, encoding="utf-8")
            touched += 1
    return touched


def main() -> None:
    fqn_map = build_fqn_map()
    print(f"FQN mappings: {len(fqn_map)}")
    print(f"Import files touched: {apply_imports(fqn_map)}")


if __name__ == "__main__":
    main()
