"""Puts the trimmed libsodium and BearSSL of the native key core into keycore/third_party.

Usage: python3 -I tools/vendor/keycore.py

Downloads the release archives into build/vendor, checks their sha256 (and libsodium's minisign signature, so
minisign must be installed), then copies only the C files keycore/CMakeLists.txt builds, the headers they include
on any of the target processors, and the licences. A new version means new pins here and a look at the diff.
"""
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tarfile
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
WORK = os.path.join(ROOT, "build", "vendor")
OUT = os.path.join(ROOT, "keycore", "third_party")

SODIUM = {
    "name": "libsodium",
    "version": "1.0.22",
    "url": "https://download.libsodium.org/libsodium/releases/libsodium-1.0.22.tar.gz",
    "sha256": "adbdd8f16149e81ac6078a03aca6fc03b592b89ef7b5ed83841c086191be3349",
    "minisign": "RWQf6LRCGA9i53mlYecO4IzT51TGPpvWucNSCh1CBM0QTaLn73Y7GFO3",
    "top": "libsodium-1.0.22",
    "var": "S",
    "base": "src/libsodium",
    "includes": ["src/libsodium/include", "src/libsodium/include/sodium", "src/libsodium/crypto_pwhash/argon2"],
    "licence": ["LICENSE"],
}
# No signed releases exist; the archive was checked to be the v0.6 tag of the project's git (only .gitignore differs).
BEARSSL = {
    "name": "bearssl",
    "version": "0.6",
    "url": "https://bearssl.org/bearssl-0.6.tar.gz",
    "sha256": "6705bba1714961b41a728dfc5debbe348d2966c117649392f8c8139efc83ff14",
    "minisign": None,
    "top": "bearssl-0.6",
    "var": "B",
    "base": "",
    "includes": ["inc", "src"],
    "licence": ["LICENSE.txt"],
}

COMMON = ["CONFIGURED=1", "SODIUM_STATIC=1", "NATIVE_LITTLE_ENDIAN=1", "HAVE_SYS_MMAN_H=1", "HAVE_MMAP=1",
          "HAVE_MPROTECT=1", "HAVE_MLOCK=1", "HAVE_MADVISE=1", "HAVE_POSIX_MEMALIGN=1", "HAVE_SYS_RANDOM_H=1",
          "HAVE_GETRANDOM=1", "HAVE_SYS_AUXV_H=1", "HAVE_GETAUXVAL=1", "HAVE_SYSCONF=1", "HAVE_INLINE_ASM=1",
          "HAVE_ATOMIC_OPS=1", "HAVE_GCC_MEMORY_FENCES=1"]
ARCHES = {
    "x86_64": ["HAVE_CPUID=1", "HAVE_EMMINTRIN_H=1", "HAVE_TMMINTRIN_H=1", "HAVE_WMMINTRIN_H=1", "BR_64=1",
               "BR_LE_UNALIGNED=1", "__x86_64__=1"],
    "arm64": ["HAVE_ARMCRYPTO=1", "HAVE_ARM_NEON=1", "BR_64=1", "__aarch64__=1", "__ARM_NEON=1"],
    "armv7": ["__arm__=1"],
}


def fetch(lib):
    os.makedirs(os.path.join(WORK, "dl"), exist_ok=True)
    path = os.path.join(WORK, "dl", os.path.basename(lib["url"]))
    if not os.path.exists(path):
        urllib.request.urlretrieve(lib["url"], path)
    digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
    if digest != lib["sha256"]:
        sys.exit(f"{lib['name']}: sha256 {digest} is not the pinned one")
    if lib["minisign"]:
        sig = path + ".minisig"
        if not os.path.exists(sig):
            urllib.request.urlretrieve(lib["url"] + ".minisig", sig)
        subprocess.run(["minisign", "-Vqm", path, "-x", sig, "-P", lib["minisign"]], check=True)
    tree = os.path.join(WORK, lib["top"])
    shutil.rmtree(tree, ignore_errors=True)
    with tarfile.open(path) as t:
        t.extractall(WORK, filter="data")
    return tree


def sources(lib):
    text = open(os.path.join(ROOT, "keycore", "CMakeLists.txt")).read()
    return sorted(set(re.findall(r"\$\{" + lib["var"] + r"\}/([\w./+-]+\.c)", text)))


def headers(lib, tree, files):
    found = set()
    incs = [f"-I{os.path.join(tree, i)}" for i in lib["includes"]]
    base = os.path.join(tree, lib["base"])
    for arch, defs in ARCHES.items():
        for f in files:
            src = os.path.join(base, f)
            out = subprocess.run(["cc", "-MM", "-MG", "-nostdinc", *incs, *[f"-D{d}" for d in COMMON + defs], src],
                                 capture_output=True, text=True, check=True).stdout
            for dep in out.replace("\\\n", " ").split(":", 1)[1].split():
                full = os.path.normpath(dep if os.path.isabs(dep) else os.path.join(ROOT, dep))
                if full.startswith(tree + os.sep) and full.endswith(".h"):
                    found.add(os.path.relpath(full, tree))
    return sorted(found)


def main():
    shutil.rmtree(OUT, ignore_errors=True)
    for lib in (SODIUM, BEARSSL):
        tree = fetch(lib)
        files = sources(lib)
        keep = [os.path.join(lib["base"], f) for f in files] + headers(lib, tree, files) + lib["licence"]
        dest = os.path.join(OUT, lib["name"])
        for rel in keep:
            os.makedirs(os.path.dirname(os.path.join(dest, rel)), exist_ok=True)
            shutil.copy2(os.path.join(tree, rel), os.path.join(dest, rel))
        with open(os.path.join(dest, "SOURCE"), "w") as s:
            s.write(f"{lib['name']} {lib['version']}\n{lib['url']}\nsha256 {lib['sha256']}\n")
            if lib["minisign"]:
                s.write(f"minisign {lib['minisign']}\n")
            s.write("\n" + "\n".join(sorted(keep)) + "\n")
        print(f"{lib['name']} {lib['version']}: {len(files)} C files, {len(keep) - len(files)} other")


if __name__ == "__main__":
    main()
