package fr.cobblepulse.ranch.item;

import fr.cobblepulse.ranch.breeding.EggData;
import fr.cobblepulse.ranch.service.HatchService;
import fr.cobblepulse.ranch.util.Msg;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Oeuf de Pokemon. Ses donnees (composant cobblepulse_ranch:egg) sont fixees a la ponte. Clic droit avec un sablier
 * tenu au curseur sur l'oeuf (inventaire) : le sablier s'applique. L'infobulle est ajoutee cote client.
 */
public final class PokemonEggItem extends Item {
    public PokemonEggItem(Properties p) {
        super(p);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        CompoundTag t = EggData.of(stack);
        return t != null && EggData.info(t).getBoolean("shiny");
    }

    @Override
    public boolean overrideOtherStackedOnMe(ItemStack egg, ItemStack other, Slot slot, ClickAction action, Player player, SlotAccess carried) {
        if (action != ClickAction.SECONDARY || !(other.getItem() instanceof HourglassItem h)) return false;
        if (EggData.of(egg) == null) return false;
        if (player instanceof ServerPlayer sp) {
            int inv = slot.container == sp.getInventory() ? slot.getContainerSlot() : -1;
            if (inv < 0) {
                Msg.err(sp, "Place l'œuf dans ton inventaire pour utiliser un sablier dessus.");
                return true;
            }
            String err = HatchService.hourglassOnEgg(sp, inv, h.gold());
            if (err != null) Msg.err(sp, err);
            else other.shrink(1);
        }
        return true;
    }
}
