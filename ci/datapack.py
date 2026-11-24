"""Datapack de test : deux especes de datapack (Ranchbebe -> Ranchadulte) copiees d'Evoli, avec leurs noms traduits,
installe seulement sur le serveur (comme un pack de Fakemons) pour verifier la reproduction des especes de datapacks."""
import glob, io, json, os, re, zipfile


def make(srv):
    dst = os.path.join(srv, "world", "datapacks", "ranch-test-species.zip")
    if os.path.exists(dst):
        return "deja present"
    jars = [j for j in glob.glob(os.path.join(srv, "mods", "*.jar")) if re.match(r"cobblemon-fabric", os.path.basename(j), re.I)]
    if not jars:
        return "jar Cobblemon introuvable"
    with zipfile.ZipFile(jars[0]) as z:
        names = [n for n in z.namelist() if re.search(r"^data/cobblemon/species/.*/eevee\.json$", n)]
        if not names:
            return "eevee.json introuvable"
        base = json.loads(z.read(names[0]).decode("utf-8"))
    out = {}
    for name, dex, pre in (("Ranchbebe", 9911, None), ("Ranchadulte", 9912, "ranchbebe")):
        sp = json.loads(json.dumps(base))
        sp["name"] = name
        sp["nationalPokedexNumber"] = dex
        sp["implemented"] = True
        sp["eggGroups"] = ["field", "fairy"]
        for k in ("evolutions", "forms", "preEvolution", "labels"):
            sp.pop(k, None)
        if pre:
            sp["preEvolution"] = pre
        out[name.lower()] = sp
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("pack.mcmeta", json.dumps({"pack": {"pack_format": 48, "description": "CobblePulse Ranch CI species"}}))
        for k, sp in out.items():
            z.writestr(f"data/cobblemon/species/custom/{k}.json", json.dumps(sp, indent=2))
        z.writestr("assets/cobblemon/lang/fr_fr.json", json.dumps({"cobblemon.species.ranchbebe.name": "Ranchbébé",
                                                                   "cobblemon.species.ranchadulte.name": "Ranchadulte"}, ensure_ascii=False))
    open(dst, "wb").write(buf.getvalue())
    return "cree"
