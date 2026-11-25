package fr.cobblepulse.ranch.service;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.pokemon.IVs;
import com.cobblemon.mod.common.pokemon.Pokemon;
import fr.cobblepulse.ranch.CobbleRanch;
import fr.cobblepulse.ranch.breeding.Genetics;
import fr.cobblepulse.ranch.config.ConfigManager;
import fr.cobblepulse.ranch.item.BottleCapItem;
import fr.cobblepulse.ranch.perm.Perms;
import fr.cobblepulse.ranch.util.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Capsules d'Argent et d'Or (Entrainement Ultime), comme dans les jeux : la statistique choisie (Argent) ou toutes
 * (Or) passent a 31 EN COMBAT. L'IV d'origine ne change pas : c'est lui que la reproduction transmet (Noeud Destin,
 * Objets Pouvoir). On s'appuie sur le stockage natif de Cobblemon (IVs.hyperTrainedIVs, Pokemon.hyperTrainIV, qui
 * declenche HYPER_TRAINED_IV_PRE/POST) : calcul des statistiques, combats et resume Cobblemon en tiennent compte.
 */
public final class BottleCaps {
    private BottleCaps() {}

    public static final String[] KEYS = {"pv", "attaque", "defense", "attaque_spe", "defense_spe", "vitesse"};
    public static final String[] NAMES = {"PV", "Attaque", "Défense", "Attaque Spé.", "Défense Spé.", "Vitesse"};
    public static final String[] SHORT = {"PV", "Atk", "Def", "SpA", "SpD", "Spe"};
    private static final String[][] ALIASES = {{"hp"}, {"atk", "attack", "att"}, {"def", "defence", "defense"},
            {"spa", "special_attack", "atk_spe", "attspe"}, {"spd", "special_defence", "special_defense", "def_spe", "defspe"}, {"spe", "speed", "vit"}};
    public static final String ALL = "tout";

    public static int statIndex(String s) {
        String k = s.toLowerCase(Locale.ROOT).replace('é', 'e').replace('-', '_').replace('.', ' ').trim().replace(' ', '_');
        for (int i = 0; i < 6; i++) {
            if (KEYS[i].equals(k) || SHORT[i].equalsIgnoreCase(k)) return i;
            for (String a : ALIASES[i]) if (a.equals(k)) return i;
        }
        return -1;
    }

    public static int minLevel() {
        int max = Cobblemon.INSTANCE.getConfig().getMaxPokemonLevel();
        return Math.max(1, Math.min(ConfigManager.get().bottle_caps.min_level, max));
    }

    /** Statistique encore ameliorable : moins de 31 en combat (IV d'origine ou entrainement partiel par bonbons). */
    public static boolean trainable(Pokemon p, int i) {
        return p.getIvs().getEffectiveBattleIV(Genetics.STATS[i]) < IVs.MAX_VALUE;
    }

    public static List<Integer> trainableStats(Pokemon p) {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < 6; i++) if (trainable(p, i)) l.add(i);
        return l;
    }

    /** Statistiques entrainees par capsule ou bonbon (cle = index, valeur = IV en combat). */
    public static Map<Integer, Integer> hyperTrained(Pokemon p) {
        Map<Integer, Integer> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 6; i++) if (p.getIvs().isHyperTrained(Genetics.STATS[i])) m.put(i, p.getIvs().getEffectiveBattleIV(Genetics.STATS[i]));
        return m;
    }

    /** "Atk, Spe" (ou "Atk 20" pour un entrainement partiel par bonbons) ; vide si aucun. */
    public static String hyperLabel(Pokemon p) {
        List<String> l = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : hyperTrained(p).entrySet())
            l.add(SHORT[e.getKey()] + (e.getValue() >= IVs.MAX_VALUE ? "" : " " + e.getValue()));
        return String.join(", ", l);
    }

    /** null si la capsule est utilisable sur ce Pokemon, sinon la raison. */
    public static String check(Pokemon p, boolean gold, int stat) {
        if (!ConfigManager.get().bottle_caps.enabled) return "Les Capsules sont désactivées sur ce serveur.";
        if (p == null) return "Emplacement d'équipe vide.";
        if (p.getLevel() < minLevel())
            return RanchService.label(p) + " doit être au niveau " + minLevel() + " pour l'Entraînement Ultime (niveau " + p.getLevel() + ").";
        if (gold) return trainableStats(p).isEmpty() ? "Toutes les statistiques de " + RanchService.label(p) + " sont déjà au maximum." : null;
        if (stat < 0) return trainableStats(p).isEmpty() ? "Toutes les statistiques de " + RanchService.label(p) + " sont déjà au maximum." : null;
        return trainable(p, stat) ? null : NAMES[stat] + " de " + RanchService.label(p) + " est déjà à 31.";
    }

    /** Entrainement Ultime : stat = index (Argent) ou -1 (Or, toutes). Renvoie les statistiques effectivement montees a 31. */
    public static List<Integer> apply(Pokemon p, int stat) {
        List<Integer> done = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            if (stat >= 0 && i != stat) continue;
            if (!trainable(p, i)) continue;
            p.hyperTrainIV(Genetics.STATS[i], IVs.MAX_VALUE);
            if (p.getIvs().getEffectiveBattleIV(Genetics.STATS[i]) >= IVs.MAX_VALUE) done.add(i);
        }
        return done;
    }

    // ================================================================================================= joueur

    /** Main qui tient une capsule (principale d'abord), ou null. */
    public static InteractionHand capHand(ServerPlayer p) {
        if (p.getMainHandItem().getItem() instanceof BottleCapItem) return InteractionHand.MAIN_HAND;
        if (p.getOffhandItem().getItem() instanceof BottleCapItem) return InteractionHand.OFF_HAND;
        return null;
    }

    private static String idOf(Pokemon p) {
        return p.getUuid().toString().substring(0, 8);
    }

    /** Liste cliquable de l'equipe. */
    public static int partyMenu(ServerPlayer p) {
        InteractionHand hand = capHand(p);
        if (hand == null) {
            Msg.err(p, "Tiens une Capsule d'Argent ou une Capsule d'Or en main.");
            return 0;
        }
        boolean gold = ((BottleCapItem) p.getItemInHand(hand).getItem()).gold();
        PlayerPartyStore party = Stores.party(p);
        Msg.send(p, Component.literal((gold ? "Capsule d'Or" : "Capsule d'Argent") + " — choisis un Pokémon de ton équipe :").withStyle(ChatFormatting.YELLOW));
        for (int s = 0; s < 6; s++) {
            Pokemon pk = party.get(s);
            if (pk == null) continue;
            String err = check(pk, gold, -1);
            MutableComponent line = Component.literal("  [" + (s + 1) + "] " + RanchService.label(pk) + " Niv. " + pk.getLevel());
            if (err == null) {
                String cmd = "/capsule " + (s + 1);
                line = line.withStyle(st -> st.withColor(ChatFormatting.GREEN).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(gold
                                ? "Ouvrir l'Entraînement Ultime (toutes les statistiques)" : "Ouvrir l'Entraînement Ultime (choix de la statistique)"))));
                line.append(Component.literal("  " + trainableStats(pk).size() + " stat(s) à entraîner").withStyle(ChatFormatting.DARK_GREEN));
            } else {
                line = line.withStyle(ChatFormatting.DARK_GRAY);
                line.append(Component.literal("  " + err).withStyle(ChatFormatting.DARK_GRAY));
            }
            p.sendSystemMessage(line);
        }
        return 1;
    }

    /** /capsule <emplacement> [stat|tout] [id] */
    public static int use(ServerPlayer p, int slot, String statArg, String id) {
        if (!Perms.has(p, Perms.CMD_CAPSULE)) {
            Msg.err(p, "Tu n'as pas la permission d'utiliser les Capsules.");
            return 0;
        }
        InteractionHand hand = capHand(p);
        if (hand == null) {
            Msg.err(p, "Tiens une Capsule d'Argent ou une Capsule d'Or en main.");
            return 0;
        }
        ItemStack stack = p.getItemInHand(hand);
        boolean gold = ((BottleCapItem) stack.getItem()).gold();
        if (BattleRegistry.getBattleByParticipatingPlayer(p) != null) {
            Msg.err(p, "Impossible pendant un combat.");
            return 0;
        }
        PlayerPartyStore party = Stores.party(p);
        Pokemon pk = party.get(slot - 1);
        if (pk != null && id != null && !id.isEmpty() && !idOf(pk).equalsIgnoreCase(id)) {
            Msg.err(p, "Ton équipe a changé : choisis à nouveau le Pokémon.");
            return partyMenu(p);
        }
        int stat = -1;
        if (statArg != null && !statArg.isEmpty() && !statArg.equalsIgnoreCase(ALL)) {
            stat = statIndex(statArg);
            if (stat < 0) {
                Msg.err(p, "Statistique inconnue : " + statArg + " (pv, attaque, defense, attaque_spe, defense_spe, vitesse).");
                return 0;
            }
            if (gold) {
                Msg.err(p, "La Capsule d'Or entraîne toutes les statistiques : /capsule " + slot + " " + ALL);
                return 0;
            }
        }
        String err = check(pk, gold, stat);
        if (err != null) {
            Msg.err(p, err);
            return 0;
        }
        if (statArg == null || statArg.isEmpty()) return openScreen(p, slot, pk, gold, "", true);
        List<Integer> done = apply(pk, gold ? -1 : stat);
        if (done.isEmpty()) {
            Msg.err(p, "L'Entraînement Ultime de " + RanchService.label(pk) + " a été refusé.");
            return 0;
        }
        if (!p.getAbilities().instabuild) stack.shrink(1);
        Stores.savePlayer(p);
        Stores.flush(party);
        p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6f, 1.6f);
        List<String> names = new ArrayList<>();
        List<String> origin = new ArrayList<>();
        for (int i : done) {
            names.add(NAMES[i]);
            origin.add(SHORT[i] + " " + pk.getIvs().getOrDefault(Genetics.STATS[i]));
        }
        Msg.send(p, Component.literal("✦ Entraînement Ultime ! ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(String.join(", ", names) + " de " + RanchService.label(pk) + " : 31 en combat.").withStyle(ChatFormatting.GREEN)));
        Msg.info(p, "IV d'origine conservés pour la reproduction : " + String.join(", ", origin) + ".");
        CobbleRanch.LOG.info("[CobblePulse-Ranch] {} : Capsule {} sur {} ({}) -> {}", p.getGameProfile().getName(), gold ? "d'Or" : "d'Argent",
                RanchService.label(pk), pk.getUuid(), String.join(",", names));
        return 1;
    }

    /** Ecran de l'Entrainement Ultime (Argent : choix de la statistique ; Or : confirmation). */
    public static int openScreen(ServerPlayer p, int slot, Pokemon pk, boolean gold, String flash, boolean ok) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("slot", slot);
        o.addProperty("id", idOf(pk));
        o.addProperty("name", RanchService.label(pk));
        o.addProperty("species_key", fr.cobblepulse.ranch.util.Names.speciesKey(pk.getSpecies()));
        o.addProperty("level", pk.getLevel());
        o.addProperty("gold", gold);
        InteractionHand hand = capHand(p);
        o.addProperty("count", hand == null ? 0 : p.getItemInHand(hand).getCount());
        o.addProperty("flash", flash);
        o.addProperty("flash_ok", ok);
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        for (int i = 0; i < 6; i++) {
            com.google.gson.JsonObject j = new com.google.gson.JsonObject();
            Stat st = Genetics.STATS[i];
            j.addProperty("key", KEYS[i]);
            j.addProperty("name", NAMES[i]);
            j.addProperty("base", pk.getIvs().getOrDefault(st));
            j.addProperty("eff", pk.getIvs().getEffectiveBattleIV(st));
            j.addProperty("trainable", trainable(pk, i));
            a.add(j);
        }
        o.add("stats", a);
        fr.cobblepulse.ranch.net.RanchNet.send(p, "capsule", o);
        return 1;
    }

    /** Clic droit sur un Pokemon sorti avec une capsule : l'ecran s'ouvre pour ce Pokemon. */
    public static void onPokemon(ServerPlayer p, com.cobblemon.mod.common.entity.pokemon.PokemonEntity pe) {
        Pokemon pk = pe.getPokemon();
        if (!p.getUUID().equals(pk.getOwnerUUID())) {
            Msg.err(p, "Ce Pokémon n'est pas à toi.");
            return;
        }
        PlayerPartyStore party = Stores.party(p);
        for (int s = 0; s < 6; s++) {
            Pokemon q = party.get(s);
            if (q != null && q.getUuid().equals(pk.getUuid())) {
                use(p, s + 1, null, null);
                return;
            }
        }
        Msg.err(p, "La Capsule s'utilise sur un Pokémon de ton équipe.");
    }

    /** Bouton de l'ecran : applique, puis rafraichit l'ecran (autre capsule en main) ou le ferme. */
    public static void screenAction(ServerPlayer p, int slot, String stat, String id) {
        int r = use(p, slot, stat, id);
        Pokemon pk = Stores.party(p).get(slot - 1);
        InteractionHand hand = capHand(p);
        if (r > 0 && pk != null && hand != null && check(pk, ((BottleCapItem) p.getItemInHand(hand).getItem()).gold(), -1) == null) {
            openScreen(p, slot, pk, ((BottleCapItem) p.getItemInHand(hand).getItem()).gold(), "Entraînement Ultime réussi !", true);
            return;
        }
        fr.cobblepulse.ranch.net.RanchNet.send(p, "capsule_close", new com.google.gson.JsonObject());
    }
}
