package fr.cobblepulse.ranch.command;

import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.pokemon.egg.EggGroup;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.CommandNode;
import fr.cobblepulse.ranch.CobbleRanch;
import fr.cobblepulse.ranch.breeding.EggData;
import fr.cobblepulse.ranch.breeding.Genetics;
import fr.cobblepulse.ranch.perm.Perms;
import fr.cobblepulse.ranch.util.Names;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Extension de /pokegive et /pokegiveother (Cobblemon) : donner un Pokemon en oeuf.
 * <pre>
 * /pokegive egg &lt;proprietes&gt;                  oeuf pour soi (ex. charmander shiny=yes nature=adamant)
 * /pokegive egg random [proprietes]           espece au hasard (toutes les especes chargees)
 * /pokegive egg random_reproductible [...]    au hasard parmi celles qui peuvent se reproduire (pas de groupe Inconnu)
 * /pokegiveother &lt;joueur&gt; egg &lt;...&gt;            meme chose pour un autre joueur
 * </pre>
 * Les proprietes sont celles de /pokegive (chromatique, nature, talent, IV, genre, forme, Ball...). L'oeuf est au niveau 1,
 * avec les pas d'eclosion de l'espece ; le Dresseur d'Origine est celui qui le fait eclore. "egg" est un mot-cle : les
 * usages normaux de /pokegive ne changent pas.
 */
public final class EggGive {
    public static final String PERM = "cobbleranch.command.pokegive.egg";
    private static final Random RNG = new Random();

    private EggGive() {}

    static {
        Perms.DEFAULTS.put(PERM, false);
    }

    private static final SuggestionProvider<CommandSourceStack> SPECIES = (ctx, b) -> {
        String rem = b.getRemaining().toLowerCase(Locale.ROOT);
        if (rem.contains(" ")) return b.buildFuture();
        List<String> l = new ArrayList<>(List.of("random", "random_reproductible"));
        for (Species s : PokemonSpecies.getImplemented()) l.add(s.getResourceIdentifier().getNamespace().equals("cobblemon")
                ? s.getName().toLowerCase(Locale.ROOT) : s.getResourceIdentifier().toString());
        return SharedSuggestionProvider.suggest(l, b);
    };

    /**
     * Appele apres l'enregistrement des commandes de Cobblemon (phase tardive) : "egg" est greffe sur les noeuds existants
     * de /pokegive et /pokegiveother, dont la permission d'origine est conservee. Sans Cobblemon, les commandes sont
     * creees avec la seule permission de l'oeuf.
     */
    public static void register(CommandDispatcher<CommandSourceStack> d) {
        CommandNode<CommandSourceStack> self = literal("egg").requires(s -> Perms.has(s, PERM))
                .then(props().executes(ctx -> give(ctx, ctx.getSource().getPlayerOrException()))).build();
        CommandNode<CommandSourceStack> other = literal("egg").requires(s -> Perms.has(s, PERM))
                .then(props().executes(ctx -> give(ctx, ctx.getArgument("player", EntitySelector.class).findSinglePlayer(ctx.getSource())))).build();
        CommandNode<CommandSourceStack> give = d.getRoot().getChild("pokegive");
        if (give != null) give.addChild(self);
        else d.register(literal("pokegive").requires(s -> Perms.has(s, PERM)).then(self));
        CommandNode<CommandSourceStack> giveOther = d.getRoot().getChild("pokegiveother");
        CommandNode<CommandSourceStack> player = giveOther == null ? null : giveOther.getChild("player");
        if (player != null) player.addChild(other);
        else if (giveOther == null)
            d.register(literal("pokegiveother").requires(s -> Perms.has(s, PERM)).then(argument("player", EntityArgument.player()).then(other)));
        CobbleRanch.LOG.info("[CobblePulse-Ranch] /pokegive egg {} ; /pokegiveother egg {}", give != null ? "greffe sur Cobblemon" : "autonome",
                player != null ? "greffe sur Cobblemon" : giveOther == null ? "autonome" : "indisponible");
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> props() {
        return argument("proprietes", StringArgumentType.greedyString()).suggests(SPECIES);
    }

    private static int give(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        String props = StringArgumentType.getString(ctx, "proprietes");
        String by = ctx.getSource().getTextName();
        CompoundTag egg;
        try {
            egg = create(props, "Œuf offert par " + by);
        } catch (IllegalArgumentException e) {
            ctx.getSource().sendFailure(Component.literal("[CobbleRanch] " + e.getMessage()));
            return 0;
        }
        ItemStack st = EggData.toStack(egg);
        if (!target.getInventory().add(st) && !st.isEmpty()) target.drop(st, false);
        CompoundTag i = EggData.info(egg);
        String name = i.getString("species_name") + (i.getString("form").isEmpty() ? "" : " (" + i.getString("form") + ")") + (i.getBoolean("shiny") ? " ★" : "");
        ctx.getSource().sendSuccess(() -> Component.literal("[CobbleRanch] Œuf de " + name + " donné à " + target.getGameProfile().getName()), true);
        if (ctx.getSource().getEntity() != target)
            target.sendSystemMessage(Component.literal("[CobbleRanch] ").withStyle(ChatFormatting.GOLD).append(Component.literal("Tu as reçu un Œuf de Pokémon !")
                    .withStyle(ChatFormatting.GREEN)));
        CobbleRanch.LOG.info("[CobblePulse-Ranch] /pokegive egg par {} : {} pour {} ({})", by, name, target.getGameProfile().getName(), props);
        return 1;
    }

    /** Donnees d'oeuf depuis des proprietes /pokegive (ou random / random_reproductible). */
    public static CompoundTag create(String props, String label) {
        String s = props == null ? "" : props.trim();
        if (s.isEmpty()) throw new IllegalArgumentException("Précise une espèce, random ou random_reproductible.");
        String[] parts = s.split("\\s+", 2);
        String first = parts[0].toLowerCase(Locale.ROOT);
        if (first.equals("random") || first.equals("aleatoire") || first.equals("random_reproductible")) {
            List<Species> pool = new ArrayList<>();
            for (Species sp : PokemonSpecies.getImplemented()) {
                if (first.equals("random_reproductible")) {
                    var g = sp.getEggGroups();
                    if (g == null || g.isEmpty() || g.contains(EggGroup.UNDISCOVERED)) continue;
                }
                pool.add(sp);
            }
            if (pool.isEmpty()) throw new IllegalArgumentException("Aucune espèce disponible.");
            Species pick = pool.get(RNG.nextInt(pool.size()));
            s = "species=" + pick.getResourceIdentifier() + (parts.length > 1 ? " " + parts[1] : "");
        }
        PokemonProperties pp = PokemonProperties.Companion.parse(s);
        Species sp = pp.getSpecies() == null ? null : Genetics.byName(pp.getSpecies());
        if (sp == null) throw new IllegalArgumentException("Espèce inconnue : " + parts[0]);
        pp.setLevel(1);
        Pokemon pk = pp.create();
        if (pk.getSpecies() != sp) pk.setSpecies(sp);
        if (pk.getLevel() != 1) pk.setLevel(1);
        pk.heal();
        CobbleRanch.LOG.debug("[CobblePulse-Ranch] oeuf cree : {}", Names.species(sp));
        return EggData.build(pk, null, label);
    }
}
