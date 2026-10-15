import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

fixture_root = Path(__file__).resolve().parent
parser = argparse.ArgumentParser()
parser.add_argument("output", type=Path)
args = parser.parse_args()
output = args.output.resolve()
output.mkdir(parents=True, exist_ok=True)
repository = subprocess.check_output(
    ["git", "rev-parse", "--show-toplevel"], cwd=fixture_root, text=True
).strip()
provenance = json.loads((fixture_root / "provenance.json").read_text())
capture = fixture_root / provenance["capture_source"]
assert hashlib.sha256(capture.read_bytes()).hexdigest() == provenance["capture_sha256"]
commits = dict.fromkeys(item["source_commit"] for item in provenance["archives"])
for commit in commits:
    with tempfile.TemporaryDirectory(prefix="skill426-historical-") as temporary:
        checkout = Path(temporary) / "producer"
        subprocess.run(
            ["git", "worktree", "add", "--detach", str(checkout), commit],
            cwd=repository,
            check=True,
        )
        try:
            target = checkout / (
                "runtime-kotlin/runtime-engine/src/test/kotlin/skillbill/engine/"
                "featuretask/lifecycle/execution/HistoricalProducerCaptureTest.kt"
            )
            shutil.copyfile(capture, target)
            environment = os.environ.copy()
            environment["SKILL426_CAPTURE_DIR"] = str(output)
            subprocess.run(
                provenance["command"],
                cwd=checkout / "runtime-kotlin",
                env=environment,
                check=True,
            )
        finally:
            subprocess.run(
                ["git", "worktree", "remove", "--force", str(checkout)],
                cwd=repository,
                check=True,
            )
for archive in provenance["archives"]:
    revision = archive["audit_semantic_revision"]
    mode = archive["archive"].removesuffix(f"-r{revision}.json")
    captured = output / f"r{revision}" / f"{mode}.json"
    assert hashlib.sha256(captured.read_bytes()).hexdigest() == archive["descriptor_sha256"]
print("All nine raw historical producer descriptors match their recorded SHA-256 identities.")
