"""Publish verified desktop installers to fixed latest object keys."""

import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path


SUFFIXES = (
    "macos-arm64.dmg",
    "linux-x64.deb",
    "windows-x64.msi",
)
LATEST_CACHE = "public, max-age=300, must-revalidate"
TIMEOUT_SECONDS = 300


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def version_tuple(version):
    if not re.fullmatch(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", version):
        raise ValueError(f"release version must be final numeric semver: {version!r}")
    return tuple(int(part) for part in version.split("."))


def artifact_names(version):
    version_tuple(version)
    return tuple(f"sona-{version}-{suffix}" for suffix in SUFFIXES)


def validate_assets(version, assets_dir):
    installers = artifact_names(version)
    expected = {name for installer in installers for name in (installer, installer + ".sha256")}
    entries = list(assets_dir.iterdir())
    actual = {path.name for path in entries if path.is_file()}
    if actual != expected or any(not path.is_file() or path.is_symlink() for path in entries):
        raise ValueError(f"expected exactly six regular release assets; missing={sorted(expected-actual)}, unexpected={sorted(actual-expected)}")
    verified = {}
    for name in installers:
        installer = assets_dir / name
        sidecar = assets_dir / (name + ".sha256")
        if not installer.is_file() or installer.stat().st_size == 0:
            raise ValueError(f"installer is missing or empty: {name}")
        fields = sidecar.read_text(encoding="ascii").strip().split()
        if len(fields) != 2 or not re.fullmatch(r"[0-9a-fA-F]{64}", fields[0]) or fields[1] not in (name, "*" + name):
            raise ValueError(f"invalid GNU sha256 sidecar for {name}")
        digest = sha256(installer)
        if fields[0].lower() != digest:
            raise ValueError(f"checksum mismatch for {name}")
        verified[name] = digest
    return verified


def stable_name(versioned_name):
    suffix = versioned_name.split("-", 2)[2]
    return f"sona-latest-{suffix}"


def content_type(name):
    if name.endswith(".dmg"):
        return "application/x-apple-diskimage"
    if name.endswith(".msi"):
        return "application/x-msi"
    if name.endswith(".deb"):
        return "application/vnd.debian.binary-package"
    if name.endswith(".sha256") or name.endswith(".json"):
        return "text/plain; charset=utf-8" if name.endswith(".sha256") else "application/json"
    raise ValueError(f"unsupported object type: {name}")


class AwsStorage:
    """Small AWS CLI boundary. Credentials come only from the process environment."""

    def __init__(self, endpoint_url, bucket):
        self.endpoint_url = endpoint_url
        self.bucket = bucket
        self.env = dict(
            os.environ, AWS_DEFAULT_REGION="hel1", AWS_RETRY_MODE="standard", AWS_MAX_ATTEMPTS="4",
            AWS_REQUEST_CHECKSUM_CALCULATION="when_required", AWS_RESPONSE_CHECKSUM_VALIDATION="when_required",
        )

    def run(self, *args, missing_ok=False, service="s3api"):
        result = subprocess.run(
            ["aws", service, *args, "--endpoint-url", self.endpoint_url],
            check=False, capture_output=True, text=True, timeout=TIMEOUT_SECONDS, env=self.env,
        )
        if result.returncode:
            if missing_ok and "404" in result.stderr and "Not Found" in result.stderr:
                return None
            raise RuntimeError(f"AWS CLI request failed ({result.returncode}): {result.stderr.strip()}")
        return result.stdout

    def head(self, key):
        output = self.run("head-object", "--bucket", self.bucket, "--key", key, missing_ok=True)
        return json.loads(output) if output is not None else None

    def download(self, key, target):
        self.run("get-object", "--bucket", self.bucket, "--key", key, str(target))

    def upload(self, path, key, cache, release, digest, filename, kind):
        self.run(
            "cp", str(path), f"s3://{self.bucket}/{key}", "--cache-control", cache,
            "--content-type", kind, "--content-disposition", f'attachment; filename="{filename}"',
            "--metadata", f"release-version={release},sha256={digest}",
            service="s3",
        )


def object_digest(storage, key, head):
    with tempfile.TemporaryDirectory(prefix="desktop-download-verify-") as directory:
        target = Path(directory) / "object"
        storage.download(key, target)
        digest = sha256(target)
        if target.stat().st_size != head.get("ContentLength"):
            raise ValueError(f"remote object length mismatch: {key}")
        return digest


def verify_object(storage, key, path, cache, release, digest, filename, kind):
    head = storage.head(key)
    if head is None:
        return False
    metadata = {key.lower(): value for key, value in head.get("Metadata", {}).items()}
    expected = (
        head.get("ContentLength") == path.stat().st_size
        and head.get("CacheControl") == cache
        and head.get("ContentType") == kind
        and head.get("ContentDisposition") == f'attachment; filename="{filename}"'
        and metadata.get("release-version") == release
        and metadata.get("sha256") == digest
    )
    remote_digest = object_digest(storage, key, head)
    if remote_digest != digest:
        return False
    if not expected:
        return False
    return True


def put_or_verify(storage, key, path, cache, release, digest, filename):
    kind = content_type(filename)
    if verify_object(storage, key, path, cache, release, digest, filename, kind):
        return
    storage.upload(path, key, cache, release, digest, filename, kind)
    if not verify_object(storage, key, path, cache, release, digest, filename, kind):
        raise ValueError(f"uploaded object failed remote verification: {key}")


def read_state(storage, key):
    head = storage.head(key)
    if head is None:
        return None
    with tempfile.TemporaryDirectory(prefix="desktop-download-state-") as directory:
        target = Path(directory) / "state.json"
        storage.download(key, target)
        state_hash = sha256(target)
        state_length = target.stat().st_size
        try:
            state = json.loads(target.read_text(encoding="utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise ValueError(f"malformed state object {key}: {error}") from error
    if not isinstance(state, dict) or state.get("schema") != 1:
        raise ValueError(f"unsupported state schema in {key}")
    version_tuple(state.get("release_version", ""))
    version_tuple(state.get("ordering_floor", state["release_version"]))
    installers = state.get("installers")
    if (not isinstance(installers, dict) or set(installers) != set(artifact_names(state["release_version"]))
            or any(not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value)
                   for value in installers.values())):
        raise ValueError(f"invalid installer hashes in {key}")
    metadata = {name.lower(): value for name, value in head.get("Metadata", {}).items()}
    if (
        head.get("CacheControl") != LATEST_CACHE
        or head.get("ContentType") != "application/json"
        or head.get("ContentDisposition") != f'attachment; filename="{Path(key).name}"'
        or head.get("ContentLength") != state_length
        or metadata.get("release-version") != state["release_version"]
        or metadata.get("sha256") != state_hash
    ):
        raise ValueError(f"state object metadata or integrity is invalid: {key}")
    return state


def state_file(value):
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", delete=False) as handle:
        json.dump(value, handle, sort_keys=True)
        handle.write("\n")
        return Path(handle.name)


def publish(storage, version, assets_dir):
    hashes = validate_assets(version, assets_dir)
    installers = artifact_names(version)
    assets = {
        filename: assets_dir / filename
        for name in installers for filename in (name, name + ".sha256")
    }
    file_hashes = hashes | {name + ".sha256": sha256(assets[name + ".sha256"]) for name in installers}
    completed = read_state(storage, "latest/release.json")
    pending = read_state(storage, "latest/promotion.json")
    stable_names = {name: stable_name(name) for name in installers}
    if completed is None and pending is None:
        existing = [storage.head(f"latest/{stable}") for stable in stable_names.values()]
        existing.extend(storage.head(f"latest/{stable}.sha256") for stable in stable_names.values())
        if any(item is not None for item in existing):
            raise ValueError("stable objects exist without state records; perform deliberate recovery before publishing")

    floors = [version_tuple(state.get("ordering_floor", state["release_version"])) for state in (completed, pending) if state]
    versions = [version_tuple(state["release_version"]) for state in (completed, pending) if state]
    floor = max(floors + versions + [version_tuple(version)])
    superseded = version_tuple(version) < floor

    if superseded:
        return "superseded"
    for previous in (completed, pending):
        if previous and previous["release_version"] == version and previous["installers"] != hashes:
            raise ValueError("release version already has different installer bytes; use a new version")
    state = {"schema": 1, "release_version": version, "ordering_floor": ".".join(map(str, floor)),
             "installers": hashes}
    intent = state_file(state)
    try:
        put_or_verify(storage, "latest/promotion.json", intent, LATEST_CACHE, version,
                      sha256(intent), "promotion.json")
    finally:
        intent.unlink(missing_ok=True)

    for name in installers:
        path = assets[name]
        digest = file_hashes[name]
        stable = stable_names[name]
        put_or_verify(storage, f"latest/{stable}", path, LATEST_CACHE, version, digest, stable)
        with tempfile.TemporaryDirectory(prefix="desktop-sha256-") as directory:
            sidecar = Path(directory) / (stable + ".sha256")
            sidecar.write_text(f"{digest}  {stable}\n", encoding="ascii")
            put_or_verify(storage, f"latest/{stable}.sha256", sidecar, LATEST_CACHE,
                          version, sha256(sidecar), stable + ".sha256")

    record = state_file(state)
    try:
        put_or_verify(storage, "latest/release.json", record, LATEST_CACHE, version,
                      sha256(record), "release.json")
    finally:
        record.unlink(missing_ok=True)
    storage.run("delete-object", "--bucket", storage.bucket, "--key", "latest/promotion.json")
    return "published"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release-version", required=True)
    parser.add_argument("--assets-dir", required=True, type=Path)
    parser.add_argument("--endpoint-url", default=os.environ.get("DESKTOP_DOWNLOADS_S3_ENDPOINT", "https://hel1.your-objectstorage.com"))
    parser.add_argument("--bucket", default=os.environ.get("DESKTOP_DOWNLOADS_BUCKET", "sonadesktop"))
    parser.add_argument("--public-base-url", default=os.environ.get("DESKTOP_DOWNLOADS_PUBLIC_BASE_URL", "https://sonadesktop.hel1.your-objectstorage.com"))
    args = parser.parse_args()
    if (
        not re.fullmatch(r"https://[A-Za-z0-9.-]+", args.endpoint_url)
        or not re.fullmatch(r"[A-Za-z0-9.-]+", args.bucket)
        or not re.fullmatch(r"https://[A-Za-z0-9.-]+", args.public_base_url)
    ):
        parser.error("endpoint and public base must be HTTPS origins and bucket must be a simple name")
    version_tuple(args.release_version)
    if not os.environ.get("AWS_ACCESS_KEY_ID") or not os.environ.get("AWS_SECRET_ACCESS_KEY"):
        parser.error("AWS_ACCESS_KEY_ID and AWS_SECRET_ACCESS_KEY environment credentials are required")
    result = publish(AwsStorage(args.endpoint_url, args.bucket), args.release_version, args.assets_dir)
    print(f"Desktop downloads {result} for {args.release_version}")
    if result == "published":
        for name in artifact_names(args.release_version):
            print(f"{args.public_base_url}/latest/{stable_name(name)}")


if __name__ == "__main__":
    main()
