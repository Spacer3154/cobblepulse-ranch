package fr.cobblepulse.ranch.service;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.battles.actor.PokemonBattleActor;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.reflect.TypeToken;
import fr.cobblepulse.ranch.CobbleRanch;
import fr.cobblepulse.ranch.config.ConfigManager;
import fr.cobblepulse.ranch.util.Msg;
import fr.cobblepulse.ranch.util.Names;
import kotlin.Unit;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/**
 * Objets de reproduction lâchés par les Pokemon sauvages, comme dans Pixelmon (config/cobbleranch/drops.json) :
 * Noeud Destin (Chaglam, Chaffreux), Pierre Stase (Leuphorie, et tenue par les Racaillou/Nodulithe captures).
 * Declenchement a la capture, a la victoire en combat et a la mort hors combat.
 */
public final class Drops {
    public static final class Drop {
        public String species;
        public String item;
        public double chance;
        public int min = 1;
        public int max = 1;
        public List<String> on = new ArrayList<>(Arrays.asList("capture", "defeat", "kill"));

        public Drop() {}

        Drop(String species, String item, double chance) {
            this.species = species;
            this.item = item;
            this.chance = chance;
        }
    }

    public static final class Held {
        public String species;
        public String item;
        public double chance;

        public Held() {}

        Held(String species, String item, double chance) {
            this.species = species;
            this.item = item;
            this.chance = chance;
        }
    }

    public static final class Config {
        public boolean enabled = true;
        /** mort hors combat : seulement si un joueur l'a tue (epee, arc...). */
        public boolean kill_requires_player = true;
        public List<Drop> drops = new ArrayList<>(List.of(
                new Drop("glameow", "cobblemon:destiny_knot", 0.10),
                new Drop("purugly", "cobblemon:destiny_knot", 0.10),
                new Drop("blissey", "cobblemon:everstone", 0.10)));
        /** objet tenu par le Pokemon au moment ou il est capture (s'il n'en tient pas deja un). */
        public List<Held> held_on_capture = new ArrayList<>(List.of(
                new Held("geodude", "cobblemon:everstone", 0.05), new Held("graveler", "cobblemon:everstone", 0.05),
                new Held("roggenrola", "cobblemon:everstone", 0.05), new Held("boldore", "cobblemon:everstone", 0.05),
                new Held("gigalith", "cobblemon:everstone", 0.05)));
    }

    private static Config config = new Config();
    private static final Random RNG = new Random();

    private Drops() {}

    public static Config get() {
        return config;
    }

    public static void load() {
        Path p = ConfigManager.dir().resolve("drops.json");
        try {
            if (Files.exists(p)) {
                Config c = ConfigManager.GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), new TypeToken<Config>() {}.getType());
                if (c != null) config = c;
            } else Files.writeString(p, ConfigManager.GSON.toJson(config), StandardCharsets.UTF_8);
        } catch (Exception e) {
            CobbleRanch.LOG.warn("[CobblePulse-Ranch] drops.json illisible : {}", e.toString());
        }
    }

    public static void register() {
        CobblemonEvents.POKEMON_CAPTURED.subscribe(Priority.NORMAL, e -> {
            try {
                onCapture(e.getPokemon(), e.getPlayer());
            } catch (Throwable t) {
                CobbleRanch.LOG.warn("[CobblePulse-Ranch] Drop a la capture : {}", t.toString());
            }
            return Unit.INSTANCE;
        });
        CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.NORMAL, e -> {
            try {
                List<ServerPlayer> winners = new ArrayList<>();
                for (var a : e.getWinners()) {
                    if (!(a instanceof PlayerBattleActor)) continue;
                    for (UUID u : a.getPlayerUUIDs()) {
                        ServerPlayer p = CobbleRanch.server().getPlayerList().getPlayer(u);
                        if (p != null) winners.add(p);
                    }
                }
                if (winners.isEmpty()) return Unit.INSTANCE;
                for (var a : e.getLosers()) {
                    if (!(a instanceof PokemonBattleActor pa) || pa.getPokemon() == null) continue;
                    Pokemon wild = pa.getPokemon().getOriginalPokemon();
                    if (wild == null) continue;
                    for (ServerPlayer p : winners) roll(wild, "defeat", p, null);
                }
            } catch (Throwable t) {
                CobbleRanch.LOG.warn("[CobblePulse-Ranch] Drop a la victoire : {}", t.toString());
            }
            return Unit.INSTANCE;
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity instanceof PokemonEntity pe) || pe.isBattling()) return;
            try {
                Pokemon pk = pe.getPokemon();
                if (pk == null || !pk.isWild()) return;
                ServerPlayer killer = source != null && source.getEntity() instanceof ServerPlayer sp ? sp : null;
                if (killer == null && config.kill_requires_player) return;
                roll(pk, "kill", killer, pe);
            } catch (Throwable t) {
                CobbleRanch.LOG.warn("[CobblePulse-Ranch] Drop a la mort : {}", t.toString());
            }
        });
    }

    private static boolean matches(Pokemon pk, String species) {
        if (species == null) return false;
        String s = species.toLowerCase(Locale.ROOT);
        return s.equals(pk.getSpecies().getName().toLowerCase(Locale.ROOT)) || s.equals(pk.getSpecies().getResourceIdentifier().toString());
    }

    private static Item item(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id == null ? "" : id);
        return rl == null || !BuiltInRegistries.ITEM.containsKey(rl) ? null : BuiltInRegistries.ITEM.get(rl);
    }

    static void onCapture(Pokemon pk, ServerPlayer p) {
        if (!config.enabled || pk == null) return;
        for (Held h : config.held_on_capture) {
            if (!matches(pk, h.species) || RNG.nextDouble() >= h.chance) continue;
            Item it = item(h.item);
            if (it == null || !pk.heldItem().isEmpty()) continue;
            pk.swapHeldItem(new ItemStack(it), false, false);
            if (p != null) Msg.send(p, Component.literal("").append(Names.speciesC(pk.getSpecies())).append(Component.literal(" tenait : ")
                    .withStyle(ChatFormatting.GRAY)).append(new ItemStack(it).getHoverName().copy().withStyle(ChatFormatting.AQUA)));
            break;
        }
        roll(pk, "capture", p, null);
    }

    /** Tirage des drops de cette espece pour cet evenement. */
    public static int roll(Pokemon pk, String event, ServerPlayer p, PokemonEntity at) {
        if (!config.enabled) return 0;
        int n = 0;
        for (Drop d : config.drops) {
            if (!matches(pk, d.species) || d.on == null || !d.on.contains(event)) continue;
            if (RNG.nextDouble() >= d.chance) continue;
            Item it = item(d.item);
            if (it == null) continue;
            int count = d.min + (d.max > d.min ? RNG.nextInt(d.max - d.min + 1) : 0);
            ItemStack st = new ItemStack(it, Math.max(1, count));
            give(p, at, st);
            n++;
            if (p != null) Msg.send(p, Component.literal("").append(Names.speciesC(pk.getSpecies())).append(Component.literal(" a laissé tomber : ")
                    .withStyle(ChatFormatting.GRAY)).append(st.getHoverName().copy().withStyle(ChatFormatting.AQUA)));
        }
        return n;
    }

    private static void give(ServerPlayer p, PokemonEntity at, ItemStack st) {
        if (at != null) {
            at.level().addFreshEntity(new ItemEntity(at.level(), at.getX(), at.getY() + 0.5, at.getZ(), st));
            return;
        }
        if (p == null) return;
        if (!p.getInventory().add(st)) {
            if (!st.isEmpty()) p.drop(st, false);
        }
    }

}
