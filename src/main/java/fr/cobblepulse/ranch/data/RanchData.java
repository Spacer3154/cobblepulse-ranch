package fr.cobblepulse.ranch.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Une pension (bloc pose dans le monde, ou pension virtuelle du /breed a distance). */
public final class RanchData {
    public UUID id;
    public UUID owner;
    public String ownerName = "";
    public boolean remote;
    public String dimension = "";
    public BlockPos pos = BlockPos.ZERO;
    public long created;
    public long revision;
    /** contournement de fertilite du proprietaire, memorise a sa derniere action (proprietaire hors ligne). */
    public boolean ownerBypassFertility;
    /** bloc introuvable a son emplacement (chunk corrompu, bloc retire sans passer par le jeu). */
    public boolean orphan;
    public final List<SlotData> slots = new ArrayList<>();

    public static RanchData create(UUID owner, String ownerName, boolean remote, int slots) {
        RanchData r = new RanchData();
        r.id = UUID.randomUUID();
        r.owner = owner;
        r.ownerName = ownerName == null ? "" : ownerName;
        r.remote = remote;
        r.created = System.currentTimeMillis();
        r.ensureSlots(slots);
        return r;
    }

    public void ensureSlots(int n) {
        while (slots.size() < n) slots.add(new SlotData());
    }

    public SlotData slot(int i) {
        return i >= 0 && i < slots.size() ? slots.get(i) : null;
    }

    public long chunkKey() {
        return ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public String where() {
        if (remote) return "À distance (/breed)";
        String dim = dimension.startsWith("minecraft:") ? dimension.substring(10) : dimension;
        return dim + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    public boolean active() {
        for (SlotData s : slots) if (!s.empty()) return true;
        return false;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putUUID("id", id);
        if (owner != null) t.putUUID("owner", owner);
        t.putString("owner_name", ownerName);
        t.putBoolean("remote", remote);
        t.putString("dimension", dimension);
        t.putIntArray("pos", new int[]{pos.getX(), pos.getY(), pos.getZ()});
        t.putLong("created", created);
        t.putLong("revision", revision);
        t.putBoolean("bypass_fertility", ownerBypassFertility);
        t.putBoolean("orphan", orphan);
        ListTag l = new ListTag();
        for (SlotData s : slots) l.add(s.save());
        t.put("slots", l);
        t.putInt("format", 1);
        return t;
    }

    public static RanchData load(CompoundTag t) {
        RanchData r = new RanchData();
        r.id = t.getUUID("id");
        r.owner = t.hasUUID("owner") ? t.getUUID("owner") : null;
        r.ownerName = t.getString("owner_name");
        r.remote = t.getBoolean("remote");
        r.dimension = t.getString("dimension");
        int[] p = t.getIntArray("pos");
        r.pos = p.length == 3 ? new BlockPos(p[0], p[1], p[2]) : BlockPos.ZERO;
        r.created = t.getLong("created");
        r.revision = t.getLong("revision");
        r.ownerBypassFertility = t.getBoolean("bypass_fertility");
        r.orphan = t.getBoolean("orphan");
        ListTag l = t.getList("slots", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) r.slots.add(SlotData.load(l.getCompound(i)));
        return r;
    }
}
