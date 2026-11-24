package fr.cobblepulse.ranch.service;

import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.cobblepulse.ranch.CobbleRanch;
import fr.cobblepulse.ranch.breeding.Compatibility;
import fr.cobblepulse.ranch.config.ConfigManager;
import fr.cobblepulse.ranch.data.RanchData;
import fr.cobblepulse.ranch.data.RanchStore;
import fr.cobblepulse.ranch.data.SlotData;
import fr.cobblepulse.ranch.data.WorldState;
import fr.cobblepulse.ranch.net.RanchNet;
import fr.cobblepulse.ranch.perm.Perms;
import fr.cobblepulse.ranch.util.Msg;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Panneau d'administration (/cobbleranch admin). Les boutons sans permission ne sont pas envoyes au client ; toute
 * action est re-verifiee ici et une action forcee sans permission est rejetee avec une alerte de securite.
 * Les actions globales demandent une confirmation en deux temps (jeton valable 15 s).
 */
public final class AdminService {
    private static final Map<UUID, Map<String, Long>> CONFIRM = new HashMap<>();

    private AdminService() {}

    public static boolean canOpen(ServerPlayer p) {
        return Perms.has(p, Perms.ADMIN_GUI);
    }

    public static void open(ServerPlayer p, String query, String filter, String sort, String message) {
        if (!canOpen(p)) {
            Msg.err(p, "Tu n'as pas accès au panneau d'administration.");
            return;
        }
        RanchNet.send(p, "admin", view(p, query, filter, sort, message));
    }

    static JsonObject view(ServerPlayer p, String query, String filter, String sort, String message) {
        JsonObject o = new JsonObject();
        query = query == null ? "" : query.trim();
        filter = filter == null || filter.isEmpty() ? "active" : filter;
        sort = sort == null || sort.isEmpty() ? "player" : sort;
        o.addProperty("query", query);
        o.addProperty("filter", filter);
        o.addProperty("sort", sort);
        o.addProperty("now", System.currentTimeMillis());
        if (message != null) o.addProperty("message", message);
        JsonObject perms = new JsonObject();
        for (String[] e : new String[][]{{"view", Perms.ADMIN_VIEW}, {"modify", Perms.ADMIN_MODIFY}, {"cancel", Perms.ADMIN_CANCEL},
                {"delete", Perms.ADMIN_DELETE}, {"gmodify", Perms.ADMIN_GLOBAL_MODIFY}, {"gcancel", Perms.ADMIN_GLOBAL_CANCEL},
                {"gdelete", Perms.ADMIN_GLOBAL_DELETE}, {"ditto", Perms.ADMIN_RULES_DITTO}, {"override", Perms.ADMIN_RULES_OVERRIDE}}) {
            perms.addProperty(e[0], Perms.has(p, e[1]));
        }
        o.add("perms", perms);
        o.addProperty("ditto", Compatibility.dittoXDittoAllowed());
        o.addProperty("ditto_override", WorldState.dittoOverride() == null ? "" : String.valueOf(WorldState.dittoOverride()));
        JsonArray list = new JsonArray();
        int total = 0;
        if (Perms.has(p, Perms.ADMIN_VIEW)) {
            List<RanchData> ranches = new ArrayList<>(RanchStore.all());
            if (sort.equals("location")) ranches.sort(Comparator.comparing((RanchData r) -> r.remote ? "~" : r.dimension).thenComparingInt(r -> r.pos.getX()).thenComparingInt(r -> r.pos.getZ()));
            else ranches.sort(Comparator.comparing((RanchData r) -> r.ownerName.toLowerCase(Locale.ROOT)));
            String q = query.toLowerCase(Locale.ROOT);
            for (RanchData r : ranches) {
                if (!q.isEmpty() && !r.ownerName.toLowerCase(Locale.ROOT).contains(q)) continue;
                if (filter.equals("orphan") && !r.orphan) continue;
                RanchService.evaluate(r, System.currentTimeMillis());
                for (int i = 0; i < r.slots.size(); i++) {
                    SlotData s = r.slots.get(i);
                    if (s.empty()) continue;
                    if (filter.equals("active") && s.start <= 0) continue;
                    total++;
                    if (list.size() >= 300) continue;
                    JsonObject e = new JsonObject();
                    e.addProperty("ranch", r.id.toString());
                    e.addProperty("slot", i);
                    e.addProperty("owner", r.ownerName);
                    e.addProperty("where", r.remote ? "Remote /breed" : r.where());
                    e.addProperty("chunk", r.remote ? "" : (r.pos.getX() >> 4) + ", " + (r.pos.getZ() >> 4));
                    e.addProperty("orphan", r.orphan);
                    Pokemon a = s.pokemon(true), b = s.pokemon(false);
                    e.addProperty("parents", (a == null ? "—" : RanchService.label(a)) + " x " + (b == null ? "—" : RanchService.label(b)));
                    e.addProperty("start", s.start);
                    e.addProperty("duration", s.duration);
                    e.addProperty("pause", s.pause);
                    e.addProperty("eggs", s.eggs.size());
                    int fert = -1;
                    for (Pokemon x : new Pokemon[]{a, b}) if (x != null) fert = fert < 0 ? fr.cobblepulse.ranch.breeding.Traits.fertility(x)
                            : Math.min(fert, fr.cobblepulse.ranch.breeding.Traits.fertility(x));
                    e.addProperty("fertility", fert);
                    list.add(e);
                }
            }
        }
        o.addProperty("total", total);
        o.add("entries", list);
        return o;
    }

    private static boolean require(ServerPlayer p, String node, String action) {
        if (Perms.has(p, node)) return true;
        CobbleRanch.LOG.warn("[CobblePulse-Ranch] ALERTE SECURITE : {} a tente l'action admin '{}' sans la permission {}", p.getGameProfile().getName(), action, node);
        for (ServerPlayer o : CobbleRanch.server().getPlayerList().getPlayers()) {
            if (o != p && Perms.has(o, Perms.ADMIN_GUI)) Msg.err(o, "Alerte sécurité : " + p.getGameProfile().getName() + " a tenté « " + action + " » sans permission.");
        }
        Msg.err(p, "Action refusée : permission manquante (" + node + "). Tentative enregistrée.");
        return false;
    }

    /** Deux clics : le premier arme, le second (dans les 15 s) execute. */
    private static boolean confirmed(ServerPlayer p, String key) {
        Map<String, Long> m = CONFIRM.computeIfAbsent(p.getUUID(), k -> new HashMap<>());
        Long t = m.get(key);
        long now = System.currentTimeMillis();
        if (t != null && now - t < 15_000) {
            m.remove(key);
            return true;
        }
        m.put(key, now);
        return false;
    }

    public static void action(ServerPlayer p, JsonObject a) {
        if (!require(p, Perms.ADMIN_GUI, "ouvrir le panneau")) return;
        String act = a.has("action") ? a.get("action").getAsString() : "";
        String query = a.has("query") ? a.get("query").getAsString() : "";
        String filter = a.has("filter") ? a.get("filter").getAsString() : "";
        String sort = a.has("sort") ? a.get("sort").getAsString() : "";
        RanchData r = a.has("ranch") ? RanchStore.get(UUID.fromString(a.get("ranch").getAsString())) : null;
        int slot = a.has("slot") ? a.get("slot").getAsInt() : -1;
        String who = p.getGameProfile().getName();
        String msg = null;
        switch (act) {
            case "refresh" -> {
            }
            case "force" -> {
                if (!require(p, Perms.ADMIN_MODIFY, act) || r == null) break;
                String e = RanchService.forceLay(r, slot);
                msg = e == null ? "Ponte forcée (" + r.ownerName + ", emplacement " + (slot + 1) + ")" : "Refusé : " + e;
                log(who, act, r, slot);
            }
            case "fertility" -> {
                if (!require(p, Perms.ADMIN_MODIFY, act) || r == null) break;
                int v = Math.max(0, Math.min(999, a.get("value").getAsInt()));
                String e = RanchService.setFertility(r, slot, v);
                msg = e == null ? "Fertilité réglée à " + v : "Refusé : " + e;
                log(who, act + "=" + v, r, slot);
            }
            case "time" -> {
                if (!require(p, Perms.ADMIN_MODIFY, act) || r == null) break;
                long v = Math.max(0, a.get("value").getAsLong());
                String e = RanchService.setRemaining(r, slot, v);
                msg = e == null ? "Temps restant réglé à " + v + " min" : "Refusé : " + e;
                log(who, act + "=" + v, r, slot);
            }
            case "cancel" -> {
                if (!require(p, Perms.ADMIN_CANCEL, act) || r == null) break;
                int[] n = RanchService.restituteSlot(r, slot, "annulé par " + who);
                msg = "Annulé : " + n[0] + " Pokémon et " + n[1] + " œuf(s) rendus à " + r.ownerName;
                log(who, act, r, slot);
            }
            case "delete" -> {
                if (!require(p, Perms.ADMIN_DELETE, act) || r == null) break;
                if (!confirmed(p, "delete:" + r.id + ":" + slot)) {
                    msg = "Clique encore sur Supprimer pour confirmer (définitif)";
                    break;
                }
                int[] n = RanchService.deleteSlot(r, slot, who);
                msg = "Supprimé : " + n[0] + " Pokémon et " + n[1] + " œuf(s)";
            }
            case "force_all" -> {
                if (!require(p, Perms.ADMIN_GLOBAL_MODIFY, act)) break;
                if (!confirmed(p, act)) {
                    msg = "Clique encore pour forcer toutes les pontes";
                    break;
                }
                int n = 0;
                for (RanchData x : new ArrayList<>(RanchStore.all())) for (int i = 0; i < x.slots.size(); i++) if (x.slots.get(i).full() && RanchService.forceLay(x, i) == null) n++;
                msg = n + " ponte(s) forcée(s)";
                CobbleRanch.LOG.warn("[CobblePulse-Ranch] {} a force {} ponte(s) sur tout le serveur", who, n);
            }
            case "restore_all" -> {
                if (!require(p, Perms.ADMIN_GLOBAL_CANCEL, act)) break;
                if (!confirmed(p, act)) {
                    msg = "Confirmation : clique encore sur RESTITUER TOUT (15 s)";
                    break;
                }
                int[] t = {0, 0};
                for (RanchData x : new ArrayList<>(RanchStore.all())) {
                    int[] n = RanchService.restituteAll(x, "restitution globale par " + who);
                    t[0] += n[0];
                    t[1] += n[1];
                }
                msg = "Restitution globale : " + t[0] + " Pokémon et " + t[1] + " œuf(s) rendus";
                CobbleRanch.LOG.warn("[CobblePulse-Ranch] RESTITUTION GLOBALE par {} : {} Pokemon, {} oeufs", who, t[0], t[1]);
            }
            case "purge_all" -> {
                if (!require(p, Perms.ADMIN_GLOBAL_DELETE, act)) break;
                if (!confirmed(p, act)) {
                    msg = "Confirmation : clique encore sur PURGER TOUT (15 s, définitif)";
                    break;
                }
                int[] t = {0, 0};
                for (RanchData x : new ArrayList<>(RanchStore.all())) for (int i = 0; i < x.slots.size(); i++) {
                    if (x.slots.get(i).empty()) continue;
                    int[] n = RanchService.deleteSlot(x, i, who);
                    t[0] += n[0];
                    t[1] += n[1];
                }
                msg = "Purge globale : " + t[0] + " Pokémon et " + t[1] + " œuf(s) supprimés";
                CobbleRanch.LOG.warn("[CobblePulse-Ranch] PURGE GLOBALE par {} : {} Pokemon, {} oeufs", who, t[0], t[1]);
            }
            case "ditto" -> {
                if (!require(p, Perms.ADMIN_RULES_DITTO, act)) break;
                boolean now = !Compatibility.dittoXDittoAllowed();
                WorldState.setDittoOverride(now == ConfigManager.get().general_settings.allow_ditto_x_ditto ? null : now);
                msg = "Ditto x Ditto : " + (now ? "AUTORISÉ" : "INTERDIT");
                CobbleRanch.LOG.warn("[CobblePulse-Ranch] {} : Ditto x Ditto {}", who, now ? "autorise" : "interdit");
            }
            case "reload" -> {
                if (!require(p, Perms.ADMIN_RULES_OVERRIDE, act)) break;
                CobbleRanch.reload();
                msg = "Configuration rechargée" + (ConfigManager.warnings().isEmpty() ? "" : " (" + ConfigManager.warnings().size() + " avertissement(s), voir le journal)");
            }
            default -> msg = null;
        }
        open(p, query, filter, sort, msg);
    }

    private static void log(String who, String act, RanchData r, int slot) {
        CobbleRanch.LOG.info("[CobblePulse-Ranch] Admin {} : {} sur la pension {} de {} (emplacement {})", who, act, r.id, r.ownerName, slot + 1);
    }
}
