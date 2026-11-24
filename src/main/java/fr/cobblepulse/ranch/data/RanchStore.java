package fr.cobblepulse.ranch.data;

import fr.cobblepulse.ranch.CobbleRanch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Registre central des pensions : toutes les pensions en memoire (consultation /daycare et admin sans charger de chunk)
 * et un fichier par pension dans world/cobbleranch/data/ranches/ (ecriture atomique : fichier temporaire puis
 * remplacement). Le BlockEntity garde une copie miroir dans le NBT du chunk.
 */
public final class RanchStore {
    public record Where(UUID ranch, int slot, boolean first, boolean egg) {}

    private static final Map<UUID, RanchData> ALL = new LinkedHashMap<>();
    private static final Map<UUID, Where> POKEMON = new HashMap<>();
    private static final Map<UUID, Where> EGGS = new HashMap<>();
    private static final Set<UUID> DIRTY = new HashSet<>();
    /** index par chunk ("dimension|cle du chunk") : verification au chargement d'un chunk sans parcourir tout le registre. */
    private static final Map<String, Set<UUID>> CHUNKS = new HashMap<>();
    private static Path dir;

    private RanchStore() {}

    public static Path dataDir() {
        return dir.getParent();
    }

    public static synchronized void init(Path dataDir) {
        ALL.clear();
        POKEMON.clear();
        EGGS.clear();
        DIRTY.clear();
        CHUNKS.clear();
        OWN_POKEMON.clear();
        OWN_EGGS.clear();
        OWN_CHUNK.clear();
        dir = dataDir.resolve("ranches");
        try {
            Files.createDirectories(dir);
            Files.createDirectories(dataDir.resolve("deleted"));
        } catch (IOException e) {
            CobbleRanch.LOG.error("[CobblePulse-Ranch] Dossier de donnees impossible a creer : {}", dir, e);
        }
        int n = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (!p.toString().endsWith(".dat")) continue;
                try {
                    RanchData r = RanchData.load(NbtIo.readCompressed(p, NbtAccounter.unlimitedHeap()));
                    ALL.put(r.id, r);
                    index(r);
                    n++;
                } catch (Exception e) {
                    CobbleRanch.LOG.error("[CobblePulse-Ranch] Pension illisible {} (la copie du bloc servira si le chunk est charge)", p.getFileName(), e);
                }
            }
        } catch (IOException e) {
            CobbleRanch.LOG.error("[CobblePulse-Ranch] Lecture des pensions impossible", e);
        }
        CobbleRanch.LOG.info("[CobblePulse-Ranch] {} pension(s) chargee(s)", n);
    }

    /** ce que chaque pension a mis dans les index (retrait en temps constant). */
    private static final Map<UUID, List<UUID>> OWN_POKEMON = new HashMap<>();
    private static final Map<UUID, List<UUID>> OWN_EGGS = new HashMap<>();
    private static final Map<UUID, String> OWN_CHUNK = new HashMap<>();

    private static void unindex(UUID ranch) {
        List<UUID> p = OWN_POKEMON.remove(ranch);
        if (p != null) for (UUID u : p) POKEMON.remove(u);
        List<UUID> e = OWN_EGGS.remove(ranch);
        if (e != null) for (UUID u : e) EGGS.remove(u);
        String c = OWN_CHUNK.remove(ranch);
        if (c != null && CHUNKS.containsKey(c)) CHUNKS.get(c).remove(ranch);
    }

    private static String chunkId(String dim, long key) {
        return dim + "|" + key;
    }

    /** Pensions posees dans ce chunk. */
    public static List<RanchData> inChunk(String dim, long key) {
        List<RanchData> l = new ArrayList<>();
        Set<UUID> ids = CHUNKS.get(chunkId(dim, key));
        if (ids != null) for (UUID u : ids) {
            RanchData r = ALL.get(u);
            if (r != null) l.add(r);
        }
        return l;
    }

    private static void index(RanchData r) {
        unindex(r.id);
        if (!r.remote) {
            String c = chunkId(r.dimension, r.chunkKey());
            CHUNKS.computeIfAbsent(c, k -> new HashSet<>()).add(r.id);
            OWN_CHUNK.put(r.id, c);
        }
        List<UUID> pk = new ArrayList<>(), eg = new ArrayList<>();
        for (int i = 0; i < r.slots.size(); i++) {
            SlotData s = r.slots.get(i);
            for (boolean first : new boolean[]{true, false}) {
                UUID u = s.uuid(first);
                if (u != null) {
                    POKEMON.put(u, new Where(r.id, i, first, false));
                    pk.add(u);
                }
            }
            for (CompoundTag e : s.eggs) if (e.hasUUID("id")) {
                EGGS.put(e.getUUID("id"), new Where(r.id, i, false, true));
                eg.add(e.getUUID("id"));
            }
        }
        OWN_POKEMON.put(r.id, pk);
        OWN_EGGS.put(r.id, eg);
    }

    public static RanchData get(UUID id) {
        return id == null ? null : ALL.get(id);
    }

    public static Collection<RanchData> all() {
        return ALL.values();
    }

    public static List<RanchData> byOwner(UUID owner) {
        List<RanchData> l = new ArrayList<>();
        for (RanchData r : ALL.values()) if (owner.equals(r.owner)) l.add(r);
        return l;
    }

    public static RanchData remoteOf(UUID owner) {
        for (RanchData r : ALL.values()) if (r.remote && owner.equals(r.owner)) return r;
        return null;
    }

    public static int countInChunk(String dim, long chunk) {
        return inChunk(dim, chunk).size();
    }

    public static int countBlocksOf(UUID owner) {
        int n = 0;
        for (RanchData r : ALL.values()) if (!r.remote && owner.equals(r.owner)) n++;
        return n;
    }

    public static RanchData at(String dim, net.minecraft.core.BlockPos pos) {
        for (RanchData r : ALL.values()) if (!r.remote && r.dimension.equals(dim) && r.pos.equals(pos)) return r;
        return null;
    }

    public static Where findPokemon(UUID uuid) {
        return POKEMON.get(uuid);
    }

    public static Where findEgg(UUID egg) {
        return EGGS.get(egg);
    }

    public static void add(RanchData r) {
        ALL.put(r.id, r);
        save(r);
    }

    /** Ecriture immediate (transactions). */
    public static void save(RanchData r) {
        r.revision++;
        index(r);
        DIRTY.remove(r.id);
        write(r);
    }

    /** Ecriture differee (timers, scans) : faite a la prochaine sauvegarde du monde. */
    public static void markDirty(RanchData r) {
        index(r);
        DIRTY.add(r.id);
    }

    public static void flushDirty() {
        for (UUID id : new ArrayList<>(DIRTY)) {
            RanchData r = ALL.get(id);
            if (r != null) save(r);
        }
        DIRTY.clear();
    }

    /** Pension supprimee : le fichier est deplace dans deleted/ (trace pour le staff). */
    public static void remove(RanchData r) {
        ALL.remove(r.id);
        unindex(r.id);
        DIRTY.remove(r.id);
        Path f = dir.resolve(r.id + ".dat");
        try {
            if (Files.exists(f)) Files.move(f, dir.getParent().resolve("deleted").resolve(r.id + "-" + System.currentTimeMillis() + ".dat"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            CobbleRanch.LOG.warn("[CobblePulse-Ranch] Archivage de la pension {} impossible : {}", r.id, e.toString());
        }
    }

    /** Copie du BlockEntity plus recente que le fichier (fichier perdu ou plus ancien) : elle est reprise. */
    public static boolean adoptMirror(RanchData mirror) {
        RanchData cur = ALL.get(mirror.id);
        if (cur != null && cur.revision >= mirror.revision) return false;
        ALL.put(mirror.id, mirror);
        index(mirror);
        write(mirror);
        CobbleRanch.LOG.warn("[CobblePulse-Ranch] Pension {} restauree depuis la copie du bloc (revision {} > {})", mirror.id, mirror.revision,
                cur == null ? "absente" : String.valueOf(cur.revision));
        return true;
    }

    private static void write(RanchData r) {
        if (dir == null) return;
        Path f = dir.resolve(r.id + ".dat");
        Path tmp = dir.resolve(r.id + ".dat.tmp");
        try {
            NbtIo.writeCompressed(r.save(), tmp);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e2) {
                CobbleRanch.LOG.error("[CobblePulse-Ranch] Sauvegarde de la pension {} impossible", r.id, e2);
            }
        }
    }
}
