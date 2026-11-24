#!/usr/bin/env python3
"""CI : versions Modrinth des mods du serveur de test (Fabric 1.21.1), dependances requises de Mega Showdown comprises."""
import json, os, sys, urllib.request

OUT = sys.argv[1] if len(sys.argv) > 1 else "out/discover"
os.makedirs(OUT, exist_ok=True)
UA = {"User-Agent": "cobblepulse-ranch-ci/1.0 (github actions)"}


def get(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=300) as r:
        return r.read()


def versions(slug):
    return json.loads(get(f"https://api.modrinth.com/v2/project/{slug}/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%221.21.1%22%5D"))


modrinth, lines = {}, []
for slug in ["cobblemon", "fabric-api", "fabric-language-kotlin", "luckperms", "cobblemon-mega-showdown"]:
    try:
        modrinth[slug] = versions(slug)[:15]
        lines.append(f"{slug}: {[v['version_number'] for v in modrinth[slug][:5]]}")
    except Exception as e:
        lines.append(f"{slug}: ERR {e}")
# dependances requises de Mega Showdown (version pour Cobblemon 1.8.4)
ms = next((v for v in modrinth.get("cobblemon-mega-showdown", []) if "1.8.4" in v["version_number"]), None) or \
     (modrinth.get("cobblemon-mega-showdown") or [None])[0]
if ms:
    for d in ms.get("dependencies", []):
        if d.get("dependency_type") != "required" or not d.get("project_id"):
            continue
        try:
            s = json.loads(get(f"https://api.modrinth.com/v2/project/{d['project_id']}"))["slug"]
            if s in modrinth:
                continue
            modrinth[s] = versions(s)[:10]
            lines.append(f"dep mega showdown {s}: {[v['version_number'] for v in modrinth[s][:3]]}")
        except Exception as e:
            lines.append(f"dep {d} ERR {e}")
json.dump({k: [{"version_number": v["version_number"], "files": v["files"], "dependencies": v.get("dependencies", [])} for v in vs]
           for k, vs in modrinth.items()}, open(os.path.join(OUT, "modrinth.json"), "w"), indent=1)
print("\n".join(lines))
