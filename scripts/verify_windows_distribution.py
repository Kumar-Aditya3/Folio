"""Check an assembled Windows image and optionally launch its native EXE.

Usage: python scripts/verify_windows_distribution.py path/to/Folio [--launch]
Uses only Python's standard library. Launch diagnostics go in a temporary folder.
"""
import argparse
import ctypes
import os
from pathlib import Path
import subprocess
import tempfile
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("image", type=Path)
parser.add_argument("--launch", action="store_true")
args = parser.parse_args()
root = args.image.resolve()
exe = root / "Folio.exe"
assert exe.is_file(), exe
assert (root / "runtime/bin/server/jvm.dll").is_file(), "Missing bundled JVM"
cfg = (root / "app/Folio.cfg").read_text()
assert "java-options=-Dskiko.renderApi=OPENGL" in cfg, "Missing Windows renderer startup fix"
for line in cfg.splitlines():
    if line.startswith("app.classpath=$APPDIR"):
        dependency = root / "app" / line.split("$APPDIR", 1)[1].lstrip("\\/")
        assert dependency.is_file(), f"Missing packaged dependency: {dependency}"

kernel = ctypes.WinDLL("kernel32", use_last_error=True)
kernel.LoadLibraryExW.argtypes = [ctypes.c_wchar_p, ctypes.c_void_p, ctypes.c_uint]
kernel.LoadLibraryExW.restype = ctypes.c_void_p
handle = kernel.LoadLibraryExW(str(exe), None, 0x2 | 0x20)
assert handle, ctypes.WinError(ctypes.get_last_error())
types = []
callback_type = ctypes.WINFUNCTYPE(ctypes.c_bool, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_ssize_t)
callback = callback_type(lambda module, resource_type, data: types.append(resource_type) is None)
kernel.EnumResourceTypesW.argtypes = [ctypes.c_void_p, callback_type, ctypes.c_ssize_t]
kernel.FreeLibrary.argtypes = [ctypes.c_void_p]
try:
    assert kernel.EnumResourceTypesW(handle, callback, 0)
    assert 3 in types and 14 in types, "EXE has no embedded Windows icon"
finally:
    kernel.FreeLibrary(handle)
print("PASS: bundled runtime, launcher dependencies and embedded EXE icon")

if args.launch:
    with tempfile.TemporaryDirectory(prefix="folio-launch-") as directory:
        environment = dict(os.environ, LOCALAPPDATA=directory)
        # An unrelated working directory catches accidental relative resource paths.
        process = subprocess.Popen([str(exe)], cwd=directory, env=environment)
        log = Path(directory) / "Folio/folio.log"
        try:
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                output = log.read_text(encoding="utf-8", errors="replace") if log.exists() else ""
                if "Folio window ready" in output:
                    assert "Uncaught failure" not in output, output
                    print("PASS: native EXE rendered its first frame from an unrelated working directory")
                    break
                if process.poll() is not None and process.returncode != 0:
                    raise RuntimeError(f"Launcher exited {process.returncode}\n{output}")
                time.sleep(.5)
            else:
                raise RuntimeError(f"No first frame within 60 seconds\n{output}")
        finally:
            subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            process.wait(timeout=10)
            # Windows may take a moment to release the log after process termination.
            time.sleep(1)
