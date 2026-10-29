"""Reproducible Android CPU library build. Requires Rust and Android NDK r27+.

The 65 MB voice pack is deliberately not fetched by this script or Gradle.
Output: androidApp/src/main/jniLibs/<abi>/libptts_ffi.so.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import zipfile

REVISION = "0418e21963f646e66f5b27d6c1aa5740524ec545"
ROOT = Path(__file__).resolve().parents[1]

def main():
    args = argparse.ArgumentParser()
    args.add_argument("--ndk", required=True)
    args.add_argument("--abi", choices=["arm64-v8a", "x86_64"], default="arm64-v8a")
    args.add_argument("--optimized", action="store_true", help="Separate ARMv8.2 fp16/dotprod library; requires runtime capability gating")
    args.add_argument("--jobs", type=int, default=2)
    opts = args.parse_args()
    if opts.optimized and opts.abi != "arm64-v8a":
        args.error("--optimized requires arm64-v8a")
    scratch = ROOT / ".build-out" / "phonon-native"
    scratch.mkdir(parents=True, exist_ok=True)
    source = scratch / ("xn-ptts-" + REVISION)
    if not source.exists():
        archive = scratch / "source.zip"
        urllib.request.urlretrieve("https://codeload.github.com/gradium-ai/xn-ptts/zip/" + REVISION, archive)
        with zipfile.ZipFile(archive) as bundle:
            for entry in bundle.infolist():
                destination = (scratch / entry.filename).resolve()
                if not destination.is_relative_to(scratch.resolve()):
                    raise RuntimeError("Invalid upstream archive path")
            bundle.extractall(scratch)
    env = os.environ.copy()
    local_tools = ROOT / ".build-out" / "phonon-toolchain"
    if (local_tools / "cargo" / "bin").exists():
        env["CARGO_HOME"] = str(local_tools / "cargo")
        env["RUSTUP_HOME"] = str(local_tools / "rustup")
        env["PATH"] = str(local_tools / "cargo" / "bin") + os.pathsep + env["PATH"]
    target = "aarch64-linux-android" if opts.abi == "arm64-v8a" else "x86_64-linux-android"
    host = "windows-x86_64" if os.name == "nt" else "linux-x86_64"
    llvm = Path(opts.ndk) / "toolchains" / "llvm" / "prebuilt" / host / "bin"
    suffix = ".cmd" if os.name == "nt" else ""
    linker = llvm / (target + "24-clang" + suffix)
    env["CARGO_TARGET_" + target.upper().replace("-", "_") + "_LINKER"] = str(linker)
    env["CARGO_TARGET_" + target.upper().replace("-", "_") + "_RUSTFLAGS"] = "-C target-cpu=generic -C strip=symbols -C link-arg=-Wl,-z,max-page-size=16384"
    if opts.optimized:
        env["CARGO_TARGET_" + target.upper().replace("-", "_") + "_RUSTFLAGS"] += " -C target-feature=+dotprod,+fp16"
        env["CARGO_TARGET_DIR"] = str(scratch / "optimized-target")
    env.pop("RUSTFLAGS", None)
    env.pop("CARGO_ENCODED_RUSTFLAGS", None)
    env["CC_" + target.replace("-", "_")] = str(linker)
    env["AR_" + target.replace("-", "_")] = str(llvm / ("llvm-ar.exe" if os.name == "nt" else "llvm-ar"))
    # Avoid many compiler processes on memory-constrained developer machines.
    env["CARGO_BUILD_JOBS"] = str(opts.jobs)
    subprocess.run([shutil.which("rustup", path=env["PATH"]), "target", "add", target], env=env, check=True)
    subprocess.run([shutil.which("cargo", path=env["PATH"]), "build", "--locked", "--release", "--target", target, "-p", "ptts-ffi"], cwd=source, env=env, check=True)
    destination = ROOT / "androidApp" / "src" / "main" / "jniLibs" / opts.abi
    destination.mkdir(parents=True, exist_ok=True)
    built = Path(env.get("CARGO_TARGET_DIR", source / "target")) / target / "release" / "libptts_ffi.so"
    output = destination / ("libptts_ffi_optimized.so" if opts.optimized else "libptts_ffi.so")
    shutil.copy2(built, output)
    print("Built", output)

if __name__ == "__main__":
    main()
