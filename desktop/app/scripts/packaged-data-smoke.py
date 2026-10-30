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
image = project / "build/compose/binaries/main/app"
classes = project / "build/classes/kotlin/jvm/test"
runtime_install = Path(sys.argv[1]) if len(sys.argv) > 1 else None
if not image.is_dir() or not classes.is_dir() or runtime_install is None or not runtime_install.is_dir():
    raise SystemExit("Run `./gradlew packagedDataSmoke` (it builds the distribution, runtime install image and smoke classes first).")

for path in image.rglob("*.jar"):
    if path.parent.name == "app":
        with zipfile.ZipFile(path) as archive:
            if any(name.startswith("skillbill/") and name.endswith(".class") for name in archive.namelist()):
                raise SystemExit(f"The desktop image contains Skill Bill runtime classes: {path}")
required_library = None
if sys.platform == "darwin":
    required_library = "macos/libskillbilltray.dylib"
elif sys.platform.startswith("linux"):
    required_library = "composetray/native/linux-*/libLinuxTray.so"
if required_library:
    found_library = False
    for path in image.rglob("*.jar"):
        if path.parent.name == "app":
            with zipfile.ZipFile(path) as archive:
                names = archive.namelist()
                found_library |= any(
                    name == required_library
                    if "*" not in required_library
                    else name.startswith("composetray/native/linux-") and name.endswith("/libLinuxTray.so")
                    for name in names
                )
    if not found_library:
        raise SystemExit(f"The packaged app is missing {required_library}.")

runtime_identity = runtime_install / "build-identity.properties"
desktop_identities = list(image.rglob("build-identity.properties"))
if not runtime_identity.is_file() or len(desktop_identities) != 1:
    raise SystemExit("Expected one desktop build identity and the runtime build identity before packaged smoke.")
runtime_record = runtime_identity.read_text()
desktop_record = desktop_identities[0].read_text()
if ("dirty=true" in runtime_record or "dirty=true" in desktop_record) and not os.environ.get("SKILL_BILL_INSTALL_RUN_ID"):
    raise SystemExit("Dirty runtime and desktop builds need the same SKILL_BILL_INSTALL_RUN_ID.")
if runtime_record != desktop_record:
    raise SystemExit("The packaged runtime and desktop build identities do not match.")

with tempfile.TemporaryDirectory(prefix="skillbill-packaged-smoke-") as directory:
    root = Path(directory)
    packaged = root / "image"
    shutil.copytree(image, packaged)
    home = root / "home"
    home.mkdir()
    runtime_root = home / ".skill-bill/runtime"
    runtime_root.mkdir(parents=True)
    shutil.copytree(runtime_install, runtime_root / "runtime-cli")
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
    content = content.replace("[JavaOptions]", "app.classpath=$APPDIR/packaged-data-smoke.jar\n\n[JavaOptions]")
    content += (
        f"\njava-options=-Dskillbill.desktop.mainClass=dev.skillbill.desktop.app.smoke.PackagedDataSmoke"
        f"\njava-options=-Duser.home={home}"
        f"\njava-options=-Djava.util.prefs.userRoot={home / 'preferences'}"
        f"\njava-options=-Djava.io.tmpdir={home / 'tmp'}\n"
    )
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
    environment.pop("SKILL_BILL_RUNTIME_DIR", None)
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
    if "Packaged runtime session, board, detail and settings passed without CLI discovery." not in result.stdout:
        raise SystemExit("The launcher did not report the packaged data result.")
