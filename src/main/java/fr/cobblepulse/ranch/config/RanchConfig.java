package fr.cobblepulse.ranch.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * config/cobbleranch/config.json. Les cles du cahier des charges sont reprises telles quelles ; les cles ajoutees
 * (marquees "ajout") completent le fonctionnement et ont une valeur par defaut neutre.
 */
public final class RanchConfig {
    public General general_settings = new General();
    public Environment environment_5x5 = new Environment();
    public Genetics genetics = new Genetics();
    public Hatching hatching_and_items = new Hatching();
    public Cooldowns command_cooldowns_seconds = new Cooldowns();
    /** ajout : /breed a distance (VIP). */
    public RemoteBreed remote_breed = new RemoteBreed();
    /** ajout : castration par le Dresseur d'Origine. */
    public Neuter neutering = new Neuter();

    public static final class General {
        public int max_ranch_blocks_per_chunk = -1;
        /** ajout : limite par dresseur (-1 = aucune). */
        public int max_ranch_blocks_per_player = -1;
        public int slots_per_ranch_block = 3;
        /** desactivee par defaut (choix de Spacer : nuit a la reproduction competitive). */
        public boolean enable_fertility_system = false;
        public int default_fertility_uses = 5;
        public boolean allow_ditto_x_ditto = false;
        /** ajout : oeufs gardes par emplacement avant que la ponte se mette en pause. */
        public int max_eggs_per_slot = 1;
        /** ajout : langue des noms ecrits par le serveur (fiches GTS, journaux). */
        public String language = "fr_fr";
        /** ajout : niveau d'operateur qui remplace les noeuds "staff" quand LuckPerms ne les definit pas. */
        public int op_level_fallback = 2;
    }

    public static final class Environment {
        public int base_breed_duration_seconds = 5400;
        public int min_breed_duration_seconds = 1200;
        public int optimal_points_required = 50;
        public int scan_refresh_cooldown_seconds = 2;
    }

    public static final class Genetics {
        public double mother_ability_inheritance_chance = 0.30;
        public boolean strict_mother_ha_required = true;
        public int base_shiny_rate = 8192;
        public double masuda_shiny_multiplier = 6.0;
        public int destiny_knot_ivs = 5;
        public int default_inherited_ivs = 3;
        public double everstone_nature_chance = 1.0;
        /** ajout : LANGUAGE (langue du client des deux DO, comme le CDC) ou TRAINER (deux DO differents). */
        public String masuda_mode = "LANGUAGE";
        /** ajout : formes regionales (parents de formes ou d'especes differentes) : chance d'heriter de la forme de la mere. */
        public double regional_form_mother_chance = 0.30;
        /** ajout : mots-cles des formes heritables (les autres, Mega, Gigamax, formes de combat... ne passent jamais). */
        public List<String> inheritable_form_keywords = new ArrayList<>(Arrays.asList("alola", "galar", "hisui", "paldea"));
        /** ajout : Balls jamais transmises (remplacees par une Poke Ball). */
        public List<String> non_inheritable_balls = new ArrayList<>(Arrays.asList("cobblemon:master_ball", "cobblemon:cherish_ball"));
        /** ajout : especes ou etiquettes Cobblemon (legendary, mythical, paradox...) qui ne se reproduisent jamais. */
        public List<String> blocked_species = new ArrayList<>();
        public List<String> blocked_labels = new ArrayList<>();
        /** ajout : bebe -> encens requis (sinon l'oeuf donne le stade suivant). Objets d'autres mods acceptes. */
        public Map<String, List<String>> incense_babies = defaultIncense();
        /** ajout : espece de l'oeuf tiree au hasard parmi une liste (Nidoran, Muciole/Lumivole, Manaphy -> Phione). */
        public Map<String, List<String>> offspring_variants = defaultVariants();
    }

    public static final class Hatching {
        public int egg_tick_interval_seconds = 5;
        public double flame_body_hatch_multiplier = 2.0;
        public String silver_hourglass_reduction_mode = "PERCENTAGE";
        public double silver_hourglass_value = 25.0;
        public boolean gold_hourglass_instant_breed = true;
        public boolean gold_hourglass_instant_hatch = true;
        /** ajout : pas d'eclosion par cycle d'oeuf de l'espece (Cobblemon : eggCycles). */
        public int steps_per_egg_cycle = 256;
        /** ajout : pas comptes par bloc parcouru. */
        public double steps_per_block = 1.0;
        /** ajout : distance maximale comptee par intervalle (teleportations ignorees). */
        public double max_blocks_per_interval = 60.0;
        /** ajout : mode FIXED : minutes retirees a la ponte = silver_hourglass_value ; pas retires a un oeuf = cette valeur. */
        public int silver_hourglass_fixed_egg_steps = 1000;
        /** ajout : talents qui accelerent l'eclosion quand le Pokemon est dans l'equipe. */
        public List<String> hatch_boost_abilities = new ArrayList<>(Arrays.asList("flamebody", "magmaarmor", "steamengine"));
    }

    public static final class Cooldowns {
        public int daycare_remote = 300;
        public int breed_command = 3600;
        public int hatch_command = 7200;
        public int eggsee_command = 5;
        /** ajout : anti-spam des autres commandes et des boutons d'action. */
        public int anti_spam = 1;
    }

    public static final class RemoteBreed {
        public int slots = 3;
        public int duration_seconds = 3600;
        /** cout par lancement (0 = gratuit) ; paye via l'economie de CobblePulse Utils si presente. */
        public long cost = 0;
    }

    public static final class Neuter {
        public boolean enabled = true;
        /** le DO peut castrer ; true = seulement si le Pokemon est encore a lui. */
        public boolean require_current_owner = true;
    }

    private static Map<String, List<String>> defaultIncense() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        String[][] t = {{"azurill", "sea"}, {"wynaut", "lax"}, {"budew", "rose"}, {"chingling", "pure"}, {"bonsly", "rock"},
                {"mimejr", "odd"}, {"happiny", "luck"}, {"munchlax", "full"}, {"mantyke", "wave"}};
        for (String[] e : t) m.put(e[0], new ArrayList<>(Arrays.asList("cobblepulse_ranch:" + e[1] + "_incense", "cobblemon:" + e[1] + "_incense")));
        return m;
    }

    private static Map<String, List<String>> defaultVariants() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("nidoranf", new ArrayList<>(Arrays.asList("nidoranf", "nidoranm")));
        m.put("nidoranm", new ArrayList<>(Arrays.asList("nidoranf", "nidoranm")));
        m.put("volbeat", new ArrayList<>(Arrays.asList("volbeat", "illumise")));
        m.put("illumise", new ArrayList<>(Arrays.asList("volbeat", "illumise")));
        m.put("manaphy", new ArrayList<>(List.of("phione")));
        return m;
    }
}
