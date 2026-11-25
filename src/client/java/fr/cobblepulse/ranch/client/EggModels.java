package fr.cobblepulse.ranch.client;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Species;
import fr.cobblepulse.ranch.CobbleRanch;
import fr.cobblepulse.ranch.registry.ModContent;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * Apparence de l'Oeuf de Pokemon selon ses types (textures fournies par Spacer) : fond = type principal, taches = second
 * type (ou du type principal pour un mono-type). 18 x 18 modeles choisis par deux proprietes d'objet ; oeuf special de
 * Manaphy ; oeuf par defaut si le type est inconnu (type ajoute par un mod) ou l'oeuf sans donnees.
 */
public final class EggModels {
    public static final List<String> TYPES = List.of("normal", "fire", "water", "grass", "electric", "ice", "fighting", "poison", "ground",
            "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy");

    private EggModels() {}

    static void register() {
        ItemProperties.register(ModContent.POKEMON_EGG, CobbleRanch.id("egg_primary"), (st, level, entity, seed) -> value(types(st)[0]));
        ItemProperties.register(ModContent.POKEMON_EGG, CobbleRanch.id("egg_secondary"), (st, level, entity, seed) -> {
            String[] t = types(st);
            return value(t[1].isEmpty() ? t[0] : t[1]);
        });
        ItemProperties.register(ModContent.POKEMON_EGG, CobbleRanch.id("egg_special"), (st, level, entity, seed) -> special(st) ? 1f / 32f : 0f);
    }

    /** (index + 1) / 32, 0 si inconnu : meme valeur que dans le modele pokemon_egg.json. */
    static float value(String type) {
        int i = TYPES.indexOf(type == null ? "" : type.toLowerCase(Locale.ROOT));
        return i < 0 ? 0f : (i + 1) / 32f;
    }

    /** {type principal, second type ("" si mono-type)}. Oeufs anterieurs aux types : lus dans l'espece cote client. */
    static String[] types(ItemStack st) {
        CompoundTag t = st.get(ModContent.EGG_DATA);
        if (t == null) return new String[]{"", ""};
        CompoundTag i = t.getCompound("info");
        if (i.contains("type1")) return new String[]{i.getString("type1"), i.getString("type2")};
        try {
            ResourceLocation rl = ResourceLocation.tryParse(i.getString("species"));
            Species sp = rl == null ? null : PokemonSpecies.getByIdentifier(rl);
            if (sp != null) {
                var f = sp.getStandardForm();
                return new String[]{f.getPrimaryType().getShowdownId(), f.getSecondaryType() == null ? "" : f.getSecondaryType().getShowdownId()};
            }
        } catch (Throwable ignored) {
        }
        return new String[]{"", ""};
    }

    static boolean special(ItemStack st) {
        CompoundTag t = st.get(ModContent.EGG_DATA);
        return t != null && t.getCompound("info").getString("species").toLowerCase(Locale.ROOT).endsWith(":manaphy");
    }
}
