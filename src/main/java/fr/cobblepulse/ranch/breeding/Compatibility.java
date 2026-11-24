package fr.cobblepulse.ranch.breeding;

import com.cobblemon.mod.common.api.pokemon.egg.EggGroup;
import com.cobblemon.mod.common.pokemon.Gender;
import com.cobblemon.mod.common.pokemon.Pokemon;
import fr.cobblepulse.ranch.config.ConfigManager;
import fr.cobblepulse.ranch.config.RanchConfig;
import fr.cobblepulse.ranch.data.WorldState;
import fr.cobblepulse.ranch.util.Names;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Regles de compatibilite (table des groupes d'oeufs lue dans les donnees de Cobblemon : especes officielles, de mods,
 * de datapacks et Fakemons). Deux Pokemon sont compatibles s'ils partagent un groupe et sont de sexes opposes, ou si
 * un Metamorph est implique (jamais avec le groupe Inconnu).
 */
public final class Compatibility {
    public record Check(boolean ok, String reason, Pokemon mother, Pokemon father, boolean dittoInvolved, Set<EggGroup> groups) {
        static Check no(String reason) {
            return new Check(false, reason, null, null, false, EnumSet.noneOf(EggGroup.class));
        }
    }

    private Compatibility() {}

    public static boolean dittoXDittoAllowed() {
        Boolean o = WorldState.dittoOverride();
        return o != null ? o : ConfigManager.get().general_settings.allow_ditto_x_ditto;
    }

    /** Raison pour laquelle ce Pokemon ne peut pas du tout se reproduire ("" s'il le peut). */
    public static String blockReason(Pokemon p, boolean bypassFertility) {
        if (p == null) return "Pokémon introuvable";
        if (Traits.neutered(p)) return Names.species(p.getSpecies()) + " est castré";
        if (!bypassFertility && Traits.sterile(p)) return Names.species(p.getSpecies()) + " n'est plus fertile (0/" + Traits.maxFertility() + ")";
        Set<EggGroup> g = Traits.eggGroups(p);
        if (g.isEmpty() || g.contains(EggGroup.UNDISCOVERED)) return Names.species(p.getSpecies()) + " est du groupe Inconnu : il ne peut pas se reproduire";
        RanchConfig.Genetics gen = ConfigManager.get().genetics;
        String name = p.getSpecies().getName().toLowerCase(Locale.ROOT);
        String id = p.getSpecies().getResourceIdentifier().toString();
        for (String s : gen.blocked_species) {
            String b = s.toLowerCase(Locale.ROOT);
            if (b.equals(name) || b.equals(id)) return Names.species(p.getSpecies()) + " ne peut pas se reproduire sur ce serveur";
        }
        for (String l : gen.blocked_labels) {
            if (p.getSpecies().getLabels().contains(l.toLowerCase(Locale.ROOT)))
                return Names.species(p.getSpecies()) + " ne peut pas se reproduire sur ce serveur (" + l + ")";
        }
        return "";
    }

    public static Check check(Pokemon a, Pokemon b, boolean bypassFertility) {
        if (a == null || b == null) return Check.no("Il faut deux parents");
        if (a.getUuid().equals(b.getUuid())) return Check.no("Le même Pokémon ne peut pas être ses deux parents");
        String r = blockReason(a, bypassFertility);
        if (r.isEmpty()) r = blockReason(b, bypassFertility);
        if (!r.isEmpty()) return Check.no(r);
        boolean da = Traits.isDitto(a), db = Traits.isDitto(b);
        if (da && db) {
            if (!dittoXDittoAllowed()) return Check.no("Deux Métamorph ne peuvent pas se reproduire ensemble");
            return new Check(true, "", a, b, true, EnumSet.of(EggGroup.DITTO));
        }
        if (da || db) {
            Pokemon other = da ? b : a;
            Set<EggGroup> g = EnumSet.noneOf(EggGroup.class);
            g.addAll(Traits.eggGroups(other));
            g.remove(EggGroup.DITTO);
            if (g.isEmpty()) return Check.no("Aucun groupe d'œufs compatible avec Métamorph");
            return new Check(true, "", other, da ? a : b, true, g);
        }
        Gender ga = a.getGender(), gb = b.getGender();
        if (ga == Gender.GENDERLESS || gb == Gender.GENDERLESS || ga == null || gb == null)
            return Check.no("Un Pokémon asexué ne peut se reproduire qu'avec Métamorph");
        if (ga == gb) return Check.no("Il faut un mâle et une femelle");
        Set<EggGroup> common = EnumSet.noneOf(EggGroup.class);
        common.addAll(Traits.eggGroups(a));
        common.retainAll(Traits.eggGroups(b));
        if (common.isEmpty()) return Check.no("Aucun groupe d'œufs en commun");
        Pokemon mother = ga == Gender.FEMALE ? a : b;
        return new Check(true, "", mother, mother == a ? b : a, false, common);
    }
}
