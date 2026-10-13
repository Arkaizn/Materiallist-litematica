#!/usr/bin/env python3
"""Downloads everything build.sh needs into a fake Prism layout, so the mod can be built in CI without a launcher.

Usage: ci-fetch.py <prism dir> <minecraft version>
Afterwards: PRISM=<prism dir> ./build.sh <prism dir>/instances/<minecraft version>
"""
import json
import os
import sys
import urllib.parse
import urllib.request

prism, mc = sys.argv[1], sys.argv[2]
UA = {"User-Agent": "Arkaizn/Materiallist-litematica CI build"}


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=120) as r:
        return r.read()


def save(url, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if not os.path.exists(path):
        with open(path, "wb") as f:
            f.write(get(url))
    print("  " + os.path.relpath(path, prism))


def modrinth(slug):
    """Newest Fabric version of a Modrinth project for this Minecraft version -> primary file URL"""
    q = urllib.parse.urlencode({"loaders": '["fabric"]', "game_versions": json.dumps([mc])})
    versions = json.loads(get(f"https://api.modrinth.com/v2/project/{slug}/version?{q}"))
    if not versions:
        sys.exit(f"No Fabric version of {slug} for Minecraft {mc} on Modrinth")
    v = versions[0]
    f = next((f for f in v["files"] if f.get("primary")), v["files"][0])
    print(f"{slug}: {v['version_number']}")
    return f["url"]


libs = os.path.join(prism, "libraries")
inst = os.path.join(prism, "instances", mc)
mods = os.path.join(inst, "minecraft", "mods")

# Minecraft client jar and its libraries
manifest = json.loads(get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
entry = next((v for v in manifest["versions"] if v["id"] == mc), None)
if entry is None:
    sys.exit(f"Minecraft {mc} not found in Mojang's version manifest")
meta = json.loads(get(entry["url"]))
print(f"Minecraft {mc}")
save(meta["downloads"]["client"]["url"], os.path.join(libs, "com", "mojang", "minecraft", mc, f"minecraft-{mc}-client.jar"))
for lib in meta["libraries"]:
    art = lib.get("downloads", {}).get("artifact")
    if art and "natives" not in art["path"]:
        save(art["url"], os.path.join(libs, art["path"]))

# Fabric Loader
loader = json.loads(get("https://meta.fabricmc.net/v2/versions/loader"))[0]["version"]
print(f"Fabric Loader {loader}")
save(f"https://maven.fabricmc.net/net/fabricmc/fabric-loader/{loader}/fabric-loader-{loader}.jar",
     os.path.join(libs, "net", "fabricmc", "fabric-loader", loader, f"fabric-loader-{loader}.jar"))

# Mods compiled against (names as build.sh expects them)
save(modrinth("fabric-api"), os.path.join(mods, "fabric-api-ci.jar"))
save(modrinth("litematica"), os.path.join(mods, "litematica-fabric-ci.jar"))
save(modrinth("malilib"), os.path.join(mods, "malilib-fabric-ci.jar"))

# build.sh reads the Minecraft version from the second "version" entry
os.makedirs(inst, exist_ok=True)
with open(os.path.join(inst, "mmc-pack.json"), "w") as f:
    f.write('{\n  "components": [\n    { "version": "ci" },\n    { "version": "%s" }\n  ]\n}\n' % mc)
