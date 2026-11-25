package fr.cobblepulse.ranch.item;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import fr.cobblepulse.ranch.service.BottleCaps;
import fr.cobblepulse.ranch.service.Stores;
import fr.cobblepulse.ranch.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Capsule d'Argent (une statistique) et Capsule d'Or (toutes) : Entrainement Ultime. Clic droit : choix du Pokemon
 * de l'equipe dans le chat ; clic droit sur son Pokemon sorti : directement ce Pokemon. Meme chose avec /capsule.
 */
public final class BottleCapItem extends Item {
    private final boolean gold;

    public BottleCapItem(boolean gold, Properties p) {
        super(p.rarity(gold ? Rarity.EPIC : Rarity.RARE));
        this.gold = gold;
    }

    public boolean gold() {
        return gold;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack st = player.getItemInHand(hand);
        if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResultHolder.success(st);
        BottleCaps.partyMenu(sp);
        return InteractionResultHolder.consume(st);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof PokemonEntity pe)) return InteractionResult.PASS;
        if (player.level().isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
        Pokemon pk = pe.getPokemon();
        var party = Stores.party(sp);
        for (int s = 0; s < 6; s++) {
            Pokemon q = party.get(s);
            if (q != null && q.getUuid().equals(pk.getUuid())) {
                BottleCaps.use(sp, s + 1, gold ? BottleCaps.ALL : null, null);
                return InteractionResult.CONSUME;
            }
        }
        Msg.err(sp, "La Capsule s'utilise sur un Pokémon de ton équipe.");
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.literal(gold ? "Entraînement Ultime : toutes les statistiques à 31 en combat."
                : "Entraînement Ultime : une statistique au choix à 31 en combat.").withStyle(ChatFormatting.GRAY));
        tip.add(Component.literal("La reproduction transmet toujours les IV d'origine.").withStyle(ChatFormatting.DARK_AQUA));
        tip.add(Component.literal("Pokémon de l'équipe au niveau maximum. Clic droit ou /capsule.").withStyle(ChatFormatting.DARK_GRAY));
    }
}
