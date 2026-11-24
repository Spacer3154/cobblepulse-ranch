package fr.cobblepulse.ranch.data;

import fr.cobblepulse.ranch.CobbleRanch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Donnees par joueur (world/cobbleranch/data/players.dat) : langue du client (Masuda), delais des commandes, boite de
 * reception (Pokemon et oeufs rendus a un joueur hors ligne ou au PC plein, remis a la connexion).
 */
public final class PlayerStore {
    public static final class Rec {
        public String name = "";
        public String language = "";
        public final Map<String, Long> cooldowns = new HashMap<>();
        /** Pokemon a rendre (NBT Cobblemon). */
        public final List<CompoundTag> pokemon = new ArrayList<>();
        /** oeufs a rendre (donnees d'oeuf). */
        public final List<CompoundTag> eggs = new ArrayList<>();
        /** objets a rendre (drops, sabliers). */
        public final List<CompoundTag> items = new ArrayList<>();

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("name", name);
            t.putString("language", language);
            CompoundTag cd = new CompoundTag();
            long now = System.currentTimeMillis();
            cooldowns.forEach((k, v) -> {
                if (v > now) cd.putLong(k, v);
            });
            t.put("cooldowns", cd);
            t.put("pokemon", list(pokemon));
            t.put("eggs", list(eggs));
            t.put("items", list(items));
            return t;
        }

        static Rec load(CompoundTag t) {
            Rec r = new Rec();
            r.name = t.getString("name");
            r.language = t.getString("language");
            CompoundTag cd = t.getCompound("cooldowns");
            for (String k : cd.getAllKeys()) r.cooldowns.put(k, cd.getLong(k));
            read(t.getList("pokemon", Tag.TAG_COMPOUND), r.pokemon);
            read(t.getList("eggs", Tag.TAG_COMPOUND), r.eggs);
            read(t.getList("items", Tag.TAG_COMPOUND), r.items);
            return r;
        }

        public boolean hasMail() {
            return !pokemon.isEmpty() || !eggs.isEmpty() || !items.isEmpty();
        }
    }

    private static ListTag list(List<CompoundTag> l) {
        ListTag t = new ListTag();
        for (CompoundTag c : l) t.add(c);
        return t;
    }

    private static void read(ListTag l, List<CompoundTag> out) {
        for (int i = 0; i < l.size(); i++) out.add(l.getCompound(i));
    }

    private static final Map<UUID, Rec> RECS = new HashMap<>();
    private static Path file;
    private static boolean dirty;

    private PlayerStore() {}

    public static synchronized void init(Path dataDir) {
        RECS.clear();
        file = dataDir.resolve("players.dat");
        if (Files.exists(file)) {
            try {
                CompoundTag t = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
                for (String k : t.getAllKeys()) {
                    try {
                        RECS.put(UUID.fromString(k), Rec.load(t.getCompound(k)));
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                CobbleRanch.LOG.error("[CobblePulse-Ranch] players.dat illisible", e);
            }
        }
    }

    public static Rec get(UUID u) {
        return RECS.computeIfAbsent(u, k -> new Rec());
    }

    public static Rec peek(UUID u) {
        return RECS.get(u);
    }

    public static Map<UUID, Rec> all() {
        return RECS;
    }

    public static void markDirty() {
        dirty = true;
    }

    public static void flushIfDirty() {
        if (dirty) save();
    }

    public static synchronized void save() {
        if (file == null) return;
        dirty = false;
        CompoundTag t = new CompoundTag();
        RECS.forEach((u, r) -> t.put(u.toString(), r.save()));
        Path tmp = file.resolveSibling("players.dat.tmp");
        try {
            NbtIo.writeCompressed(t, tmp);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            CobbleRanch.LOG.error("[CobblePulse-Ranch] Sauvegarde de players.dat impossible", e);
        }
    }

    // ---------- delais ----------

    /** Secondes restantes avant de pouvoir refaire l'action (0 = libre). */
    public static long remaining(UUID u, String key) {
        Long until = get(u).cooldowns.get(key);
        long now = System.currentTimeMillis();
        return until == null || until <= now ? 0 : (until - now + 999) / 1000;
    }

    public static void startCooldown(UUID u, String key, long seconds) {
        if (seconds <= 0) return;
        get(u).cooldowns.put(key, System.currentTimeMillis() + seconds * 1000L);
        dirty = true;
    }
}
