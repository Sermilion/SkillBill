#!/usr/bin/env python3
"""Load work data through the distribution's launcher, JDK and libraries."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

project = Path(__file__).resolve().parents[1]
image = project / "composeApp/build/compose/binaries/main/app"
classes = project / "composeApp/build/classes/kotlin/jvm/test"
if not image.is_dir() or not classes.is_dir():
    raise SystemExit("Run clean check createDistributable before the packaged data smoke.")

with tempfile.TemporaryDirectory(prefix="skillbill-packaged-smoke-") as directory:
    root = Path(directory)
    packaged = root / "image"
    shutil.copytree(image, packaged)
    home = root / "home"
    home.mkdir()
    empty_path = root / "empty-path"
    empty_path.mkdir()
    (home / "tmp").mkdir()
    cfgs = list(packaged.rglob("Skill Bill.cfg"))
    if len(cfgs) != 1:
        raise SystemExit(f"Expected one launcher configuration, found {len(cfgs)}.")
    cfg = cfgs[0]
    content = cfg.read_text()
    content = content.replace("app.mainclass=dev.skillbill.tray.composition.MainKt",
                              "app.mainclass=dev.skillbill.tray.infrastructure.data.PackagedDataSmoke")
    content = content.replace("[JavaOptions]", f"app.classpath={classes}\n\n[JavaOptions]")
    content += f"\njava-options=-Duser.home={home}\njava-options=-Djava.util.prefs.userRoot={home / 'preferences'}\njava-options=-Djava.io.tmpdir={home / 'tmp'}\n"
    cfg.write_text(content)
    if sys.platform == "darwin":
        launcher = packaged / "Skill Bill.app/Contents/MacOS/Skill Bill"
    elif sys.platform == "win32":
        launcher = packaged / "Skill Bill/Skill Bill.exe"
    else:
        launcher = packaged / "Skill Bill/bin/Skill Bill"
    environment = os.environ.copy()
    environment.update(PATH=str(empty_path), HOME=str(home), USERPROFILE=str(home))
    environment.pop("CLASSPATH", None)
    result = subprocess.run([str(launcher), str(home)], cwd=home, env=environment,
                            capture_output=True, text=True, timeout=30)
    print(result.stdout, end="")
    if result.returncode:
        print(result.stderr, file=sys.stderr, end="")
        raise SystemExit(result.returncode)
    if "Packaged Room, SQLite runtime projection, detail and DataStore passed" not in result.stdout:
        raise SystemExit("The launcher did not report the packaged data result.")
