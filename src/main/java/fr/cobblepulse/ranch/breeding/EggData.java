package fr.cobblepulse.ranch.breeding;

import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.pokemon.Gender;
import com.cobblemon.mod.common.pokemon.Nature;
import com.cobblemon.mod.common.pokemon.Pokemon;
import fr.cobblepulse.ranch.CobbleRanch;
import fr.cobblepulse.ranch.config.ConfigManager;
import fr.cobblepulse.ranch.registry.ModContent;
import fr.cobblepulse.ranch.util.Names;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Donnees d'un oeuf (composant "cobblepulse_ranch:egg" de l'objet Oeuf de Pokemon) : le Pokemon complet genere a la
 * ponte (fige : aucun nouveau tirage ensuite, ni au GTS ni a l'eclosion), les pas restants et une fiche d'affichage
 * (noms traduits + cles de traduction) lue par l'infobulle, /eggsee et la fiche GTS.
 */
public final class EggData {
    private EggData() {}

    public static CompoundTag build(Pokemon child, Genetics.Result r, String parents) {
        CompoundTag t = new CompoundTag();
        t.putUUID("id", UUID.randomUUID());
        t.put("pokemon", child.saveToNBT(CobbleRanch.registries(), new CompoundTag()));
        int cycles = child.getSpecies().getEggCycles();
        if (cycles <= 0) cycles = 20;
        int total = Math.max(1, cycles * ConfigManager.get().hatching_and_items.steps_per_egg_cycle);
        t.putInt("total", total);
        t.putDouble("steps", total);
        t.putLong("laid", System.currentTimeMillis());
        t.putInt("v", 1);
        t.put("info", info(child, r == null ? List.of() : r.eggMoves(), parents, cycles));
        return t;
    }

    static CompoundTag info(Pokemon p, List<String> eggMoves, String parents, int cycles) {
        CompoundTag i = new CompoundTag();
        i.putString("species", p.getSpecies().getResourceIdentifier().toString());
        i.putString("species_key", Names.speciesKey(p.getSpecies()));
        i.putString("species_name", Names.species(p.getSpecies()));
        i.putString("form", Names.form(p.getForm()));
        i.putBoolean("shiny", p.getShiny());
        Gender g = p.getGender();
        i.putString("gender", g == null ? "genderless" : g.name().toLowerCase(Locale.ROOT));
        Nature n = p.getNature();
        i.putString("nature_key", n.getDisplayName());
        i.putString("nature_name", Names.nature(n));
        i.putString("nature_plus", stat(n.getIncreasedStat()));
        i.putString("nature_minus", stat(n.getDecreasedStat()));
        i.putString("ability_key", p.getAbility().getDisplayName());
        i.putString("ability_name", Names.ability(p.getAbility().getTemplate()));
        i.putBoolean("hidden", Genetics.isHidden(p));
        try {
            var ball = p.getCaughtBall().item();
            i.putString("ball", BuiltInRegistries.ITEM.getKey(ball).toString());
            i.putString("ball_key", ball.getDescriptionId());
            i.putString("ball_name", Names.tr(ball.getDescriptionId(), p.getCaughtBall().getName().getPath()));
        } catch (Throwable ignored) {
        }
        int[] ivs = new int[6];
        for (int k = 0; k < 6; k++) ivs[k] = p.getIvs().getOrDefault(Genetics.STATS[k]);
        i.putIntArray("ivs", ivs);
        ListTag moves = new ListTag();
        for (String m : eggMoves) {
            MoveTemplate t = Moves.getByName(m);
            CompoundTag mt = new CompoundTag();
            mt.putString("id", m);
            mt.putString("key", t == null ? "" : Names.moveKey(t));
            mt.putString("name", t == null ? m : Names.move(t));
            moves.add(mt);
        }
        i.put("egg_moves", moves);
        int color = 0xF2E6C8;
        try {
            color = p.getForm().getPrimaryType().getHue();
        } catch (Throwable ignored) {
        }
        i.putInt("color", color);
        i.putString("parents", parents == null ? "" : parents);
        i.putInt("cycles", cycles);
        return i;
    }

    private static String stat(com.cobblemon.mod.common.api.pokemon.stats.Stat s) {
        for (int k = 0; k < 6; k++) if (Genetics.STATS[k] == s) return Names.STAT_LABELS[k];
        return "";
    }

    // ---------------------------------------------------------------- objet

    public static ItemStack toStack(CompoundTag egg) {
        ItemStack st = new ItemStack(ModContent.POKEMON_EGG);
        st.set(ModContent.EGG_DATA, egg.copy());
        return st;
    }

    public static CompoundTag of(ItemStack st) {
        if (st == null || st.isEmpty() || !st.is(ModContent.POKEMON_EGG)) return null;
        CompoundTag t = st.get(ModContent.EGG_DATA);
        return t != null && t.hasUUID("id") && t.contains("pokemon", Tag.TAG_COMPOUND) ? t : null;
    }

    public static UUID id(CompoundTag egg) {
        return egg.hasUUID("id") ? egg.getUUID("id") : null;
    }

    public static UUID pokemonId(CompoundTag egg) {
        CompoundTag p = egg.getCompound("pokemon");
        return p.hasUUID("UUID") ? p.getUUID("UUID") : null;
    }

    public static double steps(CompoundTag egg) {
        return egg.getDouble("steps");
    }

    public static int total(CompoundTag egg) {
        return Math.max(1, egg.getInt("total"));
    }

    public static CompoundTag withSteps(CompoundTag egg, double steps) {
        CompoundTag c = egg.copy();
        c.putDouble("steps", Math.max(0, steps));
        return c;
    }

    public static Pokemon pokemon(CompoundTag egg) {
        try {
            return new Pokemon().loadFromNBT(CobbleRanch.registries(), egg.getCompound("pokemon").copy());
        } catch (Throwable t) {
            CobbleRanch.LOG.error("[CobblePulse-Ranch] Oeuf illisible", t);
            return null;
        }
    }

    public static CompoundTag info(CompoundTag egg) {
        return egg.getCompound("info");
    }

    /** 0..1 : avancement de l'eclosion. */
    public static double progress(CompoundTag egg) {
        return 1.0 - steps(egg) / total(egg);
    }

    /** Phrase d'etat comme dans les jeux. */
    public static String status(double progress) {
        if (progress >= 0.9) return "Des bruits proviennent de l'intérieur ! Il va bientôt éclore !";
        if (progress >= 0.6) return "Semble s'agiter doucement !";
        if (progress >= 0.3) return "Il bouge de temps en temps.";
        return "Il faudra encore du temps avant qu'il éclose.";
    }

    public static String ivLabel(int v) {
        if (v >= 31) return "PARFAIT";
        if (v >= 26) return "EXCELLENT";
        if (v >= 16) return "BON";
        if (v >= 6) return "CORRECT";
        return "FAIBLE";
    }

    public static String bar(double progress, int width) {
        int n = (int) Math.round(Math.max(0, Math.min(1, progress)) * width);
        return "[" + "=".repeat(n) + " ".repeat(width - n) + "]";
    }

    public static String fmt(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    // ---------------------------------------------------------------- textes (legacy &) : GTS, /eggsee

    public static List<String> gtsSheet(CompoundTag egg) {
        CompoundTag i = info(egg);
        List<String> l = new ArrayList<>();
        String form = i.getString("form");
        l.add("&6&lFICHE GTS — ŒUF DE POKÉMON");
        l.add("&7Espèce : &f" + i.getString("species_name") + (form.isEmpty() ? " &8(Forme Standard)" : " &8(" + form + ")"));
        l.add("&7Talent : &f" + i.getString("ability_name") + (i.getBoolean("hidden") ? " &d(TALENT CACHÉ)" : " &8(Normal)"));
        l.add("&7Shiny : " + (i.getBoolean("shiny") ? "&eOUI ⭐" : "&fNon"));
        String plus = i.getString("nature_plus"), minus = i.getString("nature_minus");
        l.add("&7Nature : &f" + i.getString("nature_name") + (plus.isEmpty() || plus.equals(minus) ? "" : " &8(+" + plus + ", -" + minus + ")"));
        l.add("&7Sexe : &f" + gender(i.getString("gender")));
        l.add("&7Poké Ball : &f" + i.getString("ball_name"));
        int[] ivs = i.getIntArray("ivs");
        int tot = 0;
        for (int v : ivs) tot += v;
        l.add("&6Informations génétiques (IVs)");
        for (int k = 0; k + 1 < 6; k += 2) {
            l.add("&7" + pad(Names.STAT_LABELS[k]) + ": " + iv(ivs, k) + "   &7" + pad(Names.STAT_LABELS[k + 1]) + ": " + iv(ivs, k + 1));
        }
        l.add(String.format(Locale.US, "&7TOTAL IVs : &f%d / 186 &8(%.1f%%)", tot, tot * 100.0 / 186));
        ListTag moves = i.getList("egg_moves", Tag.TAG_COMPOUND);
        l.add("&6Capacités Œuf");
        if (moves.isEmpty()) l.add("&8- aucune");
        for (int k = 0; k < moves.size(); k++) l.add("&f- " + moves.getCompound(k).getString("name"));
        long left = Math.round(steps(egg));
        l.add("&6Éclosion : &fPas restants : " + fmt(left) + " / " + fmt(total(egg)));
        return l;
    }

    private static String pad(String s) {
        return s.length() >= 3 ? s : s + " ";
    }

    private static String iv(int[] ivs, int k) {
        int v = k < ivs.length ? ivs[k] : 0;
        String col = v >= 31 ? "&a" : v >= 16 ? "&f" : "&c";
        return col + v + " &8[" + ivLabel(v) + "]";
    }

    public static String gender(String g) {
        return switch (g) {
            case "male" -> "♂ Mâle";
            case "female" -> "♀ Femelle";
            default -> "Asexué";
        };
    }

    public static List<String> eggMoveNames(CompoundTag egg) {
        List<String> out = new ArrayList<>();
        ListTag moves = info(egg).getList("egg_moves", Tag.TAG_COMPOUND);
        for (int k = 0; k < moves.size(); k++) out.add(moves.getCompound(k).getString("name"));
        return out;
    }

    public static ListTag strings(List<String> l) {
        ListTag t = new ListTag();
        for (String s : l) t.add(StringTag.valueOf(s));
        return t;
    }
}
