package fr.cobblepulse.ranch.service;

import com.cobblemon.mod.common.pokemon.Pokemon;
import fr.cobblepulse.ranch.CobbleRanch;
import fr.cobblepulse.ranch.breeding.EggData;
import fr.cobblepulse.ranch.data.Journal;
import fr.cobblepulse.ranch.data.PlayerStore;
import fr.cobblepulse.ranch.data.RanchData;
import fr.cobblepulse.ranch.data.RanchStore;
import fr.cobblepulse.ranch.data.SlotData;
import fr.cobblepulse.ranch.data.WorldState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/**
 * Reconciliation des transferts interrompus (arret brutal, crash, coupure). Pour chaque entree restee dans le journal,
 * on regarde ou se trouve reellement ce qui etait deplace :
 * <ul>
 *   <li>present dans l'origine ET la destination : la destination est videe (retour a l'origine, aucun doublon) ;</li>
 *   <li>present seulement dans la destination : le transfert etait fini, il est garde ;</li>
 *   <li>present seulement dans l'origine : rien n'avait bouge ;</li>
 *   <li>present nulle part : la copie du journal est remise dans l'origine (aucune perte).</li>
 * </ul>
 * Les entrees qui touchent l'inventaire d'un joueur attendent sa connexion (inventaire charge).
 */
public final class Transfers {
    private Transfers() {}

    /** Au demarrage : tout ce qui ne depend pas d'un inventaire de joueur. */
    public static void recoverAtStartup() {
        int n = 0;
        for (CompoundTag tx : Journal.pending()) {
            if (involvesInventory(tx)) continue;
            try {
                resolve(tx, null);
                n++;
            } catch (Throwable t) {
                CobbleRanch.LOG.error("[CobblePulse-Ranch] Reconciliation impossible ({}), entree gardee", tx.getString("type"), t);
            }
        }
        if (n > 0) CobbleRanch.LOG.warn("[CobblePulse-Ranch] {} transfert(s) interrompu(s) reconcilie(s) au demarrage", n);
    }

    /** A la connexion : entrees qui touchent l'inventaire de ce joueur. */
    public static void recoverFor(ServerPlayer p) {
        for (CompoundTag tx : Journal.pending()) {
            if (!tx.hasUUID("owner") || !tx.getUUID("owner").equals(p.getUUID()) || !involvesInventory(tx)) continue;
            try {
                resolve(tx, p);
            } catch (Throwable t) {
                CobbleRanch.LOG.error("[CobblePulse-Ranch] Reconciliation impossible pour {}", p.getGameProfile().getName(), t);
            }
        }
    }

    private static boolean involvesInventory(CompoundTag tx) {
        return "INVENTORY".equals(tx.getCompound("origin").getString("kind")) || "INVENTORY".equals(tx.getCompound("dest").getString("kind"));
    }

    private static void resolve(CompoundTag tx, ServerPlayer player) {
        UUID id = tx.getUUID("id");
        UUID owner = tx.hasUUID("owner") ? tx.getUUID("owner") : null;
        UUID subject = tx.getUUID("subject");
        String type = tx.getString("type");
        CompoundTag origin = tx.getCompound("origin"), dest = tx.getCompound("dest");
        CompoundTag payload = tx.getCompound("payload");
        if ("HATCH".equals(type)) {
            resolveHatch(tx, player);
            Journal.end(id);
            return;
        }
        boolean inO = contains(origin, owner, subject, player);
        boolean inD = contains(dest, owner, subject, player);
        String outcome;
        if (inO && inD) {
            remove(dest, owner, subject, player);
            outcome = "doublon retire de la destination";
        } else if (inD) {
            outcome = "transfert termine conserve";
        } else if (inO) {
            outcome = "rien n'avait bouge";
        } else {
            insert(origin, owner, payload, player);
            outcome = "copie du journal remise a l'origine";
        }
        CobbleRanch.LOG.warn("[CobblePulse-Ranch] Transfert interrompu {} {} ({} -> {}) : {}", type, subject, origin.getString("kind"), dest.getString("kind"), outcome);
        Journal.end(id);
    }

    /** Eclosion interrompue : le registre des eclosions dit si le Pokemon a ete livre. */
    private static void resolveHatch(CompoundTag tx, ServerPlayer p) {
        UUID egg = tx.getUUID("subject");
        CompoundTag payload = tx.getCompound("payload");
        UUID mon = EggData.pokemonId(payload);
        boolean delivered = mon != null && Stores.owns(p.getUUID(), mon);
        boolean eggThere = findEgg(p, egg) >= 0;
        if (delivered && eggThere) {
            p.getInventory().removeItem(findEgg(p, egg), 1);
            WorldState.markDurable(egg);
            CobbleRanch.LOG.warn("[CobblePulse-Ranch] Eclosion interrompue de {} : oeuf en double retire", egg);
        } else if (delivered) {
            WorldState.markDurable(egg);
        } else if (!eggThere) {
            // ni oeuf ni Pokemon : l'oeuf est rendu (il pourra eclore normalement)
            WorldState.hatchedAll().remove(egg);
            WorldState.save();
            RanchService.returnEgg(p.getUUID(), payload);
            CobbleRanch.LOG.warn("[CobblePulse-Ranch] Eclosion interrompue de {} : oeuf rendu", egg);
        } else {
            // l'oeuf est encore la et le Pokemon absent : l'eclosion n'a pas eu lieu
            WorldState.hatchedAll().remove(egg);
            WorldState.save();
        }
        Stores.savePlayer(p);
    }

    public static int findEgg(ServerPlayer p, UUID egg) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            CompoundTag t = EggData.of(inv.getItem(i));
            if (t != null && egg.equals(EggData.id(t))) return i;
        }
        return -1;
    }

    private static boolean contains(CompoundTag where, UUID owner, UUID subject, ServerPlayer p) {
        switch (where.getString("kind")) {
            case "PC":
                return owner != null && Stores.pc(owner).get(subject) != null;
            case "PARTY":
                return owner != null && Stores.party(owner).get(subject) != null;
            case "RANCH": {
                if (where.getBoolean("egg")) return RanchStore.findEgg(subject) != null;
                return RanchStore.findPokemon(subject) != null;
            }
            case "MAILBOX": {
                PlayerStore.Rec r = owner == null ? null : PlayerStore.peek(owner);
                if (r == null) return false;
                for (CompoundTag t : r.pokemon) if (t.hasUUID("UUID") && t.getUUID("UUID").equals(subject)) return true;
                for (CompoundTag t : r.eggs) if (subject.equals(EggData.id(t))) return true;
                return false;
            }
            case "INVENTORY":
                return p != null && findEgg(p, subject) >= 0;
            default:
                return false;
        }
    }

    private static void remove(CompoundTag where, UUID owner, UUID subject, ServerPlayer p) {
        switch (where.getString("kind")) {
            case "PC" -> {
                Pokemon pk = Stores.pc(owner).get(subject);
                if (pk != null) Stores.pc(owner).remove(pk);
                Stores.flush(Stores.pc(owner));
            }
            case "PARTY" -> {
                Pokemon pk = Stores.party(owner).get(subject);
                if (pk != null) Stores.party(owner).remove(pk);
                Stores.flush(Stores.party(owner));
            }
            case "RANCH" -> {
                RanchData r = RanchStore.get(where.getUUID("ranch"));
                if (r == null) return;
                SlotData s = r.slot(where.getInt("slot"));
                if (s == null) return;
                if (where.getBoolean("egg")) s.eggs.removeIf(e -> subject.equals(EggData.id(e)));
                else for (boolean first : new boolean[]{true, false}) if (subject.equals(s.uuid(first))) s.set(first, null);
                RanchStore.save(r);
            }
            case "MAILBOX" -> {
                PlayerStore.Rec r = PlayerStore.peek(owner);
                if (r == null) return;
                r.pokemon.removeIf(t -> t.hasUUID("UUID") && t.getUUID("UUID").equals(subject));
                r.eggs.removeIf(t -> subject.equals(EggData.id(t)));
                PlayerStore.save();
            }
            case "INVENTORY" -> {
                int i = p == null ? -1 : findEgg(p, subject);
                if (i >= 0) {
                    p.getInventory().removeItem(i, 1);
                    Stores.savePlayer(p);
                }
            }
            default -> {
            }
        }
    }

    private static void insert(CompoundTag where, UUID owner, CompoundTag payload, ServerPlayer p) {
        switch (where.getString("kind")) {
            case "PC", "PARTY", "MAILBOX" -> {
                if (payload.hasUUID("id") && payload.contains("pokemon")) {
                    RanchService.returnEgg(owner, payload);
                    return;
                }
                Pokemon pk = new Pokemon().loadFromNBT(CobbleRanch.registries(), payload.copy());
                RanchService.returnPokemon(owner, pk, "PARTY".equals(where.getString("kind")));
            }
            case "RANCH" -> {
                RanchData r = RanchStore.get(where.getUUID("ranch"));
                SlotData s = r == null ? null : r.slot(where.getInt("slot"));
                if (where.getBoolean("egg")) {
                    if (s != null) {
                        s.eggs.add(payload.copy());
                        RanchStore.save(r);
                    } else RanchService.returnEgg(owner, payload);
                    return;
                }
                boolean first = where.getBoolean("first");
                if (s != null && !s.has(first)) {
                    Pokemon pk = new Pokemon().loadFromNBT(CobbleRanch.registries(), payload.copy());
                    s.set(first, pk);
                    RanchStore.save(r);
                } else {
                    RanchService.returnPokemon(owner, new Pokemon().loadFromNBT(CobbleRanch.registries(), payload.copy()), false);
                }
            }
            case "INVENTORY" -> {
                if (p != null && p.getInventory().add(EggData.toStack(payload))) Stores.savePlayer(p);
                else RanchService.returnEgg(owner, payload);
            }
            default -> {
            }
        }
    }

    /**
     * Oeufs d'un joueur qui se connecte : un oeuf deja eclos (registre definitif) est un doublon et disparait ; un
     * oeuf encore present dans une Pension aussi est retire de la Pension (le joueur l'a).
     */
    public static void auditInventory(ServerPlayer p) {
        var inv = p.getInventory();
        boolean changed = false;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            CompoundTag t = EggData.of(st);
            if (t == null) continue;
            UUID egg = EggData.id(t);
            WorldState.Hatch h = WorldState.hatched(egg);
            if (h != null && h.durable) {
                inv.setItem(i, ItemStack.EMPTY);
                changed = true;
                CobbleRanch.LOG.warn("[CobblePulse-Ranch] Anti-duplication : oeuf deja eclos {} retire de l'inventaire de {}", egg, p.getGameProfile().getName());
                continue;
            }
            RanchStore.Where w = RanchStore.findEgg(egg);
            if (w != null) {
                RanchData r = RanchStore.get(w.ranch());
                SlotData s = r == null ? null : r.slot(w.slot());
                if (s != null && s.eggs.removeIf(e -> egg.equals(EggData.id(e)))) {
                    RanchStore.save(r);
                    CobbleRanch.LOG.warn("[CobblePulse-Ranch] Anti-duplication : oeuf {} present chez {} et dans une Pension, copie de la Pension retiree",
                            egg, p.getGameProfile().getName());
                }
            }
        }
        if (changed) Stores.savePlayer(p);
    }
}
