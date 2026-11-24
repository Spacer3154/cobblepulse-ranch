#!/usr/bin/env python3
"""CI : vrai serveur Fabric 1.21.1 (Cobblemon 1.8.4, LuckPerms, Mega Showdown, CobblePulse-Ecology, datapack d'especes de
test, CobblePulse Ranch). 1) auto-tests puis preparation d'un depot interrompu et ARRET BRUTAL (kill -9) ;
2) redemarrage : reconciliation du journal (aucun doublon), persistance des pensions, auto-tests relances."""
import glob, json, os, queue, shutil, subprocess, sys, threading, time, urllib.request

OUT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else "out")
DISC = os.path.join(OUT, "discover")
SRV = os.path.abspath("run-server")
UA = {"User-Agent": "cobblepulse-ranch-ci/1.0 (github actions)"}
props = dict(l.strip().split("=", 1) for l in open("gradle.properties") if "=" in l and not l.startswith("#"))


def get(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=300) as r:
        return r.read()


def log(msg):
    print(msg, flush=True)
    with open(os.path.join(OUT, "smoke-steps.txt"), "a") as fh:
        fh.write(msg + "\n")


os.makedirs(os.path.join(SRV, "mods"), exist_ok=True)
modrinth = json.load(open(os.path.join(DISC, "modrinth.json")))
wanted = {"fabric-api": None, "fabric-language-kotlin": None, "luckperms": None, "cobblemon": "1.8.4", "cobblemon-mega-showdown": "1.8.4"}
for k in modrinth:
    wanted.setdefault(k, None)
for slug, ver in wanted.items():
    vs = modrinth.get(slug) or []
    pick = next((v for v in vs if ver is None or ver in v["version_number"]), None) or (vs[0] if vs and slug == "cobblemon-mega-showdown" else None)
    if not pick:
        log(f"!! missing {slug}")
        continue
    f = [f for f in pick["files"] if f["primary"]] or pick["files"]
    open(os.path.join(SRV, "mods", f[0]["filename"]), "wb").write(get(f[0]["url"]))
    log(f"mod {slug} {pick['version_number']}")
for j in glob.glob("ci/mods/*.jar"):
    shutil.copy(j, os.path.join(SRV, "mods"))
    log("mod " + os.path.basename(j))
for j in [j for j in glob.glob("build/libs/*.jar") if not j.endswith("-sources.jar")]:
    shutil.copy(j, os.path.join(SRV, "mods"))
    log("mod ranch " + j)

installer = json.loads(get("https://meta.fabricmc.net/v2/versions/installer"))[0]["version"]
loader = props.get("loader_version")
open(os.path.join(SRV, "server.jar"), "wb").write(get(f"https://meta.fabricmc.net/v2/versions/loader/1.21.1/{loader}/{installer}/server/jar"))
open(os.path.join(SRV, "eula.txt"), "w").write("eula=true\n")
open(os.path.join(SRV, "server.properties"), "w").write(
    "online-mode=false\nview-distance=4\nsimulation-distance=4\nspawn-protection=0\nmax-tick-time=-1\nlevel-seed=cobblepulse\nsync-chunk-writes=false\n")
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import datapack
log("datapack d'especes de test : " + datapack.make(SRV))


def run_server(label, commands, brutal=False):
    log("===== run %s =====" % label)
    proc = subprocess.Popen(["java", "-Xmx5G", "-Dcobblepulse_ranch.selftest=true", "-jar", "server.jar", "nogui"], cwd=SRV,
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    lines = queue.Queue()
    logf = open(os.path.join(OUT, "server-%s.log" % label), "w")

    def reader():
        for line in proc.stdout:
            logf.write(line)
            logf.flush()
            lines.put(line)
        lines.put(None)

    threading.Thread(target=reader, daemon=True).start()

    def wait_for(token, timeout):
        end = time.time() + timeout
        while time.time() < end:
            try:
                line = lines.get(timeout=1)
            except queue.Empty:
                continue
            if line is None:
                return False
            if token in line:
                return True
        return False

    ok = wait_for("Done (", 1200)
    log("server ready: %s" % ok)
    if ok:
        for c in commands:
            if c.startswith("sleep "):
                time.sleep(float(c.split()[1]))
                continue
            if c.startswith("wait "):
                log("waiting for: " + c[5:] + " -> " + str(wait_for(c[5:], 900)))
                continue
            log("> " + c)
            proc.stdin.write(c + "\n")
            proc.stdin.flush()
            time.sleep(2)
        time.sleep(3)
    if brutal:
        log("ARRET BRUTAL (kill -9)")
        proc.kill()
    else:
        try:
            proc.stdin.write("stop\n")
            proc.stdin.flush()
        except Exception:
            pass
    try:
        proc.wait(timeout=180)
    except subprocess.TimeoutExpired:
        proc.kill()
    time.sleep(2)
    logf.close()
    for l in open(os.path.join(OUT, "server-%s.log" % label), errors="replace"):
        if "SELFTEST SUMMARY" in l or "] FAIL " in l or "arret_brutal" in l or "persistance_apres" in l or "journal_vide" in l \
                or "[CobblePulse-Ranch] OK" in l or "probleme(s)" in l or ("Exception" in l and "ranch" in l.lower()) \
                or "Transfert interrompu" in l:
            log("[%s] %s" % (label, l.strip()[:500]))
    log("exit code %s" % proc.returncode)


run_server("1", ["sleep 5", "cobbleranch selftest", "wait SELFTEST DONE", "save-all flush", "sleep 3", "cobbleranch selftest crash",
                 "wait CRASH READY"], brutal=True)
run_server("2", ["sleep 5", "cobbleranch selftest crashcheck", "wait CRASHCHECK DONE", "cobbleranch selftest persistcheck",
                 "wait PERSISTCHECK DONE", "cobbleranch reload", "cobbleranch selftest", "wait SELFTEST DONE"])
shutil.copytree(os.path.join(SRV, "config", "cobbleranch"), os.path.join(OUT, "server-config"), dirs_exist_ok=True)
for crash in glob.glob(os.path.join(SRV, "crash-reports", "*")):
    shutil.copy(crash, OUT)
