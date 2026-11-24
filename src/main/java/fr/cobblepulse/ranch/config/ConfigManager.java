package fr.cobblepulse.ranch.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import fr.cobblepulse.ranch.CobbleRanch;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lecture / ecriture de config/cobbleranch/ (cles manquantes ajoutees, valeurs existantes gardees). */
public final class ConfigManager {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static volatile RanchConfig config = new RanchConfig();
    private static volatile Map<String, List<HabitatConfig.Entry>> habitat = HabitatConfig.defaults();
    private static final List<String> warnings = new ArrayList<>();

    private ConfigManager() {}

    public static Path dir() {
        return FabricLoader.getInstance().getConfigDir().resolve("cobbleranch");
    }

    public static RanchConfig get() {
        return config;
    }

    public static Map<String, List<HabitatConfig.Entry>> habitat() {
        return habitat;
    }

    public static List<String> warnings() {
        return warnings;
    }

    public static synchronized void load() {
        warnings.clear();
        try {
            Files.createDirectories(dir());
        } catch (IOException e) {
            CobbleRanch.LOG.error("[CobblePulse-Ranch] Dossier de configuration impossible a creer", e);
        }
        config = loadMerged(dir().resolve("config.json"), new RanchConfig());
        sanitize(config);
        Path hp = dir().resolve("habitat_blocks.json");
        Map<String, List<HabitatConfig.Entry>> h = null;
        if (Files.exists(hp)) {
            try {
                h = GSON.fromJson(Files.readString(hp, StandardCharsets.UTF_8), new TypeToken<LinkedHashMap<String, List<HabitatConfig.Entry>>>() {}.getType());
            } catch (Exception e) {
                warnings.add("habitat_blocks.json illisible (" + e.getMessage() + ") : valeurs par defaut utilisees");
            }
        }
        if (h == null) {
            h = HabitatConfig.defaults();
            if (!Files.exists(hp)) write(hp, GSON.toJson(h));
        }
        Map<String, List<HabitatConfig.Entry>> normed = new LinkedHashMap<>();
        for (Map.Entry<String, List<HabitatConfig.Entry>> e : h.entrySet()) {
            List<HabitatConfig.Entry> list = new ArrayList<>();
            for (HabitatConfig.Entry x : e.getValue() == null ? List.<HabitatConfig.Entry>of() : e.getValue()) {
                if (x == null || x.block == null || x.block.isBlank()) continue;
                if (x.points < 0) x.points = 0;
                if (x.max_points < 0) x.max_points = 0;
                list.add(x);
            }
            normed.put(HabitatConfig.norm(e.getKey()), list);
        }
        habitat = normed;
        for (String w : warnings) CobbleRanch.LOG.warn("[CobblePulse-Ranch] {}", w);
    }

    private static void sanitize(RanchConfig c) {
        RanchConfig.Environment env = c.environment_5x5;
        if (env.min_breed_duration_seconds < 1) env.min_breed_duration_seconds = 1;
        if (env.base_breed_duration_seconds < env.min_breed_duration_seconds) env.base_breed_duration_seconds = env.min_breed_duration_seconds;
        if (env.optimal_points_required < 1) env.optimal_points_required = 1;
        if (env.scan_refresh_cooldown_seconds < 0) env.scan_refresh_cooldown_seconds = 0;
        if (c.general_settings.slots_per_ranch_block < 1) c.general_settings.slots_per_ranch_block = 1;
        if (c.general_settings.slots_per_ranch_block > 6) c.general_settings.slots_per_ranch_block = 6;
        if (c.general_settings.max_eggs_per_slot < 1) c.general_settings.max_eggs_per_slot = 1;
        if (c.general_settings.default_fertility_uses < 1) c.general_settings.default_fertility_uses = 1;
        if (c.genetics.base_shiny_rate < 1) c.genetics.base_shiny_rate = 1;
        c.genetics.destiny_knot_ivs = Math.max(0, Math.min(6, c.genetics.destiny_knot_ivs));
        c.genetics.default_inherited_ivs = Math.max(0, Math.min(6, c.genetics.default_inherited_ivs));
        if (c.hatching_and_items.egg_tick_interval_seconds < 1) c.hatching_and_items.egg_tick_interval_seconds = 1;
        if (c.hatching_and_items.steps_per_egg_cycle < 1) c.hatching_and_items.steps_per_egg_cycle = 1;
        if (c.remote_breed.slots < 1) c.remote_breed.slots = 1;
        if (c.remote_breed.slots > 6) c.remote_breed.slots = 6;
        if (c.remote_breed.duration_seconds < 1) c.remote_breed.duration_seconds = 1;
        String mode = c.hatching_and_items.silver_hourglass_reduction_mode;
        if (!"FIXED".equalsIgnoreCase(mode) && !"PERCENTAGE".equalsIgnoreCase(mode)) {
            warnings.add("silver_hourglass_reduction_mode inconnu (" + mode + ") : PERCENTAGE");
            c.hatching_and_items.silver_hourglass_reduction_mode = "PERCENTAGE";
        }
    }

    /** Lit le fichier, ajoute les cles manquantes depuis les valeurs par defaut et reecrit le fichier. */
    private static <T> T loadMerged(Path p, T defaults) {
        JsonObject def = GSON.toJsonTree(defaults).getAsJsonObject();
        JsonObject cur = null;
        if (Files.exists(p)) {
            try {
                JsonElement e = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8));
                if (e.isJsonObject()) cur = e.getAsJsonObject();
            } catch (Exception e) {
                warnings.add(p.getFileName() + " illisible (" + e.getMessage() + ") : valeurs par defaut, fichier sauvegarde en .bad");
                try {
                    Files.copy(p, p.resolveSibling(p.getFileName() + ".bad"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                }
            }
        }
        JsonObject merged = cur == null ? def : merge(cur, def);
        @SuppressWarnings("unchecked")
        T out = (T) GSON.fromJson(merged, defaults.getClass());
        write(p, GSON.toJson(merged));
        return out;
    }

    private static JsonObject merge(JsonObject cur, JsonObject def) {
        for (Map.Entry<String, JsonElement> e : def.entrySet()) {
            JsonElement c = cur.get(e.getKey());
            if (c == null || c.isJsonNull()) cur.add(e.getKey(), e.getValue());
            else if (c.isJsonObject() && e.getValue().isJsonObject() && !isFreeMap(e.getKey())) merge(c.getAsJsonObject(), e.getValue().getAsJsonObject());
        }
        return cur;
    }

    /** Tables libres (cles choisies par le serveur) : jamais completees avec les cles par defaut. */
    private static boolean isFreeMap(String key) {
        return key.equals("incense_babies") || key.equals("offspring_variants");
    }

    private static void write(Path p, String s) {
        try {
            Files.writeString(p, s, StandardCharsets.UTF_8);
        } catch (IOException e) {
            CobbleRanch.LOG.error("[CobblePulse-Ranch] Ecriture impossible : {}", p, e);
        }
    }
}
