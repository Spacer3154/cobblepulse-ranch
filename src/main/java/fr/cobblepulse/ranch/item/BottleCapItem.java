package fr.cobblepulse.ranch.item;

import fr.cobblepulse.ranch.service.BottleCaps;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Capsule d'Argent (une statistique) et Capsule d'Or (toutes) : Entrainement Ultime. Clic droit sur son Pokemon sorti :
 * ecran de choix de la statistique (Argent) ou de confirmation (Or), via UseEntityCallback (avant Cobblemon). Clic droit
 * dans le vide : choix du Pokemon de l'equipe dans le chat. Meme chose avec /capsule.
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
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.literal(gold ? "Entraînement Ultime : toutes les statistiques à 31 en combat."
                : "Entraînement Ultime : une statistique au choix à 31 en combat.").withStyle(ChatFormatting.GRAY));
        tip.add(Component.literal("La reproduction transmet toujours les IV d'origine.").withStyle(ChatFormatting.DARK_AQUA));
        tip.add(Component.literal("Clic droit sur ton Pokémon (niveau max) ou /capsule.").withStyle(ChatFormatting.DARK_GRAY));
    }
}
