"""Extract a native installer on its build host and verify Sona's bundled license."""

import os
from pathlib import Path
import subprocess
import sys
import tempfile


def verify_license(root, expected):
    licenses = [p for p in root.rglob("LICENSE")
                if p.as_posix().endswith("/app/resources/LICENSE")]
    if len(licenses) != 1:
        raise ValueError(f"Expected one packaged app/resources/LICENSE, found {len(licenses)}")
    if licenses[0].read_bytes() != expected:
        raise ValueError("Packaged LICENSE bytes differ from root LICENSE")


def main():
    license_path, artifact_path = map(Path, sys.argv[1:])
    artifact = artifact_path.resolve()
    expected = license_path.read_bytes()
    with tempfile.TemporaryDirectory(prefix="sona-license-") as directory:
        root = Path(directory)
        if artifact.suffix == ".dmg":
            mount = root / "mount"
            subprocess.run([
                "hdiutil", "attach", "-readonly", "-nobrowse", "-quiet",
                "-mountpoint", str(mount), str(artifact),
            ], check=True)
            try:
                verify_license(mount, expected)
            finally:
                subprocess.run(["hdiutil", "detach", str(mount)], check=True)
        elif artifact.suffix == ".deb":
            subprocess.run(["dpkg-deb", "-x", str(artifact), str(root)], check=True)
            verify_license(root, expected)
        elif artifact.suffix == ".msi":
            env = dict(os.environ, SONA_MSI=str(artifact), SONA_EXTRACT=str(root))
            subprocess.run([
                "powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                "$arguments = '/a \"{0}\" /qn TARGETDIR=\"{1}\"' -f $env:SONA_MSI, $env:SONA_EXTRACT; "
                '$p = Start-Process msiexec.exe -ArgumentList $arguments -Wait -PassThru; '
                'if ($p.ExitCode -ne 0) { exit $p.ExitCode }',
            ], env=env, check=True)
            verify_license(root, expected)
        else:
            raise ValueError(f"Unsupported native installer: {artifact}")


if __name__ == "__main__":
    main()
