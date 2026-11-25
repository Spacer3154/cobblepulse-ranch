package fr.cobblepulse.ranch;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import fr.cobblepulse.ranch.block.RanchBlockEntity;
import fr.cobblepulse.ranch.breeding.Habitat;
import fr.cobblepulse.ranch.breeding.Traits;
import fr.cobblepulse.ranch.command.RanchCommands;
import fr.cobblepulse.ranch.config.ConfigManager;
import fr.cobblepulse.ranch.data.Journal;
import fr.cobblepulse.ranch.data.PlayerStore;
import fr.cobblepulse.ranch.data.RanchStore;
import fr.cobblepulse.ranch.data.WorldState;
import fr.cobblepulse.ranch.net.RanchNet;
import fr.cobblepulse.ranch.registry.ModContent;
import fr.cobblepulse.ranch.service.Drops;
import fr.cobblepulse.ranch.service.HatchService;
import fr.cobblepulse.ranch.service.RanchService;
import fr.cobblepulse.ranch.service.Transfers;
import fr.cobblepulse.ranch.test.SelfTest;
import fr.cobblepulse.ranch.util.Msg;
import fr.cobblepulse.ranch.util.Names;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** CobblePulse Ranch : Pension et reproduction des Pokemon (Minecraft 1.21.1, Fabric, Cobblemon 1.8.4). */
public final class CobbleRanch implements ModInitializer {
    public static final String MOD_ID = "cobblepulse_ranch";
    public static final Logger LOG = LoggerFactory.getLogger("CobblePulse-Ranch");
    private static MinecraftServer server;

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    public static MinecraftServer server() {
        return server;
    }

    public static RegistryAccess registries() {
        return server.registryAccess();
    }

    public static String version() {
        return FabricLoader.getInstance().getModContainer(MOD_ID).map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
    }

    @Override
    public void onInitialize() {
        ConfigManager.load();
        ModContent.register();
        RanchNet.registerTypes();
        RanchNet.registerServer();
        Drops.load();
        Drops.register();
        CommandRegistrationCallback.EVENT.register((d, ctx, env) -> RanchCommands.register(d));
        // /pokegive egg : apres les commandes de Cobblemon (phase tardive), pour se greffer sur ses noeuds
        ResourceLocation late = id("after_cobblemon");
        CommandRegistrationCallback.EVENT.addPhaseOrdering(net.fabricmc.fabric.api.event.Event.DEFAULT_PHASE, late);
        CommandRegistrationCallback.EVENT.register(late, (d, ctx, env) -> fr.cobblepulse.ranch.command.EggGive.register(d));

        ServerLifecycleEvents.SERVER_STARTING.register(s -> {
            server = s;
            Path data = s.getWorldPath(LevelResource.ROOT).resolve("cobbleranch").resolve("data");
            RanchStore.init(data);
            PlayerStore.init(data);
            WorldState.init(data);
            Journal.init(data);
            Names.load(s.getWorldPath(LevelResource.DATAPACK_DIR));
        });
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            try {
                Transfers.recoverAtStartup();
            } catch (Throwable t) {
                LOG.error("[CobblePulse-Ranch] Reconciliation du journal impossible", t);
            }
            WorldState.prune();
            selfCheck();
        });
        ServerLifecycleEvents.AFTER_SAVE.register((s, flush, force) -> {
            RanchStore.flushDirty();
            PlayerStore.flushIfDirty();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            RanchStore.flushDirty();
            PlayerStore.save();
            WorldState.save();
        });
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk) -> level.getServer().execute(() -> {
            try {
                if (level.getChunkSource().hasChunk(chunk.getPos().x, chunk.getPos().z)) RanchService.onChunkLoad(level, chunk);
            } catch (Throwable t) {
                LOG.error("[CobblePulse-Ranch] Verification des pensions du chunk {} impossible", chunk.getPos(), t);
            }
        }));
        ServerTickEvents.END_SERVER_TICK.register(HatchService::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> s.execute(() -> onJoin(handler.getPlayer())));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            ServerPlayer p = handler.getPlayer();
            RanchService.forget(p.getUUID());
            HatchService.forget(p.getUUID());
            RanchNet.forget(p.getUUID());
            PlayerStore.flushIfDirty();
        });
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, be) -> {
            if (be instanceof RanchBlockEntity rb && !rb.canBreak(player)) {
                if (player instanceof ServerPlayer sp) Msg.err(sp, "Seul le propriétaire de cette Pension peut la retirer.");
                return false;
            }
            return true;
        });
        SelfTest.install();
        LOG.info("[CobblePulse-Ranch] {} initialise", version());
    }

    private static void onJoin(ServerPlayer p) {
        try {
            PlayerStore.Rec rec = PlayerStore.get(p.getUUID());
            rec.name = p.getGameProfile().getName();
            String lang = p.clientInformation() == null ? "" : p.clientInformation().language();
            if (lang != null && !lang.isEmpty()) rec.language = lang;
            PlayerStore.markDirty();
            Transfers.recoverFor(p);
            Transfers.auditInventory(p);
            RanchService.deliverMail(p);
        } catch (Throwable t) {
            LOG.error("[CobblePulse-Ranch] Connexion de {} : verification impossible", p.getGameProfile().getName(), t);
        }
    }

    public static void reload() {
        ConfigManager.load();
        Habitat.clearCache();
        Drops.load();
        if (server != null) Names.load(server.getWorldPath(LevelResource.DATAPACK_DIR));
        LOG.info("[CobblePulse-Ranch] Configuration rechargee");
    }

    /** Verification au demarrage : objets de reproduction, encens, especes de la config. */
    private static void selfCheck() {
        List<String> problems = new ArrayList<>();
        List<String> items = new ArrayList<>(List.of(Traits.DESTINY_KNOT, Traits.EVERSTONE, Traits.MIRROR_HERB));
        items.addAll(List.of(Traits.POWER_ITEMS));
        for (String i : items) if (!BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(i))) problems.add("objet absent : " + i);
        for (Map.Entry<String, List<String>> e : ConfigManager.get().genetics.incense_babies.entrySet()) {
            if (fr.cobblepulse.ranch.breeding.Genetics.byName(e.getKey()) == null) problems.add("bébé inconnu : " + e.getKey());
            boolean any = false;
            for (String i : e.getValue()) any |= BuiltInRegistries.ITEM.containsKey(ResourceLocation.tryParse(i));
            if (!any) problems.add("aucun encens existant pour " + e.getKey());
        }
        int species = PokemonSpecies.getSpecies().size();
        int breedable = 0;
        for (var sp : PokemonSpecies.getSpecies()) {
            var g = sp.getEggGroups();
            if (g != null && !g.isEmpty() && !g.contains(com.cobblemon.mod.common.api.pokemon.egg.EggGroup.UNDISCOVERED)) breedable++;
        }
        problems.addAll(ConfigManager.warnings());
        if (problems.isEmpty()) LOG.info("[CobblePulse-Ranch] OK : {} especes chargees ({} peuvent se reproduire), {} pension(s)", species, breedable, RanchStore.all().size());
        else LOG.warn("[CobblePulse-Ranch] {} probleme(s) : {}", problems.size(), String.join(" | ", problems));
    }
}
