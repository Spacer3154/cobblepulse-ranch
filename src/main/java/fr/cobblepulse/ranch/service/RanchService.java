package fr.cobblepulse.ranch.service;

import com.cobblemon.mod.common.api.storage.PokemonStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import fr.cobblepulse.ranch.CobbleRanch;
import fr.cobblepulse.ranch.block.RanchBlockEntity;
import fr.cobblepulse.ranch.breeding.Compatibility;
import fr.cobblepulse.ranch.breeding.EggData;
import fr.cobblepulse.ranch.breeding.Genetics;
import fr.cobblepulse.ranch.breeding.Habitat;
import fr.cobblepulse.ranch.breeding.Traits;
import fr.cobblepulse.ranch.config.ConfigManager;
import fr.cobblepulse.ranch.config.RanchConfig;
import fr.cobblepulse.ranch.data.Journal;
import fr.cobblepulse.ranch.data.PlayerStore;
import fr.cobblepulse.ranch.data.RanchData;
import fr.cobblepulse.ranch.data.RanchStore;
import fr.cobblepulse.ranch.data.SlotData;
import fr.cobblepulse.ranch.net.RanchNet;
import fr.cobblepulse.ranch.net.Views;
import fr.cobblepulse.ranch.perm.Perms;
import fr.cobblepulse.ranch.registry.ModContent;
import fr.cobblepulse.ranch.util.Msg;
import fr.cobblepulse.ranch.util.Names;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Logique des pensions, entierement cote serveur. L'avancement n'est jamais calcule par un tick : il est evalue a la
 * demande (ouverture, depot/retrait, sablier, commande, rafraichissement de l'ecran) a partir de timestamp_start et
 * duration_ms. Chaque transfert de Pokemon ou d'oeuf passe par le journal (voir Transfers).
 */
public final class RanchService {
    public static final Random RNG = new Random();
    /** acces a distance (/daycare, VIP) : joueur -> pension -> fin de validite. */
    private static final Map<UUID, Map<UUID, Long>> REMOTE = new HashMap<>();
    private static final long REMOTE_MS = 10 * 60_000L;
    private static final double REACH = 8.0;

    private RanchService() {}

    private static RanchConfig cfg() {
        return ConfigManager.get();
    }

    public static String dim(Level level) {
        return level.dimension().location().toString();
    }

    public static ServerLevel level(RanchData r) {
        ResourceLocation rl = ResourceLocation.tryParse(r.dimension);
        return rl == null ? null : CobbleRanch.server().getLevel(ResourceKey.create(Registries.DIMENSION, rl));
    }

    // =====================================================================================================placement

    public static boolean canPlace(ServerPlayer p, Level level, BlockPos pos) {
        if (!Perms.has(p, Perms.USE)) {
            Msg.err(p, "Tu n'as pas la permission d'utiliser les Pensions.");
            return false;
        }
        if (Perms.has(p, Perms.BYPASS_CHUNK)) return true;
        RanchConfig.General g = cfg().general_settings;
        if (g.max_ranch_blocks_per_chunk >= 0
                && RanchStore.countInChunk(dim(level), ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4)) >= g.max_ranch_blocks_per_chunk) {
            Msg.err(p, "Limite atteinte : " + g.max_ranch_blocks_per_chunk + " Pension(s) maximum par chunk.");
            return false;
        }
        if (g.max_ranch_blocks_per_player >= 0 && RanchStore.countBlocksOf(p.getUUID()) >= g.max_ranch_blocks_per_player) {
            Msg.err(p, "Limite atteinte : " + g.max_ranch_blocks_per_player + " Pension(s) maximum par dresseur.");
            return false;
        }
        return true;
    }

    public static void onPlaced(ServerPlayer p, ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof RanchBlockEntity be)) return;
        RanchData r = create(p, level, pos);
        be.link(r);
        Msg.ok(p, "Pension posée. Clic droit pour y déposer deux Pokémon de ton PC.");
    }

    private static RanchData create(ServerPlayer p, ServerLevel level, BlockPos pos) {
        RanchData r = RanchData.create(p.getUUID(), p.getGameProfile().getName(), false, cfg().general_settings.slots_per_ranch_block);
        r.dimension = dim(level);
        r.pos = pos.immutable();
        r.ownerBypassFertility = Perms.has(p, Perms.BYPASS_FERTILITY);
        RanchStore.add(r);
        CobbleRanch.LOG.info("[CobblePulse-Ranch] Pension {} posee par {} en {}", r.id, p.getGameProfile().getName(), r.where());
        return r;
    }

    /** Bloc retire (casse, /setblock, WorldEdit...) : tout ce qu'il contient est rendu au proprietaire. */
    public static void onBlockRemoved(ServerLevel level, BlockPos pos, RanchBlockEntity be) {
        RanchData r = RanchStore.get(be.ranchId());
        if (r == null || r.remote || !r.pos.equals(pos) || !r.dimension.equals(dim(level))) return;
        int[] n = restituteAll(r, "bloc retiré");
        RanchStore.remove(r);
        be.unlink();
        CobbleRanch.LOG.info("[CobblePulse-Ranch] Pension {} retiree ({}) : {} Pokemon et {} oeuf(s) rendus a {}", r.id, r.where(), n[0], n[1], r.ownerName);
        ServerPlayer owner = CobbleRanch.server().getPlayerList().getPlayer(r.owner);
        if (owner != null && (n[0] > 0 || n[1] > 0))
            Msg.info(owner, "Pension retirée : " + n[0] + " Pokémon renvoyé(s) au PC, " + n[1] + " œuf(s) rendu(s).");
    }

    /**
     * Chargement d'un chunk : copie du bloc plus recente que le fichier (fichier perdu) reprise ; copie d'une pension
     * qui est ailleurs (WorldEdit, structure) videe pour empecher toute duplication ; pension dont le bloc a disparu
     * marquee orpheline (ses Pokemon restent recuperables).
     */
    public static void onChunkLoad(ServerLevel level, LevelChunk chunk) {
        String d = dim(level);
        long key = chunk.getPos().toLong();
        List<RanchBlockEntity> bes = new ArrayList<>();
        for (BlockEntity be : chunk.getBlockEntities().values()) if (be instanceof RanchBlockEntity rb) bes.add(rb);
        for (RanchBlockEntity be : bes) {
            UUID id = be.ranchId();
            if (id == null) continue;
            CompoundTag m = be.mirror();
            RanchData r = RanchStore.get(id);
            if (m != null) {
                try {
                    RanchData mirror = RanchData.load(m);
                    if (mirror.pos.equals(be.getBlockPos()) && mirror.dimension.equals(d) && RanchStore.adoptMirror(mirror)) r = RanchStore.get(id);
                } catch (Exception e) {
                    CobbleRanch.LOG.warn("[CobblePulse-Ranch] Copie de pension illisible en {} : {}", be.getBlockPos(), e.toString());
                }
            }
            if (r == null) continue;
            if (!r.pos.equals(be.getBlockPos()) || !r.dimension.equals(d)) {
                CobbleRanch.LOG.warn("[CobblePulse-Ranch] Copie de la pension {} detectee en {} {} (originale en {}) : copie videe (anti-duplication)",
                        id, d, be.getBlockPos().toShortString(), r.where());
                be.unlink();
                continue;
            }
            if (r.orphan) {
                r.orphan = false;
                RanchStore.markDirty(r);
            }
        }
        for (RanchData r : RanchStore.inChunk(d, key)) {
            if (r.orphan) continue;
            if (!(chunk.getBlockEntity(r.pos) instanceof RanchBlockEntity be) || !r.id.equals(be.ranchId())) {
                r.orphan = true;
                RanchStore.save(r);
                CobbleRanch.LOG.warn("[CobblePulse-Ranch] Pension {} de {} : bloc introuvable en {} (orpheline, contenu conserve)", r.id, r.ownerName, r.where());
            }
        }
    }

    // =====================================================================================================acces

    public static void openBlock(ServerPlayer p, ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof RanchBlockEntity be)) return;
        if (!Perms.has(p, Perms.USE)) {
            Msg.err(p, "Tu n'as pas la permission d'utiliser les Pensions.");
            return;
        }
        RanchData r = RanchStore.get(be.ranchId());
        if (r == null || !r.pos.equals(pos)) {
            // bloc pose par commande ou structure : le premier dresseur qui l'ouvre en devient proprietaire
            if (be.owner() != null && !be.owner().equals(p.getUUID())) {
                Msg.err(p, "Cette Pension n'est pas à toi.");
                return;
            }
            if (!canPlace(p, level, pos)) return;
            r = create(p, level, pos);
            be.link(r);
        }
        if (!p.getUUID().equals(r.owner)) {
            Msg.err(p, "Cette Pension appartient à " + r.ownerName + ".");
            return;
        }
        open(p, r);
    }

    /** Le joueur peut-il agir sur cette pension (proprietaire + a portee, acces a distance, ou pension virtuelle) ? */
    public static boolean canAct(ServerPlayer p, RanchData r) {
        if (r == null || !p.getUUID().equals(r.owner)) return false;
        if (r.remote || r.orphan) return true;
        Map<UUID, Long> m = REMOTE.get(p.getUUID());
        if (m != null) {
            Long until = m.get(r.id);
            if (until != null && until > System.currentTimeMillis()) return true;
        }
        return dim(p.level()).equals(r.dimension) && p.position().distanceToSqr(r.pos.getCenter()) <= REACH * REACH;
    }

    public static void grantRemote(ServerPlayer p, RanchData r) {
        REMOTE.computeIfAbsent(p.getUUID(), k -> new HashMap<>()).put(r.id, System.currentTimeMillis() + REMOTE_MS);
    }

    public static void forget(UUID player) {
        REMOTE.remove(player);
    }

    public static void open(ServerPlayer p, RanchData r) {
        r.ownerBypassFertility = Perms.has(p, Perms.BYPASS_FERTILITY);
        refresh(r, false);
        RanchNet.send(p, "ranch", Views.ranch(p, r, null));
    }

    /** Evaluation a la demande (+ scan si le delai de 2 s est passe). */
    public static void refresh(RanchData r, boolean forceScan) {
        long now = System.currentTimeMillis();
        boolean laid = evaluate(r, now);
        boolean scanned = false;
        for (SlotData s : r.slots) scanned |= scan(r, s, forceScan, now);
        if (scanned) laid |= evaluate(r, now);
        if (laid) save(r);
        else RanchStore.markDirty(r);
    }

    /** Ecriture immediate + mise a jour de la copie du bloc. */
    public static void save(RanchData r) {
        RanchStore.save(r);
        if (r.remote) return;
        ServerLevel l = level(r);
        if (l != null && l.isLoaded(r.pos) && l.getBlockEntity(r.pos) instanceof RanchBlockEntity be) be.setChanged();
    }

    // =====================================================================================================evaluation

    private static long baseDuration(RanchData r, SlotData s) {
        if (r.remote) return cfg().remote_breed.duration_seconds * 1000L;
        return Habitat.durationMs(Math.max(0, s.score));
    }

    /** Fait avancer les cycles termines (pontes) ; true si un oeuf a ete pondu. */
    public static boolean evaluate(RanchData r, long now) {
        boolean laid = false;
        int cap = cfg().general_settings.max_eggs_per_slot;
        for (SlotData s : r.slots) {
            if (r.orphan) {
                s.start = 0;
                s.pause = "Bloc introuvable : récupère tes Pokémon";
                continue;
            }
            if (!s.full()) {
                s.start = 0;
                s.pause = s.has(true) || s.has(false) ? "Il manque un parent" : "";
                continue;
            }
            Pokemon a = s.pokemon(true), b = s.pokemon(false);
            Compatibility.Check c = Compatibility.check(a, b, r.ownerBypassFertility);
            if (!c.ok()) {
                s.start = 0;
                s.pause = c.reason();
                continue;
            }
            if (s.duration <= 0) s.duration = baseDuration(r, s);
            if (s.start <= 0) {
                if (s.eggs.size() >= cap) {
                    s.pause = "Œuf à récupérer";
                    continue;
                }
                s.start = now;
                s.pause = "";
            }
            int guard = 0;
            while (s.start > 0 && now >= s.start + s.duration && guard++ < 64) {
                if (s.eggs.size() >= cap) {
                    s.start = 0;
                    s.pause = "Œuf à récupérer";
                    break;
                }
                c = Compatibility.check(a, b, r.ownerBypassFertility);
                if (!c.ok()) {
                    s.start = 0;
                    s.pause = c.reason();
                    break;
                }
                long layAt = s.start + s.duration;
                lay(r, s, c);
                laid = true;
                if (s.eggs.size() < cap) s.start = layAt;
                else {
                    s.start = 0;
                    s.pause = "Œuf à récupérer";
                }
            }
        }
        return laid;
    }

    /** Ponte : l'oeuf et toute sa genetique sont generes et figes maintenant. */
    public static CompoundTag lay(RanchData r, SlotData s, Compatibility.Check c) {
        Genetics.Result res = Genetics.breed(c, RNG);
        String parents = label(s.pokemon(true)) + " x " + label(s.pokemon(false));
        CompoundTag egg = EggData.build(res.child(), res, parents);
        s.eggs.add(egg);
        s.eggsLaid++;
        boolean changed = res.parentsChanged();
        if (Traits.fertilityEnabled() && !r.ownerBypassFertility) {
            for (boolean first : new boolean[]{true, false}) {
                Pokemon p = s.pokemon(first);
                Traits.setFertility(p, Traits.fertility(p) - 1);
            }
            changed = true;
        }
        if (changed) {
            s.refresh(true);
            s.refresh(false);
        }
        if (!res.notes().isEmpty()) s.habitat.putString("last_notes", String.join(" ; ", res.notes()));
        CobbleRanch.LOG.info("[CobblePulse-Ranch] Ponte ({} / {}) : {} {}{} [{}]", r.ownerName, r.where(), EggData.info(egg).getString("species_name"),
                EggData.info(egg).getString("form"), EggData.info(egg).getBoolean("shiny") ? " CHROMATIQUE" : "", parents);
        return egg;
    }

    public static String label(Pokemon p) {
        if (p == null) return "?";
        String g = switch (p.getGender()) {
            case MALE -> " ♂";
            case FEMALE -> " ♀";
            default -> "";
        };
        return Names.species(p.getSpecies()) + g;
    }

    /** Scan d'habitat (pensions posees seulement, chunk charge, delai de scan_refresh_cooldown_seconds). */
    public static boolean scan(RanchData r, SlotData s, boolean force, long now) {
        if (r.remote || r.orphan) return false;
        if (!s.has(true) && !s.has(false)) return false;
        long cd = cfg().environment_5x5.scan_refresh_cooldown_seconds * 1000L;
        if (!force && s.score >= 0 && now - s.scanAt < cd) return false;
        ServerLevel level = level(r);
        if (level == null || !level.isLoaded(r.pos)) return false;
        var groups = groupsFor(s);
        Habitat.Result res = Habitat.scan(level, r.pos, groups);
        s.score = res.score();
        s.group = res.group();
        String notes = s.habitat.getString("last_notes");
        s.habitat = res.save();
        if (!notes.isEmpty()) s.habitat.putString("last_notes", notes);
        s.scanAt = now;
        long nd = Habitat.durationMs(res.score());
        if (s.start > 0 && s.duration > 0 && nd != s.duration) {
            double progress = Math.max(0, Math.min(1, (now - s.start) / (double) s.duration));
            s.start = now - Math.round(progress * nd);
        }
        s.duration = nd;
        return true;
    }

    private static java.util.Set<com.cobblemon.mod.common.api.pokemon.egg.EggGroup> groupsFor(SlotData s) {
        Pokemon a = s.pokemon(true), b = s.pokemon(false);
        if (a != null && b != null) {
            Compatibility.Check c = Compatibility.check(a, b, true);
            if (c.ok()) return c.groups();
        }
        var g = java.util.EnumSet.noneOf(com.cobblemon.mod.common.api.pokemon.egg.EggGroup.class);
        for (Pokemon p : new Pokemon[]{a, b}) if (p != null) g.addAll(Traits.eggGroups(p));
        g.remove(com.cobblemon.mod.common.api.pokemon.egg.EggGroup.UNDISCOVERED);
        g.remove(com.cobblemon.mod.common.api.pokemon.egg.EggGroup.DITTO);
        return g;
    }

    // =====================================================================================================actions joueur

    private static RanchData actionTarget(ServerPlayer p, UUID ranchId, int slot) {
        RanchData r = RanchStore.get(ranchId);
        if (r == null) {
            Msg.err(p, "Cette Pension n'existe plus.");
            return null;
        }
        if (!canAct(p, r)) {
            Msg.err(p, "Tu es trop loin de cette Pension (ou elle n'est pas à toi).");
            return null;
        }
        if (r.slot(slot) == null) return null;
        return r;
    }

    private static PokemonStore<?> sourceStore(ServerPlayer p, RanchData r) {
        return r.remote ? Stores.party(p) : Stores.pc(p);
    }

    /** Depot d'un Pokemon du PC (ou de l'equipe pour la pension a distance) dans un emplacement. */
    public static String deposit(ServerPlayer p, UUID ranchId, int slot, boolean first, UUID pokemonId) {
        RanchData r = actionTarget(p, ranchId, slot);
        if (r == null) return "Action impossible";
        if (r.orphan) return "Bloc introuvable : dépôt impossible";
        SlotData s = r.slot(slot);
        PokemonStore<?> src = sourceStore(p, r);
        Pokemon pk = src.get(pokemonId);
        if (pk == null) return r.remote ? "Ce Pokémon n'est plus dans ton équipe" : "Ce Pokémon n'est plus dans ton PC";
        if (pk.getState() instanceof com.cobblemon.mod.common.pokemon.activestate.ActivePokemonState || pk.getEntity() != null)
            return "Rappelle d'abord ce Pokémon";
        if (com.cobblemon.mod.common.battles.BattleRegistry.INSTANCE.getBattleByParticipatingPlayer(p) != null) return "Impossible pendant un combat";
        r.ownerBypassFertility = Perms.has(p, Perms.BYPASS_FERTILITY);
        String block = Compatibility.blockReason(pk, r.ownerBypassFertility);
        if (!block.isEmpty()) return block;
        Pokemon other = s.pokemon(!first);
        if (other != null) {
            Compatibility.Check c = Compatibility.check(first ? pk : other, first ? other : pk, r.ownerBypassFertility);
            if (!c.ok()) return c.reason();
        }
        if (RanchStore.findPokemon(pk.getUuid()) != null) return "Ce Pokémon est déjà dans une Pension";
        // remplacement : l'ancien parent repart d'abord
        if (s.has(first)) {
            String w = withdraw(p, ranchId, slot, first);
            if (w != null) return w;
        }
        CompoundTag origin = Journal.where(r.remote ? "PARTY" : "PC");
        CompoundTag dest = Journal.where("RANCH");
        dest.putUUID("ranch", r.id);
        dest.putInt("slot", slot);
        dest.putBoolean("first", first);
        UUID tx = Journal.begin("DEPOSIT", p.getUUID(), pk.getUuid(), pk.saveToNBT(CobbleRanch.registries(), new CompoundTag()), origin, dest);
        if (!src.remove(pk)) {
            Journal.end(tx);
            return "Retrait du PC impossible";
        }
        if (Traits.fertilityEnabled() && (!pk.getPersistentData().contains(Traits.KEY) || !pk.getPersistentData().getCompound(Traits.KEY).contains("fertility")))
            Traits.setFertility(pk, Traits.fertility(pk));
        s.set(first, pk);
        s.start = 0;
        s.duration = 0;
        s.score = -1;
        refresh(r, true);
        save(r);
        Stores.flush(src);
        Journal.end(tx);
        CobbleRanch.LOG.info("[CobblePulse-Ranch] Depot {} {} -> pension {} emplacement {}", p.getGameProfile().getName(), label(pk), r.id, slot + 1);
        return null;
    }

    /** Retrait d'un parent vers le PC (equipe pour la pension a distance) ; boite de reception si plein. */
    public static String withdraw(ServerPlayer p, UUID ranchId, int slot, boolean first) {
        RanchData r = actionTarget(p, ranchId, slot);
        if (r == null) return "Action impossible";
        SlotData s = r.slot(slot);
        Pokemon pk = s.pokemon(first);
        if (pk == null) return "Emplacement vide";
        if (Stores.owns(r.owner, pk.getUuid())) {
            CobbleRanch.LOG.error("[CobblePulse-Ranch] Anti-duplication : {} est deja dans le PC/equipe de {}, retrait annule", pk.getUuid(), r.ownerName);
            return "Ce Pokémon existe déjà dans ton PC : contacte le staff";
        }
        refresh(r, false);
        String dest = r.remote ? "PARTY" : "PC";
        UUID tx = Journal.begin("WITHDRAW", r.owner, pk.getUuid(), s.nbt(first).copy(), ranchWhere(r, slot, first), Journal.where(dest));
        s.set(first, null);
        s.start = 0;
        s.duration = 0;
        boolean ok = Stores.give(r.owner, pk, r.remote);
        if (!ok) {
            PlayerStore.get(r.owner).pokemon.add(pk.saveToNBT(CobbleRanch.registries(), new CompoundTag()));
            PlayerStore.save();
            Msg.info(p, "PC plein : " + label(pk) + " t'attend dans ta boîte de réception (reconnexion ou /daycare).");
        }
        save(r);
        Stores.flushOwner(r.owner);
        Journal.end(tx);
        CobbleRanch.LOG.info("[CobblePulse-Ranch] Retrait {} {} <- pension {} emplacement {}", p.getGameProfile().getName(), label(pk), r.id, slot + 1);
        return null;
    }

    static CompoundTag ranchWhere(RanchData r, int slot, boolean first) {
        CompoundTag w = Journal.where("RANCH");
        w.putUUID("ranch", r.id);
        w.putInt("slot", slot);
        w.putBoolean("first", first);
        return w;
    }

    /** Recuperation des oeufs d'un emplacement (un par une, inventaire). */
    public static String collect(ServerPlayer p, UUID ranchId, int slot) {
        RanchData r = actionTarget(p, ranchId, slot);
        if (r == null) return "Action impossible";
        refresh(r, false);
        SlotData s = r.slot(slot);
        if (s.eggs.isEmpty()) return "Aucun œuf à récupérer";
        int got = 0;
        while (!s.eggs.isEmpty()) {
            if (p.getInventory().getFreeSlot() < 0) {
                if (got == 0) return "Inventaire plein";
                break;
            }
            CompoundTag egg = s.eggs.get(0);
            UUID eggId = EggData.id(egg);
            CompoundTag origin = ranchWhere(r, slot, false);
            origin.putBoolean("egg", true);
            UUID tx = Journal.begin("COLLECT", r.owner, eggId, egg.copy(), origin, Journal.where("INVENTORY"));
            s.eggs.remove(0);
            ItemStack st = EggData.toStack(egg);
            if (!p.getInventory().add(st)) {
                s.eggs.add(0, egg);
                Journal.end(tx);
                break;
            }
            save(r);
            Stores.savePlayer(p);
            Journal.end(tx);
            got++;
            CobbleRanch.LOG.info("[CobblePulse-Ranch] Oeuf {} ({}) recupere par {}", eggId, EggData.info(egg).getString("species_name"), p.getGameProfile().getName());
        }
        if (got > 0) {
            p.level().playSound(null, p.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.6f, 1.2f);
            refresh(r, false);
            save(r);
        }
        return null;
    }

    /** Sablier sur un emplacement (argent : reduction, or : ponte immediate). */
    public static String hourglass(ServerPlayer p, UUID ranchId, int slot, boolean gold) {
        RanchData r = actionTarget(p, ranchId, slot);
        if (r == null) return "Action impossible";
        if (!Perms.has(p, gold ? Perms.HOURGLASS_GOLD : Perms.HOURGLASS_SILVER)) return "Tu n'as pas la permission d'utiliser ce sablier";
        Item item = gold ? ModContent.GOLD_HOURGLASS : ModContent.SILVER_HOURGLASS;
        int invSlot = findItem(p, item);
        if (invSlot < 0) return "Tu n'as pas de " + (gold ? "Sablier d'Or" : "Sablier d'Argent");
        refresh(r, false);
        SlotData s = r.slot(slot);
        if (s.start <= 0) return s.pause.isEmpty() ? "Aucune ponte en cours dans cet emplacement" : s.pause;
        long now = System.currentTimeMillis();
        long remaining = s.start + s.duration - now;
        if (remaining <= 0) return "La ponte est déjà terminée";
        RanchConfig.Hatching h = cfg().hatching_and_items;
        if (gold) {
            if (!h.gold_hourglass_instant_breed) return "Le Sablier d'Or est désactivé pour les Pensions";
            s.start = now - s.duration;
        } else {
            long cut = "FIXED".equalsIgnoreCase(h.silver_hourglass_reduction_mode) ? Math.round(h.silver_hourglass_value * 60_000)
                    : Math.round(remaining * h.silver_hourglass_value / 100.0);
            s.start -= Math.max(1000, cut);
        }
        p.getInventory().getItem(invSlot).shrink(1);
        evaluate(r, now);
        save(r);
        p.level().playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, gold ? 1.6f : 1.1f);
        CobbleRanch.LOG.info("[CobblePulse-Ranch] Sablier {} utilise par {} sur la pension {} emplacement {}", gold ? "d'or" : "d'argent",
                p.getGameProfile().getName(), r.id, slot + 1);
        return null;
    }

    public static int findItem(ServerPlayer p, Item item) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(item)) return i;
        return -1;
    }

    public static int count(ServerPlayer p, Item item) {
        int n = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(item)) n += inv.getItem(i).getCount();
        return n;
    }

    // =====================================================================================================restitution

    /** Rend un Pokemon a son proprietaire (PC, ou equipe d'abord), boite de reception si tout est plein. */
    public static void returnPokemon(UUID owner, Pokemon pk, boolean partyFirst) {
        if (Stores.owns(owner, pk.getUuid())) {
            CobbleRanch.LOG.error("[CobblePulse-Ranch] Anti-duplication : {} deja present chez {}, non rendu", pk.getUuid(), owner);
            return;
        }
        if (!Stores.give(owner, pk, partyFirst)) {
            PlayerStore.get(owner).pokemon.add(pk.saveToNBT(CobbleRanch.registries(), new CompoundTag()));
            PlayerStore.save();
        }
        Stores.flushOwner(owner);
    }

    /** Rend un oeuf : inventaire si le joueur est en ligne avec de la place, sinon boite de reception. */
    public static void returnEgg(UUID owner, CompoundTag egg) {
        ServerPlayer p = CobbleRanch.server().getPlayerList().getPlayer(owner);
        if (p != null && p.getInventory().getFreeSlot() >= 0 && p.getInventory().add(EggData.toStack(egg))) {
            Stores.savePlayer(p);
            return;
        }
        PlayerStore.get(owner).eggs.add(egg.copy());
        PlayerStore.save();
    }

    /** Vide un emplacement en rendant parents et oeufs au proprietaire. {pokemon, oeufs}. */
    public static int[] restituteSlot(RanchData r, int slot, String why) {
        SlotData s = r.slot(slot);
        int[] n = {0, 0};
        if (s == null) return n;
        for (boolean first : new boolean[]{true, false}) {
            Pokemon pk = s.pokemon(first);
            if (pk == null) continue;
            UUID tx = Journal.begin("RESTITUTE", r.owner, pk.getUuid(), s.nbt(first).copy(), ranchWhere(r, slot, first), Journal.where(r.remote ? "PARTY" : "PC"));
            s.set(first, null);
            save(r);
            returnPokemon(r.owner, pk, r.remote);
            Journal.end(tx);
            n[0]++;
        }
        while (!s.eggs.isEmpty()) {
            CompoundTag egg = s.eggs.remove(0);
            CompoundTag origin = ranchWhere(r, slot, false);
            origin.putBoolean("egg", true);
            UUID tx = Journal.begin("RESTITUTE_EGG", r.owner, EggData.id(egg), egg.copy(), origin, Journal.where("MAILBOX"));
            save(r);
            returnEgg(r.owner, egg);
            Journal.end(tx);
            n[1]++;
        }
        s.start = 0;
        s.duration = 0;
        s.pause = "";
        save(r);
        if (n[0] + n[1] > 0) CobbleRanch.LOG.info("[CobblePulse-Ranch] Emplacement {} de la pension {} restitue ({}) : {} Pokemon, {} oeuf(s)", slot + 1, r.id, why, n[0], n[1]);
        return n;
    }

    public static int[] restituteAll(RanchData r, String why) {
        int[] t = {0, 0};
        for (int i = 0; i < r.slots.size(); i++) {
            int[] n = restituteSlot(r, i, why);
            t[0] += n[0];
            t[1] += n[1];
        }
        return t;
    }

    /** Suppression (moderation) : parents et oeufs detruits, copie gardee dans deleted/ pour le staff. */
    public static int[] deleteSlot(RanchData r, int slot, String by) {
        SlotData s = r.slot(slot);
        int[] n = {0, 0};
        if (s == null) return n;
        CompoundTag archive = new CompoundTag();
        archive.put("slot", s.save());
        archive.putString("by", by);
        archive.putLong("time", System.currentTimeMillis());
        archive.putUUID("ranch", r.id);
        try {
            java.nio.file.Path f = RanchStore.dataDir().resolve("deleted").resolve("slot-" + r.id + "-" + (slot + 1) + "-" + System.currentTimeMillis() + ".dat");
            net.minecraft.nbt.NbtIo.writeCompressed(archive, f);
        } catch (Exception e) {
            CobbleRanch.LOG.warn("[CobblePulse-Ranch] Archive de suppression impossible : {}", e.toString());
        }
        for (boolean first : new boolean[]{true, false}) if (s.has(first)) n[0]++;
        n[1] = s.eggs.size();
        s.set(true, null);
        s.set(false, null);
        s.eggs.clear();
        s.start = 0;
        s.duration = 0;
        s.pause = "";
        save(r);
        CobbleRanch.LOG.warn("[CobblePulse-Ranch] Emplacement {} de la pension {} ({}) SUPPRIME par {} : {} Pokemon, {} oeuf(s)", slot + 1, r.id, r.ownerName, by, n[0], n[1]);
        return n;
    }

    // =====================================================================================================admin

    public static String forceLay(RanchData r, int slot) {
        SlotData s = r.slot(slot);
        if (s == null || !s.full()) return "Il faut deux parents";
        Compatibility.Check c = Compatibility.check(s.pokemon(true), s.pokemon(false), true);
        if (!c.ok()) return c.reason();
        lay(r, s, c);
        s.start = s.eggs.size() < cfg().general_settings.max_eggs_per_slot ? System.currentTimeMillis() : 0;
        if (s.start == 0) s.pause = "Œuf à récupérer";
        save(r);
        return null;
    }

    public static String setFertility(RanchData r, int slot, int value) {
        SlotData s = r.slot(slot);
        if (s == null || (!s.has(true) && !s.has(false))) return "Emplacement vide";
        for (boolean first : new boolean[]{true, false}) {
            Pokemon p = s.pokemon(first);
            if (p == null) continue;
            Traits.setFertility(p, value);
            s.refresh(first);
        }
        evaluate(r, System.currentTimeMillis());
        save(r);
        return null;
    }

    public static String setRemaining(RanchData r, int slot, long minutes) {
        SlotData s = r.slot(slot);
        if (s == null || s.start <= 0) return "Aucune ponte en cours";
        long now = System.currentTimeMillis();
        long rem = Math.max(0, Math.min(s.duration, minutes * 60_000L));
        s.start = now - (s.duration - rem);
        evaluate(r, now);
        save(r);
        return null;
    }

    /** Tous les emplacements occupes du serveur (admin). */
    public static List<int[]> occupied(RanchData r) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < r.slots.size(); i++) if (!r.slots.get(i).empty()) out.add(new int[]{i});
        return out;
    }

    /** Pension virtuelle du /breed a distance (creee au besoin). */
    public static RanchData remoteRanch(ServerPlayer p) {
        RanchData r = RanchStore.remoteOf(p.getUUID());
        if (r == null) {
            r = RanchData.create(p.getUUID(), p.getGameProfile().getName(), true, cfg().remote_breed.slots);
            RanchStore.add(r);
        }
        r.ensureSlots(cfg().remote_breed.slots);
        r.ownerName = p.getGameProfile().getName();
        return r;
    }

    /** Remise des colis a la connexion (Pokemon au PC, oeufs et objets a l'inventaire). */
    public static void deliverMail(ServerPlayer p) {
        PlayerStore.Rec rec = PlayerStore.peek(p.getUUID());
        if (rec == null || !rec.hasMail()) return;
        int pk = 0, eggs = 0, items = 0;
        for (CompoundTag t : new ArrayList<>(rec.pokemon)) {
            try {
                Pokemon mon = new Pokemon().loadFromNBT(CobbleRanch.registries(), t.copy());
                if (Stores.owns(p.getUUID(), mon.getUuid())) {
                    rec.pokemon.remove(t);
                    continue;
                }
                if (!Stores.give(p.getUUID(), mon, false)) break;
                rec.pokemon.remove(t);
                pk++;
            } catch (Throwable e) {
                CobbleRanch.LOG.error("[CobblePulse-Ranch] Colis Pokemon illisible pour {}", p.getGameProfile().getName(), e);
                break;
            }
        }
        for (CompoundTag e : new ArrayList<>(rec.eggs)) {
            if (p.getInventory().getFreeSlot() < 0 || !p.getInventory().add(EggData.toStack(e))) break;
            rec.eggs.remove(e);
            eggs++;
        }
        for (CompoundTag i : new ArrayList<>(rec.items)) {
            ItemStack st = ItemStack.parseOptional(CobbleRanch.registries(), i);
            if (st.isEmpty()) {
                rec.items.remove(i);
                continue;
            }
            if (!p.getInventory().add(st)) break;
            rec.items.remove(i);
            items++;
        }
        if (pk + eggs + items == 0) return;
        Stores.flushOwner(p.getUUID());
        Stores.savePlayer(p);
        PlayerStore.save();
        Msg.ok(p, "Boîte de réception : " + pk + " Pokémon envoyé(s) au PC, " + eggs + " œuf(s) et " + items + " objet(s) remis."
                + (rec.hasMail() ? " Il en reste : libère de la place puis reconnecte-toi." : ""));
    }
}
