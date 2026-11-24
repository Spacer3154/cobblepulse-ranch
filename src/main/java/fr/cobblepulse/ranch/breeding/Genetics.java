package fr.cobblepulse.ranch.breeding;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.abilities.AbilityTemplate;
import com.cobblemon.mod.common.api.abilities.PotentialAbility;
import com.cobblemon.mod.common.api.moves.BenchedMove;
import com.cobblemon.mod.common.api.moves.Move;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.pokeball.PokeBalls;
import com.cobblemon.mod.common.api.pokemon.Natures;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.pokemon.evolution.PreEvolution;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.pokeball.PokeBall;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Nature;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobblemon.mod.common.pokemon.abilities.HiddenAbility;
import fr.cobblepulse.ranch.config.ConfigManager;
import fr.cobblepulse.ranch.config.RanchConfig;
import fr.cobblepulse.ranch.data.PlayerStore;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Creation du descendant au moment exact de la ponte : espece (premier stade, bebe seulement avec l'encens), forme
 * regionale, IV (Noeud Destin, objets Pouvoir), nature (Pierre Stase), talent (talent cache seulement par la mere),
 * Ball de la mere, capacites oeuf (et Herbe Miroir), chromatique (Masuda). Tout est fige dans l'oeuf.
 */
public final class Genetics {
    public static final Stat[] STATS = {Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED};

    /** Resultat : le Pokemon de l'oeuf, et ce que l'Herbe Miroir a appris aux parents. */
    public record Result(Pokemon child, List<String> notes, boolean parentsChanged, boolean masuda, List<String> eggMoves) {}

    private Genetics() {}

    private static RanchConfig.Genetics cfg() {
        return ConfigManager.get().genetics;
    }

    // ---------------------------------------------------------------- espece

    /** Chaine d'evolution de la forme jusqu'au premier stade : [premier stade, ..., forme donnee]. */
    public static List<FormData> lineage(FormData f) {
        List<FormData> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        FormData cur = f;
        while (cur != null && chain.size() < 12 && seen.add(cur.getSpecies().getResourceIdentifier() + "/" + cur.getName())) {
            chain.add(cur);
            PreEvolution pre = cur.getPreEvolution();
            if (pre == null) break;
            FormData next = pre.getForm();
            if (next == null && pre.getSpecies() != null) next = pre.getSpecies().getStandardForm();
            cur = next;
        }
        Collections.reverse(chain);
        return chain;
    }

    /** "nidoranf", "Nidoran-F", "cobblemon:nidoranf", "Mime Jr." -> "nidoranf" / "mimejr". */
    public static String key(String s) {
        if (s == null) return "";
        String n = s.toLowerCase(Locale.ROOT).trim();
        if (n.contains(":")) n = n.substring(n.indexOf(':') + 1);
        return n.replaceAll("[^a-z0-9]", "");
    }

    public static Species byName(String s) {
        if (s == null || s.isBlank()) return null;
        String n = s.toLowerCase(Locale.ROOT).trim();
        ResourceLocation rl = ResourceLocation.tryParse(n.contains(":") ? n : "cobblemon:" + n);
        Species sp = rl == null ? null : PokemonSpecies.getByIdentifier(rl);
        if (sp != null) return sp;
        sp = PokemonSpecies.getByName(n.contains(":") ? n.substring(n.indexOf(':') + 1) : n);
        if (sp != null) return sp;
        String k = key(s);
        for (Species x : PokemonSpecies.getSpecies()) if (matches(x, k)) return x;
        return null;
    }

    /** Espece designee par cette cle (nom, identifiant Showdown ou identifiant complet, casse et ponctuation ignorees). */
    public static boolean matches(Species sp, String key) {
        String k = key(key);
        if (k.isEmpty()) return false;
        if (key.contains(":") && key.toLowerCase(Locale.ROOT).equals(sp.getResourceIdentifier().toString())) return true;
        return k.equals(key(sp.getName())) || k.equals(key(sp.getResourceIdentifier().getPath())) || k.equals(key(sp.showdownId()));
    }

    /** Encens requis pour obtenir ce bebe (null si aucun). */
    public static List<String> incenseFor(Species baby) {
        for (var e : cfg().incense_babies.entrySet()) if (matches(baby, e.getKey())) return e.getValue();
        return null;
    }

    /** Espece et forme de base de l'oeuf, avant la regle des formes regionales. */
    public static FormData baseOffspring(Pokemon mother, Pokemon father, Random rnd) {
        List<FormData> chain = lineage(mother.getForm());
        FormData root = chain.get(0);
        List<String> incense = incenseFor(root.getSpecies());
        if (incense != null && chain.size() > 1) {
            String hm = Traits.heldId(mother), hf = Traits.heldId(father);
            if (!incense.contains(hm) && !incense.contains(hf)) root = chain.get(1);
        }
        for (var e : cfg().offspring_variants.entrySet()) {
            if (!matches(root.getSpecies(), e.getKey()) || e.getValue().isEmpty()) continue;
            Species v = byName(e.getValue().get(rnd.nextInt(e.getValue().size())));
            if (v != null) root = v.getStandardForm();
            break;
        }
        return root;
    }

    /** "alola", "galar"... si la forme est une forme regionale heritable, "" sinon. */
    public static String regionalKey(FormData f) {
        if (f == null || f == f.getSpecies().getStandardForm()) return "";
        String n = f.getName().toLowerCase(Locale.ROOT);
        for (String k : cfg().inheritable_form_keywords) if (n.contains(k.toLowerCase(Locale.ROOT))) return n;
        return "";
    }

    private static FormData findForm(Species sp, String formName) {
        if (formName.isEmpty()) return sp.getStandardForm();
        for (FormData f : sp.getForms()) if (f.getName().equalsIgnoreCase(formName)) return f;
        // meme region, autre variante (ex. Tauros de Paldea) : premier mot-cle commun
        for (String k : cfg().inheritable_form_keywords) {
            if (!formName.contains(k)) continue;
            for (FormData f : sp.getForms()) if (f.getName().toLowerCase(Locale.ROOT).contains(k)) return f;
        }
        return null;
    }

    /**
     * Forme de l'oeuf. Pierre Stase portee par un parent (hors Metamorph) : forme de ce parent garantie. Sinon, deux
     * parents de la meme forme regionale : cette forme. Formes ou especes differentes (Metamorph compris) : la forme
     * de la mere avec regional_form_mother_chance (30 %). Une forme absente chez l'espece de l'oeuf est ignoree.
     */
    public static FormData chooseForm(FormData base, Pokemon mother, Pokemon father, Pokemon everstoneParent, Random rnd, List<String> notes) {
        Species sp = base.getSpecies();
        if (everstoneParent != null && !Traits.isDitto(everstoneParent)) {
            String k = regionalKey(everstoneParent.getForm());
            FormData f = findForm(sp, k);
            if (f != null) {
                if (!k.isEmpty()) notes.add("Pierre Stase : forme " + k + " transmise");
                return f;
            }
        }
        String km = Traits.isDitto(mother) ? "" : regionalKey(mother.getForm());
        String kf = Traits.isDitto(father) ? "" : regionalKey(father.getForm());
        if (!km.isEmpty() && km.equals(kf)) {
            FormData f = findForm(sp, km);
            return f != null ? f : base;
        }
        if (!km.isEmpty() && rnd.nextDouble() < cfg().regional_form_mother_chance) {
            FormData f = findForm(sp, km);
            if (f != null) return f;
        }
        // forme de base de la lignee (ex. Raichu d'Alola -> Pichu normal)
        return sp.getStandardForm();
    }

    // ---------------------------------------------------------------- creation

    public static Pokemon create(FormData form) {
        Species sp = form.getSpecies();
        Pokemon child;
        try {
            child = PokemonProperties.Companion.parse("species=" + sp.getResourceIdentifier() + " level=1").create();
        } catch (Throwable t) {
            child = PokemonProperties.Companion.parse(sp.getName().toLowerCase(Locale.ROOT) + " level=1").create();
        }
        if (child.getSpecies() != sp) child.setSpecies(sp);
        applyForm(child, form);
        if (child.getLevel() != 1) child.setLevel(1);
        return child;
    }

    /** Forme via ses aspects (meme methode que l'editeur de CobblePulse Tools). */
    public static void applyForm(Pokemon pk, FormData target) {
        Species s = pk.getSpecies();
        Set<String> forced = new HashSet<>(pk.getForcedAspects());
        for (FormData f : s.getForms()) forced.removeAll(f.getAspects());
        forced.addAll(target.getAspects());
        pk.setForcedAspects(forced);
        pk.updateAspects();
        if (pk.getForm() != target) pk.setForm(target);
    }

    /** Ditto x Ditto : l'oeuf est un Metamorph. */
    public static Result breed(Compatibility.Check c, Random rnd) {
        Pokemon mother = c.mother(), father = c.father();
        List<String> notes = new ArrayList<>();
        Pokemon[] parents = {mother, father};
        // Pierre Stase : nature (et forme) du porteur ; deux porteurs = 50/50
        List<Pokemon> stones = new ArrayList<>();
        for (Pokemon p : parents) if (Traits.holds(p, Traits.EVERSTONE)) stones.add(p);
        Pokemon stoneParent = stones.isEmpty() ? null : stones.get(rnd.nextInt(stones.size()));

        FormData base = baseOffspring(mother, father, rnd);
        FormData form = chooseForm(base, mother, father, stoneParent, rnd, notes);
        Pokemon child = create(form);

        // ---- IV
        boolean knot = Traits.holds(mother, Traits.DESTINY_KNOT) || Traits.holds(father, Traits.DESTINY_KNOT);
        int inherit = knot ? cfg().destiny_knot_ivs : cfg().default_inherited_ivs;
        int[] ivs = new int[6];
        boolean[] done = new boolean[6];
        List<Pokemon> power = new ArrayList<>();
        for (Pokemon p : parents) if (Traits.powerStat(p) >= 0) power.add(p);
        int used = 0;
        if (!power.isEmpty() && inherit > 0) {
            // un seul objet Pouvoir applique ; deux porteurs : chacun a 50 % d'etre celui retenu
            Pokemon holder = power.get(rnd.nextInt(power.size()));
            int st = Traits.powerStat(holder);
            ivs[st] = holder.getIvs().getOrDefault(STATS[st]);
            done[st] = true;
            used++;
            notes.add("Objet Pouvoir : IV " + fr.cobblepulse.ranch.util.Names.STAT_LABELS[st] + " transmis");
        }
        List<Integer> order = new ArrayList<>(List.of(0, 1, 2, 3, 4, 5));
        Collections.shuffle(order, rnd);
        for (int st : order) {
            if (used >= inherit) break;
            if (done[st]) continue;
            Pokemon from = parents[rnd.nextInt(2)];
            ivs[st] = from.getIvs().getOrDefault(STATS[st]);
            done[st] = true;
            used++;
        }
        for (int i = 0; i < 6; i++) {
            if (!done[i]) ivs[i] = rnd.nextInt(32);
            child.getIvs().set(STATS[i], ivs[i]);
        }

        // ---- nature
        Nature nature = null;
        if (stoneParent != null && rnd.nextDouble() < cfg().everstone_nature_chance) {
            nature = stoneParent.getNature();
            notes.add("Pierre Stase : nature transmise");
        }
        if (nature == null) nature = Natures.getRandomNature();
        child.setNature(nature);
        child.setMintedNature(null);

        // ---- talent
        applyAbility(child, mother, rnd);

        // ---- Poke Ball de la mere (ou du parent non-Metamorph)
        PokeBall ball = mother.getCaughtBall();
        if (ball == null || cfg().non_inheritable_balls.contains(ball.getName().toString())) ball = PokeBalls.getPokeBall();
        child.setCaughtBall(ball);

        // ---- capacites oeuf (au moins un parent les connait)
        List<MoveTemplate> eggMoves = new ArrayList<>(child.getForm().getMoves().getEggMoves());
        Set<String> eggNames = new HashSet<>();
        for (MoveTemplate m : eggMoves) eggNames.add(m.getName());
        LinkedHashSet<MoveTemplate> learned = new LinkedHashSet<>();
        for (Pokemon p : parents) {
            for (Move m : p.getMoveSet().getMoves()) if (eggNames.contains(m.getTemplate().getName())) learned.add(m.getTemplate());
        }
        List<MoveTemplate> finalMoves = new ArrayList<>();
        for (MoveTemplate m : learned) if (finalMoves.size() < 4) finalMoves.add(m);
        for (Move m : child.getMoveSet().getMoves()) {
            if (finalMoves.size() >= 4) break;
            boolean dup = false;
            for (MoveTemplate x : finalMoves) dup |= x.getName().equals(m.getTemplate().getName());
            if (!dup) finalMoves.add(m.getTemplate());
        }
        for (int i = 0; i < 4; i++) child.getMoveSet().setMove(i, i < finalMoves.size() ? finalMoves.get(i).create() : null);
        List<String> eggMoveNames = new ArrayList<>();
        for (MoveTemplate m : learned) eggMoveNames.add(m.getName());

        // ---- Herbe Miroir : le porteur apprend les capacites oeuf (de sa propre espece) connues du partenaire
        boolean changed = false;
        for (int i = 0; i < 2; i++) {
            Pokemon holder = parents[i], other = parents[1 - i];
            if (!Traits.holds(holder, Traits.MIRROR_HERB)) continue;
            Set<String> own = new HashSet<>();
            for (MoveTemplate m : holder.getForm().getMoves().getEggMoves()) own.add(m.getName());
            Set<String> knows = new HashSet<>();
            for (Move m : holder.getMoveSet().getMoves()) knows.add(m.getTemplate().getName());
            for (BenchedMove b : holder.getBenchedMoves()) knows.add(b.getMoveTemplate().getName());
            for (Move m : other.getMoveSet().getMoves()) {
                String n = m.getTemplate().getName();
                if (!own.contains(n) || knows.contains(n)) continue;
                if (holder.getMoveSet().hasSpace()) holder.getMoveSet().add(m.getTemplate().create());
                else holder.getBenchedMoves().add(new BenchedMove(m.getTemplate(), 0));
                knows.add(n);
                changed = true;
                notes.add("Herbe Miroir : " + fr.cobblepulse.ranch.util.Names.species(holder.getSpecies()) + " apprend "
                        + fr.cobblepulse.ranch.util.Names.move(m.getTemplate()));
            }
        }

        // ---- chromatique (Masuda)
        boolean masuda = masuda(mother, father);
        double odds = (masuda ? cfg().masuda_shiny_multiplier : 1.0) / cfg().base_shiny_rate;
        child.setShiny(rnd.nextDouble() < odds);

        child.heal();
        return new Result(child, notes, changed, masuda, eggMoveNames);
    }

    /** Talent : 30 % le talent exact de la mere (cache compris) ; sinon un talent normal de l'espece. */
    static void applyAbility(Pokemon child, Pokemon mother, Random rnd) {
        List<PotentialAbility> commons = new ArrayList<>(), hiddens = new ArrayList<>();
        for (PotentialAbility a : child.getForm().getAbilities()) (a instanceof HiddenAbility ? hiddens : commons).add(a);
        List<PotentialAbility> motherCommons = new ArrayList<>();
        boolean motherHidden = false;
        String motherAbility = mother.getAbility().getName();
        for (PotentialAbility a : mother.getForm().getAbilities()) {
            if (a instanceof HiddenAbility) {
                if (a.getTemplate().getName().equals(motherAbility)) motherHidden = true;
            } else motherCommons.add(a);
        }
        PotentialAbility pick = null;
        if (rnd.nextDouble() < cfg().mother_ability_inheritance_chance) {
            if (motherHidden && !hiddens.isEmpty()) pick = hiddens.get(0);
            else if (!motherHidden) {
                int idx = -1;
                for (int i = 0; i < motherCommons.size(); i++) if (motherCommons.get(i).getTemplate().getName().equals(motherAbility)) idx = i;
                for (PotentialAbility a : commons) if (a.getTemplate().getName().equals(motherAbility)) pick = a;
                if (pick == null && idx >= 0 && idx < commons.size()) pick = commons.get(idx);
            }
        }
        if (pick == null) {
            List<PotentialAbility> pool = new ArrayList<>(commons);
            if (!cfg().strict_mother_ha_required) pool.addAll(hiddens);
            if (pool.isEmpty()) pool.addAll(hiddens);
            if (!pool.isEmpty()) pick = pool.get(rnd.nextInt(pool.size()));
        }
        if (pick == null) return;
        AbilityTemplate tpl = pick.getTemplate();
        Priority prio = pick.getPriority();
        child.updateAbility(tpl.create(false, prio));
    }

    public static boolean isHidden(Pokemon p) {
        String n = p.getAbility().getName();
        for (PotentialAbility a : p.getForm().getAbilities()) if (a instanceof HiddenAbility && a.getTemplate().getName().equals(n)) return true;
        return false;
    }

    /** Methode Masuda : DO de langues differentes (mode LANGUAGE) ou DO differents (mode TRAINER). */
    public static boolean masuda(Pokemon a, Pokemon b) {
        UUID oa = Traits.originalTrainer(a), ob = Traits.originalTrainer(b);
        if (oa == null || ob == null) return false;
        if ("TRAINER".equalsIgnoreCase(cfg().masuda_mode)) return !oa.equals(ob);
        PlayerStore.Rec ra = PlayerStore.peek(oa), rb = PlayerStore.peek(ob);
        String la = ra == null ? "" : ra.language, lb = rb == null ? "" : rb.language;
        return !la.isEmpty() && !lb.isEmpty() && !la.equalsIgnoreCase(lb);
    }
}
