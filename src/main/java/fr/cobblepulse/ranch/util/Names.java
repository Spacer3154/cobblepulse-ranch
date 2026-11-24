package fr.cobblepulse.ranch.util;

import com.cobblemon.mod.common.api.abilities.AbilityTemplate;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Nature;
import com.cobblemon.mod.common.pokemon.Species;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobblepulse.ranch.CobbleRanch;
import fr.cobblepulse.ranch.config.ConfigManager;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Noms traduits cote serveur (fiches GTS, journaux, messages construits en texte) : fichiers de langue de tous les mods
 * (Cobblemon, CobblePulse-Ecology, autres mods de Pokemon) et des datapacks du monde, dans la langue de la config.
 * Les composants envoyes aux joueurs utilisent la cle de traduction avec ce nom en secours : un Fakemon installe
 * seulement sur le serveur s'affiche quand meme avec son nom.
 */
public final class Names {
    private static final Map<String, String> LANG = new HashMap<>();

    private Names() {}

    public static synchronized void load(Path worldDatapacks) {
        LANG.clear();
        String lang = ConfigManager.get().general_settings.language.toLowerCase(Locale.ROOT);
        int n = 0;
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            for (Path root : mod.getRootPaths()) {
                Path assets = root.resolve("assets");
                if (!Files.isDirectory(assets)) continue;
                try (Stream<Path> ns = Files.list(assets)) {
                    for (Path d : (Iterable<Path>) ns::iterator) {
                        Path f = d.resolve("lang").resolve(lang + ".json");
                        if (Files.isRegularFile(f)) {
                            try (InputStream in = Files.newInputStream(f)) {
                                n += read(in);
                            }
                        }
                    }
                } catch (Exception e) {
                    CobbleRanch.LOG.debug("[CobblePulse-Ranch] langue de {} illisible : {}", mod.getMetadata().getId(), e.toString());
                }
            }
        }
        if (worldDatapacks != null && Files.isDirectory(worldDatapacks)) {
            try (Stream<Path> packs = Files.list(worldDatapacks)) {
                for (Path p : (Iterable<Path>) packs::iterator) n += readPack(p, lang);
            } catch (Exception ignored) {
            }
        }
        CobbleRanch.LOG.info("[CobblePulse-Ranch] {} traductions ({}) chargees", n, lang);
    }

    private static int readPack(Path p, String lang) {
        int n = 0;
        try {
            if (Files.isDirectory(p)) {
                Path assets = p.resolve("assets");
                if (!Files.isDirectory(assets)) return 0;
                try (Stream<Path> ns = Files.list(assets)) {
                    for (Path d : (Iterable<Path>) ns::iterator) {
                        Path f = d.resolve("lang").resolve(lang + ".json");
                        if (Files.isRegularFile(f)) try (InputStream in = Files.newInputStream(f)) {
                            n += read(in);
                        }
                    }
                }
            } else if (p.toString().endsWith(".zip")) {
                try (ZipFile z = new ZipFile(p.toFile())) {
                    var en = z.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry e = en.nextElement();
                        if (e.getName().startsWith("assets/") && e.getName().endsWith("/lang/" + lang + ".json")) {
                            try (InputStream in = z.getInputStream(e)) {
                                n += read(in);
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return n;
    }

    private static int read(InputStream in) throws Exception {
        JsonElement e = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        if (!e.isJsonObject()) return 0;
        int n = 0;
        for (Map.Entry<String, JsonElement> x : ((JsonObject) e).entrySet()) {
            if (x.getValue().isJsonPrimitive()) {
                LANG.put(x.getKey(), x.getValue().getAsString());
                n++;
            }
        }
        return n;
    }

    public static String tr(String key, String fallback) {
        String s = key == null ? null : LANG.get(key);
        return s != null ? s : fallback;
    }

    public static String key(Component c) {
        return c != null && c.getContents() instanceof TranslatableContents t ? t.getKey() : null;
    }

    public static String speciesKey(Species s) {
        String k = key(s.getTranslatedName());
        return k != null ? k : "cobblemon.species." + s.getName().toLowerCase(Locale.ROOT) + ".name";
    }

    public static String species(Species s) {
        if (s == null) return "?";
        return tr(speciesKey(s), s.getName());
    }

    public static MutableComponent speciesC(Species s) {
        return Component.translatableWithFallback(speciesKey(s), species(s));
    }

    public static String formId(FormData f) {
        if (f == null || f == f.getSpecies().getStandardForm()) return "";
        return f.getName();
    }

    /** "Forme d'Alola", ou le nom de la forme ; "" pour la forme de base. */
    public static String form(FormData f) {
        String id = formId(f);
        if (id.isEmpty()) return "";
        String l = id.toLowerCase(Locale.ROOT);
        if (l.startsWith("alola")) return "Forme d'Alola";
        if (l.startsWith("galar")) return "Forme de Galar";
        if (l.startsWith("hisui")) return "Forme de Hisui";
        if (l.startsWith("paldea")) return "Forme de Paldea" + (l.contains("combat") ? " (Combat)" : l.contains("blaze") ? " (Flamboyante)" : l.contains("aqua") ? " (Aquatique)" : "");
        String k = "cobblemon.ui.pokedex.info.form." + f.getSpecies().getName().toLowerCase(Locale.ROOT) + "-" + l;
        return tr(k, id);
    }

    public static String abilityKey(AbilityTemplate a) {
        return a.getDisplayName();
    }

    public static String ability(AbilityTemplate a) {
        return a == null ? "?" : tr(a.getDisplayName(), a.getName());
    }

    public static String nature(Nature n) {
        return n == null ? "?" : tr(n.getDisplayName(), n.getName().getPath());
    }

    public static String moveKey(MoveTemplate m) {
        String k = key(m.getDisplayName());
        return k != null ? k : "cobblemon.move." + m.getName();
    }

    public static String move(MoveTemplate m) {
        return m == null ? "?" : tr(moveKey(m), m.getName());
    }

    public static String item(ItemStack st) {
        if (st == null || st.isEmpty()) return "";
        return tr(st.getItem().getDescriptionId(), st.getHoverName().getString());
    }

    public static String itemKey(ItemStack st) {
        return st == null || st.isEmpty() ? "" : st.getItem().getDescriptionId();
    }

    /** Nom d'un groupe d'oeufs ("water_1" -> "Aquatique 1"). */
    public static String eggGroup(String showdownOrEnum) {
        String g = showdownOrEnum.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return switch (g) {
            case "monster" -> "Monstrueux";
            case "water1" -> "Aquatique 1";
            case "water2" -> "Aquatique 2";
            case "water3" -> "Aquatique 3";
            case "bug" -> "Insectoïde";
            case "flying" -> "Aérien";
            case "field" -> "Terrestre";
            case "fairy" -> "Féérique";
            case "grass" -> "Végétal";
            case "humanlike" -> "Humanoïde";
            case "mineral" -> "Minéral";
            case "amorphous" -> "Amorphe";
            case "ditto" -> "Métamorph";
            case "dragon" -> "Draconique";
            case "undiscovered" -> "Inconnu";
            case "default" -> "Par défaut";
            default -> showdownOrEnum;
        };
    }

    public static final String[] STAT_LABELS = {"PV", "Atk", "Def", "SpA", "SpD", "Spe"};
}
