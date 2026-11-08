"""Prepare an engine release: adapter package, signed engines.json with Mozilla's per-ABI AARs pinned by sha256.

Usage: python3 -I tools/publish/publish_engine.py [--gecko VERSION] [--out DIR] [--adapter-base URL] [--shell]

Run from the repository root. Builds :engine-gecko:adapterPackage for the GeckoView version, downloads the
per-ABI AARs from maven.mozilla.org (cached in build/publish), compares their manifest with the one the shell
was built with, writes engines.json and engines.json.sig to --out (default: repository root) and prints what to
upload. With --shell it also builds the signed release shell (versionCode from shellVersionCode) and offers it in
the index; without it the shell entry of the existing index is kept. Nothing is uploaded or pushed.
"""
import argparse
import glob
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
import zipfile

ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
SHELL_APK = "app/build/outputs/apk/release/app-release.apk"
MAVEN = "https://maven.mozilla.org/maven2/org/mozilla/geckoview"
KEYS = os.path.expanduser("~/.antite-keys")
CACHE = "build/publish"


def prop(name, path="gradle.properties"):
    for line in open(path):
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip()
    raise KeyError(name)


def shell_version_code():
    return int(prop("shellVersionCode"))


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch(url, to):
    if os.path.exists(to):
        return to
    print("download", url)
    tmp = to + ".part"
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Antite publish"})) as r, open(tmp, "wb") as f:
        while chunk := r.read(1 << 20):
            f.write(chunk)
    sha1 = urllib.request.urlopen(url + ".sha1").read().decode().split()[0]
    if hashlib.sha1(open(tmp, "rb").read()).hexdigest() != sha1:
        os.remove(tmp)
        sys.exit(f"{url}: sha1 does not match Mozilla's .sha1")
    os.rename(tmp, to)
    return to


def components(aar):
    """Names of everything an app manifest must declare for this GeckoView."""
    text = zipfile.ZipFile(aar).read("AndroidManifest.xml").decode()
    out = set()
    for tag, body in re.findall(r"<(uses-permission|service|provider|activity|receiver)\b([^>]*)>", text):
        m = re.search(r'android:name="([^"]+)"', body)
        if m:
            out.add(f"{tag} {m.group(1)}")
    return out


def shell_aar():
    version = prop("geckoviewVersion")
    found = glob.glob(os.path.expanduser(
        f"~/.gradle/caches/modules-2/files-2.1/org.mozilla.geckoview/geckoview/{version}/*/geckoview-{version}.aar"))
    if not found:
        sys.exit(f"GeckoView {version} AAR is not in the Gradle cache; build the app first")
    return found[0]


def artifact(urls, path):
    return {"url": urls, "size": os.path.getsize(path), "sha256": sha256(path)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gecko", default=None, help="GeckoView version, default: geckoviewVersion")
    ap.add_argument("--out", default=".")
    ap.add_argument("--adapter-base", default=None, help="base URL of the adapter download, default: GitHub release")
    ap.add_argument("--shell", action="store_true", help="also publish the release shell")
    ap.add_argument("--shell-base", default=None, help="base URL of the shell download, default: GitHub release")
    a = ap.parse_args()

    gecko = a.gecko or prop("geckoviewVersion")
    revision = int(prop("adapterRevision"))
    version = gecko if revision == 0 else f"{gecko}.{revision}"
    api = int(prop("engineApi"))
    tag = f"engine-{version}"
    base = a.adapter_base or f"{prop('engineReleaseBase')}/{tag}"
    os.makedirs(CACHE, exist_ok=True)
    index_path = os.path.join(a.out, "engines.json")
    old = json.load(open(index_path)) if os.path.exists(index_path) else {"serial": 0, "engines": []}
    previous = next((e for e in old["engines"] if e["id"] == "gecko-release"), None)

    # A published engine version is never rebuilt: its files are pinned by hash. Adapter changes need a new
    # adapterRevision, which phones see as a newer engine.
    adapter = None
    if previous and previous["version"] == version and previous["api"] == api:
        print(f"1. engine {version} is already published, its adapter is kept")
        adapter_entry = previous["adapter"]
    else:
        print(f"1. adapter for engine {version}")
        subprocess.run(["./gradlew", "-q", ":engine-gecko:adapterPackage", f"-PgeckoviewVersion={gecko}"], check=True)
        adapter = f"engine-gecko/build/outputs/engine/gecko-{version}-adapter.zip"
        manifest = json.loads(zipfile.ZipFile(adapter).read("engine.json"))
        if manifest["version"] != version or manifest["api"] != api:
            sys.exit(f"adapter manifest does not match: {manifest}")
        adapter_entry = artifact([f"{base}/{os.path.basename(adapter)}"], adapter)

    print("2. Mozilla's AARs")
    upstream = {}
    aars = {}
    for abi in ABIS:
        url = f"{MAVEN}/geckoview-{abi}/{gecko}/geckoview-{abi}-{gecko}.aar"
        aars[abi] = fetch(url, os.path.join(CACHE, f"geckoview-{abi}-{gecko}.aar"))
        names = zipfile.ZipFile(aars[abi]).namelist()
        if f"jni/{abi}/libxul.so" not in names or "assets/omni.ja" not in names:
            sys.exit(f"{url} has no libxul.so or omni.ja for {abi}")
        upstream[abi] = artifact([url], aars[abi])

    print("3. manifest against the shell")
    # Shells that already run this engine API keep getting engines; only new Android components raise the bar.
    min_shell = previous["minShell"] if previous and previous["api"] == api else shell_version_code()
    declared = components(shell_aar())
    for abi in ABIS:
        extra = components(aars[abi]) - declared
        if extra:
            min_shell = shell_version_code() + 1
            print(f"   {abi} needs a new shell (versionCode {min_shell}), not declared in it:")
            for e in sorted(extra):
                print("     ", e)
    print(f"   minShell {min_shell}")

    print("4. index")
    entry = {
        "id": "gecko-release", "type": "gecko", "api": api, "channel": "release", "version": version,
        "minShell": min_shell,
        "adapter": adapter_entry,
        "upstream": upstream,
    }
    shell = old.get("shell")
    if a.shell:
        code = shell_version_code()
        if code <= (old.get("shell") or {}).get("version", 0):
            sys.exit("shellVersionCode must grow")
        print(f"   release shell, versionCode {code}")
        subprocess.run(["./gradlew", "-q", ":app:assembleRelease"], check=True)
        shell_base = a.shell_base or f"{prop('engineReleaseBase')}/shell-{code}"
        # One universal APK; the index keeps a per-ABI map so every shell finds its entry.
        apk = artifact([f"{shell_base}/antite-{code}.apk"], SHELL_APK)
        shell = {"version": code, "apk": {abi: apk for abi in ABIS}}
    index = {
        "format": 1,
        "serial": max(old["serial"] + 1, int(time.time())),
        "published": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "engines": [entry] + [e for e in old["engines"] if e["id"] != entry["id"]],
    }
    if shell:
        index["shell"] = shell
    body = (json.dumps(index, indent=2, ensure_ascii=True) + "\n").encode()
    os.makedirs(a.out, exist_ok=True)
    open(index_path, "wb").write(body)
    sig = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", f"{KEYS}/publisher.key.pem", "-passin", f"file:{KEYS}/password"],
        input=body, capture_output=True, check=True).stdout
    open(index_path + ".sig", "wb").write(sig)
    check = subprocess.run(["openssl", "dgst", "-sha256", "-verify", "keys/publisher.pub.pem", "-signature",
                            index_path + ".sig", index_path], capture_output=True, text=True)
    if "Verified OK" not in check.stdout:
        sys.exit("signature check failed: " + check.stdout + check.stderr)
    print(f"   {index_path} serial {index['serial']}, signature verified with keys/publisher.pub.pem")

    print("\nUpload:")
    if adapter:
        print(f"  {adapter} -> release {tag} ({entry['adapter']['url'][0]})")
    if a.shell:
        print(f"  {SHELL_APK} -> {shell['apk'][ABIS[0]]['url'][0]}")
    print(f"  {index_path} and {index_path}.sig -> repository root ({prop('engineIndexUrl')})")


if __name__ == "__main__":
    main()
