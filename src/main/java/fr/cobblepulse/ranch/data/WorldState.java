package fr.cobblepulse.ranch.data;

import fr.cobblepulse.ranch.CobbleRanch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Etat global (world/cobbleranch/data/state.dat) : reglages modifies en direct par le staff (Ditto x Ditto) et registre
 * des oeufs eclos. Un oeuf duplique (bug d'un autre mod, retour arriere) ne peut pas eclore deux fois : son identifiant
 * est deja dans le registre.
 */
public final class WorldState {
    public static final class Hatch {
        public UUID pokemon;
        public UUID player;
        public long time;
        /** false tant que l'equipe et l'inventaire du joueur n'ont pas ete enregistres apres l'eclosion. */
        public boolean durable;
    }

    private static Boolean dittoOverride;
    private static final Map<UUID, Hatch> HATCHED = new HashMap<>();
    private static Path file;

    private WorldState() {}

    public static synchronized void init(Path dataDir) {
        HATCHED.clear();
        dittoOverride = null;
        file = dataDir.resolve("state.dat");
        if (!Files.exists(file)) return;
        try {
            CompoundTag t = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            if (t.contains("ditto_override")) dittoOverride = t.getBoolean("ditto_override");
            CompoundTag h = t.getCompound("hatched");
            for (String k : h.getAllKeys()) {
                CompoundTag e = h.getCompound(k);
                Hatch x = new Hatch();
                x.pokemon = e.hasUUID("pokemon") ? e.getUUID("pokemon") : null;
                x.player = e.hasUUID("player") ? e.getUUID("player") : null;
                x.time = e.getLong("time");
                x.durable = e.getBoolean("durable");
                HATCHED.put(UUID.fromString(k), x);
            }
        } catch (Exception e) {
            CobbleRanch.LOG.error("[CobblePulse-Ranch] state.dat illisible", e);
        }
    }

    public static Boolean dittoOverride() {
        return dittoOverride;
    }

    public static void setDittoOverride(Boolean v) {
        dittoOverride = v;
        save();
    }

    public static Hatch hatched(UUID egg) {
        return HATCHED.get(egg);
    }

    public static Map<UUID, Hatch> hatchedAll() {
        return HATCHED;
    }

    public static void recordHatch(UUID egg, UUID pokemon, UUID player, boolean durable) {
        Hatch h = new Hatch();
        h.pokemon = pokemon;
        h.player = player;
        h.time = System.currentTimeMillis();
        h.durable = durable;
        HATCHED.put(egg, h);
        save();
    }

    public static void markDurable(UUID egg) {
        Hatch h = HATCHED.get(egg);
        if (h != null && !h.durable) {
            h.durable = true;
            save();
        }
    }

    /** Registre limite aux 180 derniers jours. */
    public static void prune() {
        long limit = System.currentTimeMillis() - 180L * 24 * 3600 * 1000;
        if (HATCHED.values().removeIf(h -> h.durable && h.time < limit)) save();
    }

    public static synchronized void save() {
        if (file == null) return;
        CompoundTag t = new CompoundTag();
        if (dittoOverride != null) t.putBoolean("ditto_override", dittoOverride);
        CompoundTag h = new CompoundTag();
        HATCHED.forEach((k, x) -> {
            CompoundTag e = new CompoundTag();
            if (x.pokemon != null) e.putUUID("pokemon", x.pokemon);
            if (x.player != null) e.putUUID("player", x.player);
            e.putLong("time", x.time);
            e.putBoolean("durable", x.durable);
            h.put(k.toString(), e);
        });
        t.put("hatched", h);
        Path tmp = file.resolveSibling("state.dat.tmp");
        try {
            NbtIo.writeCompressed(t, tmp);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            CobbleRanch.LOG.error("[CobblePulse-Ranch] Sauvegarde de state.dat impossible", e);
        }
    }
}
