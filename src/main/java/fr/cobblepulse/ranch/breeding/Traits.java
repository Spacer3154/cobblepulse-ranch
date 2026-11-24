package fr.cobblepulse.ranch.breeding;

import com.cobblemon.mod.common.api.pokemon.egg.EggGroup;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Pokemon;
import fr.cobblepulse.ranch.config.ConfigManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Donnees de reproduction portees par le Pokemon lui-meme (persistentData "cobbleranch") : fertilite restante et
 * castration. Elles suivent le Pokemon partout (PC, echanges, GTS, Pension).
 */
public final class Traits {
    public static final String KEY = "cobbleranch";

    private Traits() {}

    private static CompoundTag tag(Pokemon p) {
        CompoundTag root = p.getPersistentData();
        if (!root.contains(KEY)) root.put(KEY, new CompoundTag());
        return root.getCompound(KEY);
    }

    private static CompoundTag peek(Pokemon p) {
        CompoundTag root = p.getPersistentData();
        return root.contains(KEY) ? root.getCompound(KEY) : null;
    }

    public static int maxFertility() {
        return ConfigManager.get().general_settings.default_fertility_uses;
    }

    public static int fertility(Pokemon p) {
        CompoundTag t = peek(p);
        return t != null && t.contains("fertility") ? t.getInt("fertility") : maxFertility();
    }

    public static void setFertility(Pokemon p, int v) {
        tag(p).putInt("fertility", Math.max(0, v));
    }

    public static boolean fertilityEnabled() {
        return ConfigManager.get().general_settings.enable_fertility_system;
    }

    public static boolean sterile(Pokemon p) {
        return fertilityEnabled() && fertility(p) <= 0;
    }

    public static boolean neutered(Pokemon p) {
        CompoundTag t = peek(p);
        return t != null && t.getBoolean("neutered");
    }

    public static boolean neuteredTag(CompoundTag pokemonNbt) {
        if (pokemonNbt == null) return false;
        for (String k : new String[]{"PersistentData", "persistentData"}) {
            if (pokemonNbt.contains(k)) {
                CompoundTag pd = pokemonNbt.getCompound(k);
                if (pd.contains(KEY) && pd.getCompound(KEY).getBoolean("neutered")) return true;
            }
        }
        return false;
    }

    public static void setNeutered(Pokemon p, boolean v, UUID by) {
        CompoundTag t = tag(p);
        t.putBoolean("neutered", v);
        if (v) {
            if (by != null) t.putUUID("neutered_by", by);
            t.putLong("neutered_at", System.currentTimeMillis());
        } else {
            t.remove("neutered_by");
            t.remove("neutered_at");
        }
    }

    /** Le joueur est-il le Dresseur d'Origine de ce Pokemon ? */
    public static boolean isOriginalTrainer(Pokemon p, UUID player) {
        String ot = p.getOriginalTrainer();
        if (ot == null || ot.isBlank()) return false;
        try {
            return UUID.fromString(ot).equals(player);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static UUID originalTrainer(Pokemon p) {
        String ot = p.getOriginalTrainer();
        if (ot == null || ot.isBlank()) return null;
        try {
            return UUID.fromString(ot);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ---------- groupes d'oeufs ----------

    public static Set<EggGroup> eggGroups(Pokemon p) {
        Set<EggGroup> g = EnumSet.noneOf(EggGroup.class);
        FormData f = p.getForm();
        if (f != null && f.getEggGroups() != null) g.addAll(f.getEggGroups());
        if (g.isEmpty() && p.getSpecies().getEggGroups() != null) g.addAll(p.getSpecies().getEggGroups());
        return g;
    }

    public static boolean isDitto(Pokemon p) {
        return eggGroups(p).contains(EggGroup.DITTO) || p.getSpecies().getName().equalsIgnoreCase("ditto");
    }

    public static String groupId(EggGroup g) {
        return g.name().toLowerCase(Locale.ROOT);
    }

    // ---------- objets tenus ----------

    public static String heldId(Pokemon p) {
        ItemStack st = p.heldItem();
        return st == null || st.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
    }

    public static boolean holds(Pokemon p, String itemId) {
        return heldId(p).equals(itemId);
    }

    public static final String DESTINY_KNOT = "cobblemon:destiny_knot";
    public static final String EVERSTONE = "cobblemon:everstone";
    public static final String MIRROR_HERB = "cobblemon:mirror_herb";
    /** Objets Pouvoir -> statistique garantie (0 PV, 1 Atk, 2 Def, 3 SpA, 4 SpD, 5 Spe). */
    public static final String[] POWER_ITEMS = {"cobblemon:power_weight", "cobblemon:power_bracer", "cobblemon:power_belt",
            "cobblemon:power_lens", "cobblemon:power_band", "cobblemon:power_anklet"};

    public static int powerStat(Pokemon p) {
        String h = heldId(p);
        for (int i = 0; i < POWER_ITEMS.length; i++) if (POWER_ITEMS[i].equals(h)) return i;
        return -1;
    }

    /** Effet de l'objet tenu pour la reproduction (affiche dans la Pension), "" sinon. */
    public static String breedingItemEffect(Pokemon p) {
        String h = heldId(p);
        if (h.isEmpty()) return "";
        if (h.equals(DESTINY_KNOT)) return "transmet " + ConfigManager.get().genetics.destiny_knot_ivs + " IV";
        if (h.equals(EVERSTONE)) return "transmet sa nature et sa forme régionale";
        if (h.equals(MIRROR_HERB)) return "apprend les capacités œuf du partenaire";
        int ps = powerStat(p);
        if (ps >= 0) return "transmet l'IV " + fr.cobblepulse.ranch.util.Names.STAT_LABELS[ps];
        for (var e : ConfigManager.get().genetics.incense_babies.entrySet()) {
            if (e.getValue().contains(h)) return "encens : œuf de " + e.getKey();
        }
        return "";
    }
}
