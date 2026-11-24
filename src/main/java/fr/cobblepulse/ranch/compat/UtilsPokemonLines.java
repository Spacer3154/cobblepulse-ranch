package fr.cobblepulse.ranch.compat;

import com.cobblemon.mod.common.pokemon.Pokemon;
import fr.cobblepulse.ranch.breeding.Traits;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Point d'entree "cobblepulse:pokemon_lines" lu par CobblePulse Utils : lignes ajoutees a la fiche d'un Pokemon partout
 * ou Utils l'affiche (GTS, STS, Echange Miracle, menus). Castration et fertilite visibles avant l'achat.
 */
public final class UtilsPokemonLines implements Function<Pokemon, List<String>> {
    @Override
    public List<String> apply(Pokemon p) {
        List<String> l = new ArrayList<>();
        if (p == null) return l;
        if (Traits.neutered(p)) l.add("&c✂ Castré &8(ne peut pas se reproduire)");
        else if (Traits.fertilityEnabled()) {
            int f = Traits.fertility(p);
            l.add("&7Fertilité : " + (f <= 0 ? "&c" : "&a") + f + "/" + Traits.maxFertility());
        }
        return l;
    }
}
