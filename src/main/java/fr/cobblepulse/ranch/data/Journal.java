package fr.cobblepulse.ranch.data;

import fr.cobblepulse.ranch.CobbleRanch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Journal des transferts en cours (world/cobbleranch/data/journal.dat). Chaque transfert (depot, retrait, oeuf recupere,
 * eclosion...) y est ecrit AVANT de toucher aux conteneurs, avec une copie complete de ce qui est deplace et son
 * conteneur d'origine ; il est efface une fois les deux conteneurs enregistres sur le disque. Apres un arret brutal, les
 * entrees restantes sont reconciliees (voir Transfers) : jamais de doublon, jamais de perte.
 */
public final class Journal {
    private static final List<CompoundTag> PENDING = new ArrayList<>();
    private static Path file;

    private Journal() {}

    public static synchronized void init(Path dataDir) {
        PENDING.clear();
        file = dataDir.resolve("journal.dat");
        if (!Files.exists(file)) return;
        try {
            ListTag l = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getList("pending", Tag.TAG_COMPOUND);
            for (int i = 0; i < l.size(); i++) PENDING.add(l.getCompound(i));
        } catch (Exception e) {
            CobbleRanch.LOG.error("[CobblePulse-Ranch] journal.dat illisible", e);
        }
    }

    public static synchronized List<CompoundTag> pending() {
        return new ArrayList<>(PENDING);
    }

    /** Ouvre une transaction (ecrite et synchronisee sur le disque avant de continuer). */
    public static synchronized UUID begin(String type, UUID owner, UUID subject, CompoundTag payload, CompoundTag origin, CompoundTag dest) {
        CompoundTag t = new CompoundTag();
        UUID id = UUID.randomUUID();
        t.putUUID("id", id);
        t.putString("type", type);
        if (owner != null) t.putUUID("owner", owner);
        t.putUUID("subject", subject);
        if (payload != null) t.put("payload", payload);
        t.put("origin", origin);
        t.put("dest", dest);
        t.putLong("time", System.currentTimeMillis());
        PENDING.add(t);
        write();
        return id;
    }

    public static synchronized void end(UUID id) {
        if (PENDING.removeIf(t -> t.hasUUID("id") && t.getUUID("id").equals(id))) write();
    }

    public static CompoundTag where(String kind) {
        CompoundTag t = new CompoundTag();
        t.putString("kind", kind);
        return t;
    }

    private static void write() {
        if (file == null) return;
        CompoundTag root = new CompoundTag();
        ListTag l = new ListTag();
        l.addAll(PENDING);
        root.put("pending", l);
        Path tmp = file.resolveSibling("journal.dat.tmp");
        try {
            NbtIo.writeCompressed(root, tmp);
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            CobbleRanch.LOG.error("[CobblePulse-Ranch] Ecriture du journal impossible", e);
        }
    }
}
