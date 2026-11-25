package fr.cobblepulse.ranch.perm;

import fr.cobblepulse.ranch.CobbleRanch;
import fr.cobblepulse.ranch.config.ConfigManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Noeuds de permission du CDC (section 7.2). Valeur prise dans LuckPerms si le noeud y est defini (heritage et jokers
 * compris, un refus explicite l'emporte) ; sinon valeur par defaut : "tous" pour l'usage normal, "operateur" pour les
 * avantages VIP, les contournements et l'administration.
 */
public final class Perms {
    public static final String USE = "cobbleranch.user.use";
    public static final String HOURGLASS_SILVER = "cobbleranch.user.hourglass.silver";
    public static final String HOURGLASS_GOLD = "cobbleranch.user.hourglass.gold";
    public static final String NEUTER = "cobbleranch.user.neuter";
    public static final String CMD_DAYCARE = "cobbleranch.command.daycare";
    public static final String CMD_DAYCARE_REMOTE = "cobbleranch.command.daycare.remote";
    public static final String CMD_BREED = "cobbleranch.command.breed";
    public static final String CMD_BREED_VIP = "cobbleranch.command.breed.vip";
    public static final String CMD_HATCH = "cobbleranch.command.hatch";
    public static final String CMD_HATCH_VIP = "cobbleranch.command.hatch.vip";
    public static final String CMD_EGGSEE = "cobbleranch.command.eggsee";
    public static final String CMD_CAPSULE = "cobbleranch.command.capsule";
    public static final String BYPASS_CHUNK = "cobbleranch.bypass.chunk_limit";
    public static final String BYPASS_FERTILITY = "cobbleranch.bypass.fertility";
    public static final String BYPASS_COOLDOWN = "cobbleranch.bypass.cooldown";
    public static final String ADMIN_GUI = "cobbleranch.admin.gui";
    public static final String ADMIN_VIEW = "cobbleranch.admin.view";
    public static final String ADMIN_MODIFY = "cobbleranch.admin.modify";
    public static final String ADMIN_CANCEL = "cobbleranch.admin.cancel";
    public static final String ADMIN_DELETE = "cobbleranch.admin.delete";
    public static final String ADMIN_GLOBAL_MODIFY = "cobbleranch.admin.global.modify";
    public static final String ADMIN_GLOBAL_CANCEL = "cobbleranch.admin.global.cancel";
    public static final String ADMIN_GLOBAL_DELETE = "cobbleranch.admin.global.delete";
    public static final String ADMIN_RULES_DITTO = "cobbleranch.admin.rules.ditto";
    public static final String ADMIN_RULES_OVERRIDE = "cobbleranch.admin.rules.override";

    /** true = accorde a tous par defaut ; false = operateurs seulement. */
    public static final Map<String, Boolean> DEFAULTS = new LinkedHashMap<>();

    static {
        for (String n : new String[]{USE, HOURGLASS_SILVER, HOURGLASS_GOLD, NEUTER, CMD_DAYCARE, CMD_BREED, CMD_HATCH, CMD_EGGSEE, CMD_CAPSULE}) DEFAULTS.put(n, true);
        for (String n : new String[]{CMD_DAYCARE_REMOTE, CMD_BREED_VIP, CMD_HATCH_VIP, BYPASS_CHUNK, BYPASS_FERTILITY, BYPASS_COOLDOWN,
                ADMIN_GUI, ADMIN_VIEW, ADMIN_MODIFY, ADMIN_CANCEL, ADMIN_DELETE, ADMIN_GLOBAL_MODIFY, ADMIN_GLOBAL_CANCEL,
                ADMIN_GLOBAL_DELETE, ADMIN_RULES_DITTO, ADMIN_RULES_OVERRIDE}) DEFAULTS.put(n, false);
    }

    private static final boolean LUCKPERMS = FabricLoader.getInstance().isModLoaded("luckperms");
    private static boolean warned;

    private Perms() {}

    public static boolean has(ServerPlayer p, String node) {
        if (p == null) return false;
        if (LUCKPERMS) {
            try {
                Boolean b = LuckPermsHook.check(p.getUUID(), node);
                if (b != null) return b;
            } catch (Throwable t) {
                if (!warned) {
                    warned = true;
                    CobbleRanch.LOG.warn("[CobblePulse-Ranch] API LuckPerms indisponible, valeurs par defaut : {}", t.toString());
                }
            }
        }
        if (Boolean.TRUE.equals(DEFAULTS.get(node))) return true;
        return p.hasPermissions(ConfigManager.get().general_settings.op_level_fallback);
    }

    /** Console / blocs de commande : tout est permis. */
    public static boolean has(CommandSourceStack src, String node) {
        if (src.getEntity() instanceof ServerPlayer p) return has(p, node);
        return src.hasPermission(2);
    }
}
