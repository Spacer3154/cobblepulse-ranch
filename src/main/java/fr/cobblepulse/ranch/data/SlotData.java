package fr.cobblepulse.ranch.data;

import com.cobblemon.mod.common.pokemon.Pokemon;
import fr.cobblepulse.ranch.CobbleRanch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Un emplacement de reproduction : deux parents (NBT Cobblemon complet, aucune entite dans le monde), l'horodatage de
 * debut du cycle en cours (timestamp_start, 0 = inactif) et sa duree (duration_ms), les oeufs en attente et le dernier
 * resultat du scan d'habitat.
 */
public final class SlotData {
    private CompoundTag a, b;
    private transient Pokemon pa, pb;
    public long start;
    public long duration;
    public final List<CompoundTag> eggs = new ArrayList<>();
    /** raison de la pause ("" = en cours ou inactif sans raison). */
    public String pause = "";
    public long eggsLaid;
    // habitat (dernier scan)
    public int score = -1;
    public String group = "";
    public long scanAt;
    public CompoundTag habitat = new CompoundTag();

    public CompoundTag nbt(boolean first) {
        return first ? a : b;
    }

    public boolean has(boolean first) {
        return (first ? a : b) != null;
    }

    public boolean full() {
        return a != null && b != null;
    }

    public boolean empty() {
        return a == null && b == null && eggs.isEmpty();
    }

    /** Pokemon reconstruit depuis le NBT (cache). */
    public Pokemon pokemon(boolean first) {
        CompoundTag t = first ? a : b;
        if (t == null) return null;
        Pokemon p = first ? pa : pb;
        if (p == null) {
            try {
                p = new Pokemon().loadFromNBT(CobbleRanch.registries(), t.copy());
            } catch (Throwable e) {
                CobbleRanch.LOG.error("[CobblePulse-Ranch] Pokemon illisible dans une pension", e);
                return null;
            }
            if (first) pa = p;
            else pb = p;
        }
        return p;
    }

    public UUID uuid(boolean first) {
        CompoundTag t = first ? a : b;
        if (t == null) return null;
        Pokemon p = pokemon(first);
        return p != null ? p.getUuid() : (t.hasUUID("UUID") ? t.getUUID("UUID") : null);
    }

    public void set(boolean first, Pokemon p) {
        CompoundTag t = p == null ? null : p.saveToNBT(CobbleRanch.registries(), new CompoundTag());
        if (first) {
            a = t;
            pa = p;
        } else {
            b = t;
            pb = p;
        }
    }

    /** Re-serialise un parent modifie (fertilite, capacites apprises par l'Herbe Miroir). */
    public void refresh(boolean first) {
        Pokemon p = pokemon(first);
        if (p != null) set(first, p);
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        if (a != null) t.put("parent_a", a);
        if (b != null) t.put("parent_b", b);
        t.putLong("timestamp_start", start);
        t.putLong("duration_ms", duration);
        ListTag l = new ListTag();
        for (CompoundTag e : eggs) l.add(e);
        t.put("eggs", l);
        t.putString("pause", pause);
        t.putLong("eggs_laid", eggsLaid);
        t.putInt("score", score);
        t.putString("group", group);
        t.putLong("scan_at", scanAt);
        t.put("habitat", habitat);
        return t;
    }

    public static SlotData load(CompoundTag t) {
        SlotData s = new SlotData();
        if (t.contains("parent_a", Tag.TAG_COMPOUND)) s.a = t.getCompound("parent_a");
        if (t.contains("parent_b", Tag.TAG_COMPOUND)) s.b = t.getCompound("parent_b");
        s.start = t.getLong("timestamp_start");
        s.duration = t.getLong("duration_ms");
        ListTag l = t.getList("eggs", Tag.TAG_COMPOUND);
        for (int i = 0; i < l.size(); i++) s.eggs.add(l.getCompound(i));
        s.pause = t.getString("pause");
        s.eggsLaid = t.getLong("eggs_laid");
        s.score = t.contains("score") ? t.getInt("score") : -1;
        s.group = t.getString("group");
        s.scanAt = t.getLong("scan_at");
        s.habitat = t.getCompound("habitat");
        return s;
    }
}
