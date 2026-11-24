package fr.cobblepulse.ranch.item;

import fr.cobblepulse.ranch.breeding.EggData;
import fr.cobblepulse.ranch.service.HatchService;
import fr.cobblepulse.ranch.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Sablier d'Argent (reduit le temps de ponte restant ou les pas d'un oeuf) et Sablier d'Or (ponte ou eclosion
 * immediate). Sur un emplacement : bouton dans l'ecran de la Pension. Sur un oeuf : clic droit avec le sablier sur
 * l'oeuf dans l'inventaire, ou sablier en main principale et oeuf en main secondaire.
 */
public final class HourglassItem extends Item {
    private final boolean gold;

    public HourglassItem(boolean gold, Properties p) {
        super(p);
        this.gold = gold;
    }

    public boolean gold() {
        return gold;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack st = player.getItemInHand(hand);
        if (level.isClientSide() || !(player instanceof ServerPlayer sp)) return InteractionResultHolder.success(st);
        if (hand == InteractionHand.MAIN_HAND && EggData.of(sp.getOffhandItem()) != null) {
            String err = HatchService.hourglassOnEgg(sp, 40, gold);
            if (err != null) Msg.err(sp, err);
            else st.shrink(1);
            return InteractionResultHolder.consume(st);
        }
        Msg.info(sp, "Utilise-le sur un œuf (clic droit dessus dans l'inventaire, ou œuf en main secondaire) ou sur un emplacement de Pension (bouton dans l'écran).");
        return InteractionResultHolder.pass(st);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext ctx, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.literal(gold ? "Ponte ou éclosion immédiate." : "Réduit le temps de ponte ou les pas d'éclosion.").withStyle(ChatFormatting.GRAY));
        tip.add(Component.literal("Œuf : clic droit avec le sablier sur l'œuf dans l'inventaire.").withStyle(ChatFormatting.DARK_GRAY));
        tip.add(Component.literal("Pension : bouton dans l'écran du Bloc de Pension.").withStyle(ChatFormatting.DARK_GRAY));
    }
}
