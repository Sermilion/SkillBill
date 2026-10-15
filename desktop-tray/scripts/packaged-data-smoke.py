#!/usr/bin/env python3
"""Load work data through the distribution's launcher, JDK and libraries."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

project = Path(__file__).resolve().parents[1]
image = project / "composeApp/build/compose/binaries/main/app"
classes = project / "core/data/build/classes/kotlin/jvm/test"
if not image.is_dir() or not classes.is_dir():
    raise SystemExit("Run `./gradlew packagedDataSmoke` (it builds the distribution and the smoke classes first).")

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
    smoke_jar = cfg.parent / "packaged-data-smoke.jar"
    with zipfile.ZipFile(smoke_jar, "w") as archive:
        for class_file in classes.rglob("*.class"):
            archive.write(class_file, class_file.relative_to(classes))
    content = cfg.read_text()
    content = content.replace("app.mainclass=dev.skillbill.tray.composition.MainKt",
                              "app.mainclass=dev.skillbill.tray.core.data.PackagedDataSmoke")
    content = content.replace("[JavaOptions]", "app.classpath=$APPDIR/packaged-data-smoke.jar\n\n[JavaOptions]")
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
    environment.pop("_JPACKAGE_LAUNCHER", None)
    try:
        result = subprocess.run([str(launcher), str(home)], cwd=home, env=environment,
                                capture_output=True, text=True, timeout=180)
    except subprocess.TimeoutExpired as timeout:
        for stream in (timeout.stdout, timeout.stderr):
            print(stream.decode() if isinstance(stream, bytes) else stream or "", file=sys.stderr, end="")
        raise SystemExit("The packaged launcher did not finish within 180 seconds; its captured output is above.")
    print(result.stdout, end="")
    if result.returncode:
        print(result.stderr, file=sys.stderr, end="")
        raise SystemExit(result.returncode)
    if "Packaged Room, SQLite runtime projection, detail and DataStore passed" not in result.stdout:
        raise SystemExit("The launcher did not report the packaged data result.")
