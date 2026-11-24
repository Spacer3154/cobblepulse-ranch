package fr.cobblepulse.ranch.compat;

import fr.cobblepulse.ranch.breeding.EggData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Point d'entree "cobblepulse:item_lines" lu par CobblePulse Utils : la fiche GTS complete d'un oeuf (espece, talent,
 * chromatique, nature, Ball, IV, capacites oeuf, pas restants) dans la description de l'annonce.
 */
public final class UtilsItemLines implements Function<ItemStack, List<String>> {
    @Override
    public List<String> apply(ItemStack st) {
        CompoundTag egg = EggData.of(st);
        return egg == null ? new ArrayList<>() : EggData.gtsSheet(egg);
    }
}
