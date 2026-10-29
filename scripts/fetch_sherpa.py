"""Vendor a pinned Sherpa Android runtime, sharing Folio's ONNX Runtime 1.23.2.

Only runtime code is bundled. Voice weights are downloaded explicitly inside Folio.
The upstream ORT copy is removed, never resolved by an arbitrary pickFirst rule.
"""
from pathlib import Path
import hashlib
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = "1.12.30"
SHA256 = "0a44f3aa516c31eb74cef0ea6bbacf76095434bf6c89be728226c7ecf550232f"

def main():
    scratch = ROOT / ".build-out" / "kokoro"
    scratch.mkdir(parents=True, exist_ok=True)
    archive = scratch / f"sherpa-{VERSION}.aar"
    if not archive.exists():
        urllib.request.urlretrieve(
            f"https://github.com/k2-fsa/sherpa-onnx/releases/download/v{VERSION}/sherpa-onnx-{VERSION}.aar", archive)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
        raise RuntimeError("Sherpa runtime verification failed")
    destination = ROOT / "androidApp" / "libs" / f"sherpa-onnx-{VERSION}-shared-ort.aar"
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as source, zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as output:
        for item in source.infolist():
            if item.filename.startswith("jni/"):
                # JNI is self-contained apart from ORT/system libs (verified DT_NEEDED).
                # Folio's neural narration supports 64-bit devices only. The separate
                # C/C++ APIs and 32-bit speech runtimes are never loaded by the app.
                if item.filename not in ("jni/arm64-v8a/libsherpa-onnx-jni.so", "jni/x86_64/libsherpa-onnx-jni.so"):
                    continue
            output.writestr(item, source.read(item))
    print(destination)

if __name__ == "__main__":
    main()
