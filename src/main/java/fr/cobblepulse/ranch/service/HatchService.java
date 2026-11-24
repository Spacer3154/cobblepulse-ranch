package fr.cobblepulse.ranch.service;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.events.pokemon.HatchEggEvent;
import com.cobblemon.mod.common.pokemon.Pokemon;
import fr.cobblepulse.ranch.CobbleRanch;
import fr.cobblepulse.ranch.breeding.EggData;
import fr.cobblepulse.ranch.breeding.Traits;
import fr.cobblepulse.ranch.config.ConfigManager;
import fr.cobblepulse.ranch.config.RanchConfig;
import fr.cobblepulse.ranch.data.Journal;
import fr.cobblepulse.ranch.data.WorldState;
import fr.cobblepulse.ranch.perm.Perms;
import fr.cobblepulse.ranch.registry.ModContent;
import fr.cobblepulse.ranch.util.Msg;
import fr.cobblepulse.ranch.util.Names;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Eclosion dans l'inventaire au prorata de la distance parcourue. Pas de travail par tick : chaque joueur est traite une
 * fois toutes les egg_tick_interval_seconds (joueurs repartis sur l'intervalle), distance mesuree entre deux passages
 * (teleportations ignorees au-dela de max_blocks_per_interval). Corps Ardent / Armumagma / Turbine dans l'equipe :
 * multiplicateur flame_body_hatch_multiplier.
 */
public final class HatchService {
    private static final Map<UUID, Vec3> LAST = new HashMap<>();
    private static final Map<UUID, String> LAST_DIM = new HashMap<>();
    private static long tick;

    private HatchService() {}

    private static RanchConfig.Hatching cfg() {
        return ConfigManager.get().hatching_and_items;
    }

    public static void tick(MinecraftServer server) {
        tick++;
        int interval = Math.max(20, cfg().egg_tick_interval_seconds * 20);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if ((tick + (p.getUUID().hashCode() & 0x7fffffff)) % interval != 0) continue;
            sample(p);
        }
    }

    public static void forget(UUID u) {
        LAST.remove(u);
        LAST_DIM.remove(u);
    }

    private static void sample(ServerPlayer p) {
        Vec3 now = p.position();
        String dim = p.level().dimension().location().toString();
        Vec3 prev = LAST.put(p.getUUID(), now);
        String pd = LAST_DIM.put(p.getUUID(), dim);
        if (prev == null || !dim.equals(pd) || p.isSpectator()) return;
        double d = now.distanceTo(prev);
        if (d <= 0.05 || d > cfg().max_blocks_per_interval) return;
        walk(p, d);
    }

    /** Talent accelerateur dans l'equipe ? */
    public static boolean boosted(ServerPlayer p) {
        for (Pokemon pk : Stores.party(p)) {
            if (pk == null) continue;
            String a = pk.getAbility().getName().toLowerCase(Locale.ROOT);
            for (String b : cfg().hatch_boost_abilities) if (a.equals(b.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""))) return true;
        }
        return false;
    }

    /** Distance parcourue : fait avancer tous les oeufs de l'inventaire. */
    public static void walk(ServerPlayer p, double blocks) {
        var inv = p.getInventory();
        boolean any = false;
        for (int i = 0; i < inv.getContainerSize(); i++) if (EggData.of(inv.getItem(i)) != null) any = true;
        if (!any) return;
        double steps = blocks * cfg().steps_per_block * (boosted(p) ? cfg().flame_body_hatch_multiplier : 1.0);
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            CompoundTag t = EggData.of(st);
            if (t == null) continue;
            double left = EggData.steps(t) - steps;
            if (left <= 0) {
                st.set(ModContent.EGG_DATA, EggData.withSteps(t, 0));
                hatch(p, i, false);
            } else st.set(ModContent.EGG_DATA, EggData.withSteps(t, left));
        }
    }

    /**
     * Eclosion transactionnelle : registre des eclosions + journal, oeuf retire, Pokemon ajoute (equipe puis PC),
     * equipe/PC puis inventaire enregistres tout de suite. Un oeuf deja eclos ou dont le Pokemon existe deja ne
     * produit rien (anti-duplication).
     */
    public static boolean hatch(ServerPlayer p, int invSlot, boolean instant) {
        var inv = p.getInventory();
        ItemStack st = inv.getItem(invSlot);
        CompoundTag egg = EggData.of(st);
        if (egg == null) return false;
        UUID eggId = EggData.id(egg);
        if (WorldState.hatched(eggId) != null) {
            inv.setItem(invSlot, ItemStack.EMPTY);
            Stores.savePlayer(p);
            Msg.err(p, "Cet œuf a déjà éclos : copie supprimée.");
            CobbleRanch.LOG.warn("[CobblePulse-Ranch] Anti-duplication : oeuf {} deja eclos, copie de {} supprimee", eggId, p.getGameProfile().getName());
            return false;
        }
        Pokemon pk = EggData.pokemon(egg);
        if (pk == null) return false;
        if (Stores.owns(p.getUUID(), pk.getUuid())) {
            inv.setItem(invSlot, ItemStack.EMPTY);
            Stores.savePlayer(p);
            Msg.err(p, "Ce Pokémon existe déjà : copie de l'œuf supprimée.");
            return false;
        }
        pk.setOriginalTrainer(p.getUUID());
        pk.setOriginalTrainerName(p.getGameProfile().getName());
        if (Traits.fertilityEnabled()) Traits.setFertility(pk, Traits.maxFertility());
        CompoundTag dest = Journal.where("PARTY");
        UUID tx = Journal.begin("HATCH", p.getUUID(), eggId, egg.copy(), Journal.where("INVENTORY"), dest);
        WorldState.recordHatch(eggId, pk.getUuid(), p.getUUID(), false);
        inv.setItem(invSlot, ItemStack.EMPTY);
        if (!Stores.give(p.getUUID(), pk, true)) {
            // equipe et PC pleins : l'oeuf reste (rien n'est perdu)
            inv.setItem(invSlot, st);
            WorldState.hatchedAll().remove(eggId);
            WorldState.save();
            Journal.end(tx);
            Msg.err(p, "Ton œuf est prêt à éclore mais ton équipe et ton PC sont pleins !");
            return false;
        }
        Stores.flushOwner(p.getUUID());
        Stores.savePlayer(p);
        WorldState.markDurable(eggId);
        Journal.end(tx);
        try {
            Cobblemon.playerDataManager.getPokedexData(p).obtain(pk);
        } catch (Throwable ignored) {
        }
        try {
            CobblemonEvents.HATCH_EGG_POST.post(new HatchEggEvent.Post(p, pk));
        } catch (Throwable ignored) {
        }
        ServerLevel l = p.serverLevel();
        l.sendParticles(ParticleTypes.HAPPY_VILLAGER, p.getX(), p.getY() + 1, p.getZ(), 20, 0.5, 0.5, 0.5, 0.1);
        l.playSound(null, p.blockPosition(), SoundEvents.TURTLE_EGG_HATCH, SoundSource.PLAYERS, 1f, 1f);
        p.sendSystemMessage(Msg.prefix().append(Component.literal("Ton œuf a éclos ! C'est un ").withStyle(ChatFormatting.GREEN))
                .append(Names.speciesC(pk.getSpecies()).withStyle(pk.getShiny() ? ChatFormatting.YELLOW : ChatFormatting.AQUA))
                .append(Component.literal(pk.getShiny() ? " ★ chromatique !" : " !").withStyle(pk.getShiny() ? ChatFormatting.YELLOW : ChatFormatting.GREEN)));
        CobbleRanch.LOG.info("[CobblePulse-Ranch] Eclosion{} : {} pour {} (oeuf {})", instant ? " instantanee" : "", Names.species(pk.getSpecies()),
                p.getGameProfile().getName(), eggId);
        return true;
    }

    /** Sablier sur un oeuf de l'inventaire. null = applique, sinon raison du refus. */
    public static String hourglassOnEgg(ServerPlayer p, int invSlot, boolean gold) {
        if (!Perms.has(p, gold ? Perms.HOURGLASS_GOLD : Perms.HOURGLASS_SILVER)) return "Tu n'as pas la permission d'utiliser ce sablier";
        ItemStack st = p.getInventory().getItem(invSlot);
        CompoundTag t = EggData.of(st);
        if (t == null) return "Ce n'est pas un œuf";
        if (gold) {
            if (!cfg().gold_hourglass_instant_hatch) return "Le Sablier d'Or est désactivé pour les œufs";
            st.set(ModContent.EGG_DATA, EggData.withSteps(t, 0));
            return hatch(p, invSlot, true) ? null : "Éclosion impossible";
        }
        double left = EggData.steps(t);
        double cut = "FIXED".equalsIgnoreCase(cfg().silver_hourglass_reduction_mode) ? cfg().silver_hourglass_fixed_egg_steps
                : left * cfg().silver_hourglass_value / 100.0;
        left -= Math.max(1, cut);
        if (left <= 0) {
            st.set(ModContent.EGG_DATA, EggData.withSteps(t, 0));
            return hatch(p, invSlot, true) ? null : "Éclosion impossible";
        }
        st.set(ModContent.EGG_DATA, EggData.withSteps(t, left));
        p.level().playSound(null, p.blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1f, 1.1f);
        Msg.ok(p, "Sablier d'Argent : il reste " + EggData.fmt(Math.round(left)) + " pas.");
        return null;
    }
}
