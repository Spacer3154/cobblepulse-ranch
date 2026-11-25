package fr.cobblepulse.ranch.command;

import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import fr.cobblepulse.ranch.CobbleRanch;
import fr.cobblepulse.ranch.breeding.Compatibility;
import fr.cobblepulse.ranch.breeding.EggData;
import fr.cobblepulse.ranch.breeding.Genetics;
import fr.cobblepulse.ranch.breeding.Traits;
import fr.cobblepulse.ranch.compat.UtilsEconomy;
import fr.cobblepulse.ranch.config.ConfigManager;
import fr.cobblepulse.ranch.config.RanchConfig;
import fr.cobblepulse.ranch.data.PlayerStore;
import fr.cobblepulse.ranch.data.RanchData;
import fr.cobblepulse.ranch.data.SlotData;
import fr.cobblepulse.ranch.net.RanchNet;
import fr.cobblepulse.ranch.net.Views;
import fr.cobblepulse.ranch.perm.Perms;
import fr.cobblepulse.ranch.registry.ModContent;
import fr.cobblepulse.ranch.service.AdminService;
import fr.cobblepulse.ranch.service.HatchService;
import fr.cobblepulse.ranch.service.RanchService;
import fr.cobblepulse.ranch.service.Stores;
import fr.cobblepulse.ranch.test.SelfTest;
import fr.cobblepulse.ranch.util.Msg;
import fr.cobblepulse.ranch.util.Names;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/** /daycare, /breed, /hatch, /eggsee (commandes en chat), /castrer et /cobbleranch (staff). */
public final class RanchCommands {
    private RanchCommands() {}

    private interface PlayerAction {
        int run(ServerPlayer p) throws Exception;
    }

    private static int run(CommandContext<CommandSourceStack> ctx, PlayerAction a) {
        ServerPlayer p = ctx.getSource().getPlayer();
        if (p == null) {
            ctx.getSource().sendFailure(Component.literal("Commande réservée aux joueurs."));
            return 0;
        }
        try {
            return a.run(p);
        } catch (Exception e) {
            CobbleRanch.LOG.error("[CobblePulse-Ranch] Erreur de commande", e);
            Msg.err(p, "Erreur : " + e.getMessage());
            return 0;
        }
    }

    /** Anti-spam / delai : true si l'action peut continuer (et lance le delai). */
    private static boolean cooldown(ServerPlayer p, String key, int seconds) {
        if (seconds <= 0 || Perms.has(p, Perms.BYPASS_COOLDOWN)) return true;
        long left = PlayerStore.remaining(p.getUUID(), key);
        if (left > 0) {
            Msg.err(p, "Patiente encore " + Msg.duration(left) + ".");
            return false;
        }
        PlayerStore.startCooldown(p.getUUID(), key, seconds);
        return true;
    }

    private static RanchConfig.Cooldowns cd() {
        return ConfigManager.get().command_cooldowns_seconds;
    }

    public static void register(CommandDispatcher<CommandSourceStack> d) {

        // ---------------- /daycare
        d.register(literal("daycare").requires(s -> Perms.has(s, Perms.CMD_DAYCARE))
                .executes(ctx -> run(ctx, p -> {
                    if (!cooldown(p, "daycare", cd().anti_spam)) return 0;
                    RanchNet.send(p, "daycare", Views.daycare(p));
                    return 1;
                })));

        // ---------------- /breed <a> <b> [lancer]
        d.register(literal("breed").requires(s -> Perms.has(s, Perms.CMD_BREED))
                .executes(ctx -> run(ctx, p -> {
                    Msg.info(p, "Usage : /breed <emplacement 1-6> <emplacement 1-6>" + (Perms.has(p, Perms.CMD_BREED_VIP) ? " [lancer]" : ""));
                    return 0;
                }))
                .then(argument("a", IntegerArgumentType.integer(1, 6)).then(argument("b", IntegerArgumentType.integer(1, 6))
                        .executes(ctx -> run(ctx, p -> breed(p, IntegerArgumentType.getInteger(ctx, "a"), IntegerArgumentType.getInteger(ctx, "b"), false)))
                        .then(literal("lancer").executes(ctx -> run(ctx, p -> breed(p, IntegerArgumentType.getInteger(ctx, "a"),
                                IntegerArgumentType.getInteger(ctx, "b"), true)))))));

        // ---------------- /hatch [instant]
        d.register(literal("hatch").requires(s -> Perms.has(s, Perms.CMD_HATCH))
                .executes(ctx -> run(ctx, RanchCommands::hatchInfo))
                .then(literal("instant").executes(ctx -> run(ctx, RanchCommands::hatchInstant))));

        // ---------------- /eggsee
        d.register(literal("eggsee").requires(s -> Perms.has(s, Perms.CMD_EGGSEE)).executes(ctx -> run(ctx, RanchCommands::eggsee)));

        // ---------------- /castrer <emplacement> [confirmer]  (alias /neuter)
        for (String name : new String[]{"castrer", "neuter"}) {
            d.register(literal(name).requires(s -> Perms.has(s, Perms.NEUTER))
                    .then(argument("emplacement", IntegerArgumentType.integer(1, 6))
                            .executes(ctx -> run(ctx, p -> neuter(p, IntegerArgumentType.getInteger(ctx, "emplacement"), false)))
                            .then(literal("confirmer").executes(ctx -> run(ctx, p -> neuter(p, IntegerArgumentType.getInteger(ctx, "emplacement"), true))))));
        }

        // ---------------- /capsule [emplacement] [stat|tout] [id]  (alias /bottlecap)
        for (String name : new String[]{"capsule", "bottlecap"}) {
            d.register(literal(name).requires(s -> Perms.has(s, Perms.CMD_CAPSULE))
                    .executes(ctx -> run(ctx, fr.cobblepulse.ranch.service.BottleCaps::partyMenu))
                    .then(argument("emplacement", IntegerArgumentType.integer(1, 6))
                            .executes(ctx -> run(ctx, p -> fr.cobblepulse.ranch.service.BottleCaps.use(p, IntegerArgumentType.getInteger(ctx, "emplacement"), null, null)))
                            .then(argument("stat", StringArgumentType.word())
                                    .suggests((c, b) -> SharedSuggestionProvider.suggest(capStats(), b))
                                    .executes(ctx -> run(ctx, p -> fr.cobblepulse.ranch.service.BottleCaps.use(p, IntegerArgumentType.getInteger(ctx, "emplacement"),
                                            StringArgumentType.getString(ctx, "stat"), null)))
                                    .then(argument("id", StringArgumentType.word())
                                            .executes(ctx -> run(ctx, p -> fr.cobblepulse.ranch.service.BottleCaps.use(p, IntegerArgumentType.getInteger(ctx, "emplacement"),
                                                    StringArgumentType.getString(ctx, "stat"), StringArgumentType.getString(ctx, "id"))))))));
        }

        // ---------------- /cobbleranch
        LiteralArgumentBuilder<CommandSourceStack> root = literal("cobbleranch")
                .executes(ctx -> run(ctx, p -> {
                    Msg.info(p, "CobblePulse Ranch " + CobbleRanch.version() + " — /daycare, /breed, /hatch, /eggsee, /castrer, /capsule, /cobbleranch guide");
                    return 1;
                }))
                .then(literal("guide").executes(ctx -> run(ctx, p -> {
                    RanchNet.send(p, "guide", Views.guide());
                    return 1;
                })))
                .then(literal("admin").requires(s -> Perms.has(s, Perms.ADMIN_GUI)).executes(ctx -> run(ctx, p -> {
                    AdminService.open(p, "", "active", "player", null);
                    return 1;
                })))
                .then(literal("reload").requires(s -> Perms.has(s, Perms.ADMIN_RULES_OVERRIDE)).executes(ctx -> {
                    CobbleRanch.reload();
                    ctx.getSource().sendSuccess(() -> Component.literal("[CobbleRanch] Configuration rechargée"
                            + (ConfigManager.warnings().isEmpty() ? "" : " : " + String.join(" | ", ConfigManager.warnings()))), true);
                    return 1;
                }))
                .then(literal("give").requires(s -> Perms.has(s, Perms.ADMIN_MODIFY))
                        .then(argument("joueur", EntityArgument.player()).then(argument("objet", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(giveIds(), b))
                                .executes(ctx -> give(ctx, 1))
                                .then(argument("nombre", IntegerArgumentType.integer(1, 64)).executes(ctx -> give(ctx, IntegerArgumentType.getInteger(ctx, "nombre")))))))
                .then(literal("fertilite").requires(s -> Perms.has(s, Perms.ADMIN_MODIFY))
                        .then(argument("joueur", EntityArgument.player()).then(argument("emplacement", IntegerArgumentType.integer(1, 6))
                                .then(argument("valeur", IntegerArgumentType.integer(0, 999)).executes(ctx -> partyEdit(ctx, pk -> {
                                    Traits.setFertility(pk, IntegerArgumentType.getInteger(ctx, "valeur"));
                                    return "fertilité " + IntegerArgumentType.getInteger(ctx, "valeur");
                                }))))))
                .then(literal("castration").requires(s -> Perms.has(s, Perms.ADMIN_MODIFY))
                        .then(argument("joueur", EntityArgument.player()).then(argument("emplacement", IntegerArgumentType.integer(1, 6))
                                .then(literal("oui").executes(ctx -> partyEdit(ctx, pk -> {
                                    Traits.setNeutered(pk, true, null);
                                    return "castré";
                                })))
                                .then(literal("non").executes(ctx -> partyEdit(ctx, pk -> {
                                    Traits.setNeutered(pk, false, null);
                                    return "castration retirée";
                                }))))));
        if (SelfTest.ENABLED) root.then(SelfTest.commands());
        d.register(root);
    }

    // ================================================================================================= /breed

    private static MutableComponent line(String label, String value) {
        return Component.literal("• " + label + " : ").withStyle(ChatFormatting.GRAY).append(Component.literal(value).withStyle(ChatFormatting.WHITE));
    }

    private static String describe(Pokemon p) {
        StringBuilder b = new StringBuilder(RanchService.label(p) + " (Nv. " + p.getLevel() + ")");
        List<String> g = new ArrayList<>();
        for (var e : Traits.eggGroups(p)) g.add(Names.eggGroup(e.name()));
        b.append(" | Groupes : ").append(String.join(", ", g));
        if (Traits.neutered(p)) b.append(" | CASTRÉ");
        else if (Traits.fertilityEnabled()) b.append(" | Fertilité ").append(Traits.fertility(p)).append("/").append(Traits.maxFertility());
        String eff = Traits.breedingItemEffect(p);
        if (!p.heldItem().isEmpty()) b.append(" | Objet : ").append(Names.item(p.heldItem())).append(eff.isEmpty() ? "" : " (" + eff + ")");
        return b.toString();
    }

    static int breed(ServerPlayer p, int ia, int ib, boolean start) {
        if (!cooldown(p, "breed_analysis", cd().anti_spam)) return 0;
        PlayerPartyStore party = Stores.party(p);
        Pokemon a = party.get(ia - 1), b = party.get(ib - 1);
        if (a == null || b == null) {
            Msg.err(p, "Emplacement d'équipe vide (" + (a == null ? ia : ib) + ").");
            return 0;
        }
        boolean bypass = Perms.has(p, Perms.BYPASS_FERTILITY);
        Compatibility.Check c = Compatibility.check(a, b, bypass);
        RanchConfig cfg = ConfigManager.get();
        p.sendSystemMessage(Msg.prefix().append(Component.literal("--- ANALYSE DE COMPATIBILITÉ ---").withStyle(ChatFormatting.YELLOW)));
        p.sendSystemMessage(line("Parent " + ia, describe(a)));
        p.sendSystemMessage(line("Parent " + ib, describe(b)));
        if (!c.ok()) {
            p.sendSystemMessage(Component.literal("• Résultat : INCOMPATIBLE ✖ — " + c.reason()).withStyle(ChatFormatting.RED));
        } else {
            List<String> g = new ArrayList<>();
            for (var e : c.groups()) g.add(Names.eggGroup(e.name()));
            p.sendSystemMessage(Component.literal("• Résultat : COMPATIBLE ✔ (" + String.join(", ", g) + ")").withStyle(ChatFormatting.GREEN));
            var base = Genetics.baseOffspring(c.mother(), c.father(), new java.util.Random(0));
            List<String> incense = Genetics.incenseFor(Genetics.lineage(c.mother().getForm()).get(0).getSpecies());
            String egg = Names.species(base.getSpecies());
            if (incense != null && !base.getSpecies().equals(Genetics.lineage(c.mother().getForm()).get(0).getSpecies()))
                egg += " (bébé " + Names.species(Genetics.lineage(c.mother().getForm()).get(0).getSpecies()) + " avec un encens)";
            String km = Genetics.regionalKey(c.mother().getForm()), kf = Traits.isDitto(c.father()) ? "" : Genetics.regionalKey(c.father().getForm());
            if (!km.isEmpty()) egg += km.equals(kf) ? " — forme " + km + " garantie" : " — forme " + km + " : " + Math.round(cfg.genetics.regional_form_mother_chance * 100) + " %";
            p.sendSystemMessage(line("Œuf", egg));
            boolean knot = Traits.holds(a, Traits.DESTINY_KNOT) || Traits.holds(b, Traits.DESTINY_KNOT);
            boolean stone = Traits.holds(a, Traits.EVERSTONE) || Traits.holds(b, Traits.EVERSTONE);
            p.sendSystemMessage(line("Génétique", (knot ? cfg.genetics.destiny_knot_ivs : cfg.genetics.default_inherited_ivs) + " IV transmis"
                    + (stone ? " | nature transmise (Pierre Stase)" : " | nature aléatoire")
                    + " | talent de la mère : " + Math.round(cfg.genetics.mother_ability_inheritance_chance * 100) + " %"
                    + (Genetics.isHidden(c.mother()) ? " (talent caché possible)" : " (pas de talent caché)")));
            List<String> caps = new ArrayList<>();
            for (Pokemon x : new Pokemon[]{a, b}) {
                String h = fr.cobblepulse.ranch.service.BottleCaps.hyperLabel(x);
                if (!h.isEmpty()) caps.add(RanchService.label(x) + " (" + h + ")");
            }
            if (!caps.isEmpty())
                p.sendSystemMessage(line("Capsules", String.join(", ", caps) + " : 31 en combat seulement, ce sont les IV d'origine qui sont transmis"));
            boolean masuda = Genetics.masuda(c.mother(), c.father());
            p.sendSystemMessage(line("Chromatique", (masuda ? Math.round(cfg.genetics.masuda_shiny_multiplier) + "/" : "1/") + cfg.genetics.base_shiny_rate
                    + (masuda ? " (méthode Masuda)" : "")));
            p.sendSystemMessage(line("Durée en Pension", Msg.duration(cfg.environment_5x5.min_breed_duration_seconds) + " (habitat optimal) à "
                    + Msg.duration(cfg.environment_5x5.base_breed_duration_seconds) + (Perms.has(p, Perms.CMD_BREED_VIP)
                    ? " | à distance : " + Msg.duration(cfg.remote_breed.duration_seconds) : "")));
        }
        p.sendSystemMessage(Component.literal("--------------------------------------------------").withStyle(ChatFormatting.DARK_GRAY));
        if (!start) {
            if (c.ok() && Perms.has(p, Perms.CMD_BREED_VIP))
                Msg.info(p, "VIP : /breed " + ia + " " + ib + " lancer pour démarrer la reproduction à distance.");
            return c.ok() ? 1 : 0;
        }
        return startRemote(p, ia, ib, a, b, c);
    }

    private static int startRemote(ServerPlayer p, int ia, int ib, Pokemon a, Pokemon b, Compatibility.Check c) {
        if (!Perms.has(p, Perms.CMD_BREED_VIP)) {
            Msg.err(p, "La reproduction à distance est un avantage VIP.");
            return 0;
        }
        if (!c.ok()) return 0;
        boolean bypass = Perms.has(p, Perms.BYPASS_COOLDOWN);
        long left = bypass ? 0 : PlayerStore.remaining(p.getUUID(), "breed_remote");
        if (left > 0) {
            Msg.err(p, "Prochaine reproduction à distance dans " + Msg.duration(left) + ".");
            return 0;
        }
        long cost = ConfigManager.get().remote_breed.cost;
        if (cost > 0 && !UtilsEconomy.has(p.getUUID(), cost)) {
            Msg.err(p, "Il faut " + UtilsEconomy.format(cost) + " pour lancer une reproduction à distance.");
            return 0;
        }
        RanchData r = RanchService.remoteRanch(p);
        int slot = -1;
        for (int i = 0; i < r.slots.size(); i++) if (r.slots.get(i).empty()) {
            slot = i;
            break;
        }
        if (slot < 0) {
            Msg.err(p, "Tes " + r.slots.size() + " emplacements à distance sont occupés (voir /daycare).");
            return 0;
        }
        String e = RanchService.deposit(p, r.id, slot, true, a.getUuid());
        if (e == null) {
            e = RanchService.deposit(p, r.id, slot, false, b.getUuid());
            if (e != null) RanchService.withdraw(p, r.id, slot, true);
        }
        if (e != null) {
            Msg.err(p, "Impossible : " + e);
            return 0;
        }
        if (cost > 0) UtilsEconomy.withdraw(p.getUUID(), cost);
        if (!bypass) PlayerStore.startCooldown(p.getUUID(), "breed_remote", ConfigManager.get().command_cooldowns_seconds.breed_command);
        SlotData s = r.slot(slot);
        Msg.ok(p, "Reproduction à distance lancée (emplacement " + (slot + 1) + ") : premier œuf dans " + Msg.duration(s.duration / 1000)
                + ". Suivi et récupération : /daycare" + (cost > 0 ? " (" + UtilsEconomy.format(cost) + " payés)" : "") + ".");
        return 1;
    }

    // ================================================================================================= /hatch, /eggsee

    private static CompoundTag eggInHand(ServerPlayer p) {
        CompoundTag t = EggData.of(p.getMainHandItem());
        if (t == null) Msg.err(p, "Tiens un Œuf de Pokémon dans ta main principale.");
        return t;
    }

    static int hatchInfo(ServerPlayer p) {
        if (!cooldown(p, "hatch_info", cd().anti_spam)) return 0;
        CompoundTag t = eggInHand(p);
        if (t == null) return 0;
        CompoundTag i = EggData.info(t);
        double prog = EggData.progress(t);
        long left = Math.round(EggData.steps(t));
        p.sendSystemMessage(Msg.prefix().append(Component.literal("--- ÉCLOSION DE L'ŒUF EN MAIN ---").withStyle(ChatFormatting.YELLOW)));
        p.sendSystemMessage(line("Espèce", i.getString("species_name") + " | Progression : " + EggData.bar(prog, 20) + " " + Math.round(prog * 100) + "%"));
        p.sendSystemMessage(line("Pas restants", EggData.fmt(left) + " / " + EggData.fmt(EggData.total(t)) + " | Statut : " + EggData.status(prog)));
        boolean boost = HatchService.boosted(p);
        p.sendSystemMessage(line("Bonus d'éclosion", boost ? "Corps Ardent / Armumagma / Turbine ×" + ConfigManager.get().hatching_and_items.flame_body_hatch_multiplier
                + " (actif)" : "aucun (Corps Ardent, Armumagma ou Turbine dans l'équipe : ×" + ConfigManager.get().hatching_and_items.flame_body_hatch_multiplier + ")"));
        p.sendSystemMessage(line("Sabliers", "Argent ×" + RanchService.count(p, ModContent.SILVER_HOURGLASS) + " | Or ×" + RanchService.count(p, ModContent.GOLD_HOURGLASS)
                + " (clic droit avec le sablier sur l'œuf dans l'inventaire)"));
        if (Perms.has(p, Perms.CMD_HATCH_VIP)) {
            long cdLeft = Perms.has(p, Perms.BYPASS_COOLDOWN) ? 0 : PlayerStore.remaining(p.getUUID(), "hatch_instant");
            p.sendSystemMessage(line("VIP", cdLeft > 0 ? "éclosion instantanée dans " + Msg.duration(cdLeft) : "/hatch instant fait éclore cet œuf maintenant"));
        }
        p.sendSystemMessage(Component.literal("--------------------------------------------------").withStyle(ChatFormatting.DARK_GRAY));
        return 1;
    }

    static int hatchInstant(ServerPlayer p) {
        if (!Perms.has(p, Perms.CMD_HATCH_VIP)) {
            Msg.err(p, "L'éclosion instantanée est un avantage VIP.");
            return 0;
        }
        CompoundTag t = eggInHand(p);
        if (t == null) return 0;
        boolean bypass = Perms.has(p, Perms.BYPASS_COOLDOWN);
        long left = bypass ? 0 : PlayerStore.remaining(p.getUUID(), "hatch_instant");
        if (left > 0) {
            Msg.err(p, "Charge d'éclosion disponible dans " + Msg.duration(left) + ".");
            return 0;
        }
        int slot = p.getInventory().selected;
        p.getMainHandItem().set(ModContent.EGG_DATA, EggData.withSteps(t, 0));
        if (!HatchService.hatch(p, slot, true)) return 0;
        if (!bypass) PlayerStore.startCooldown(p.getUUID(), "hatch_instant", cd().hatch_command);
        return 1;
    }

    static int eggsee(ServerPlayer p) {
        CompoundTag t = EggData.of(p.getMainHandItem());
        if (t == null) {
            Msg.err(p, "Tiens un Œuf de Pokémon dans ta main principale.");
            return 0;
        }
        if (!cooldown(p, "eggsee", cd().eggsee_command)) return 0;
        for (Component c : eggseeLines(t)) p.sendSystemMessage(c);
        return 1;
    }

    /** Rapport /eggsee (format du cahier des charges). */
    public static List<Component> eggseeLines(CompoundTag t) {
        CompoundTag i = EggData.info(t);
        List<Component> out = new ArrayList<>();
        out.add(Msg.prefix().append(Component.literal("--- ANALYSE DE L'ŒUF EN MAIN ---").withStyle(ChatFormatting.YELLOW)));
        String form = i.getString("form");
        out.add(Component.literal("• Espèce : ").withStyle(ChatFormatting.GRAY)
                .append(Component.translatableWithFallback(i.getString("species_key"), i.getString("species_name")).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(form.isEmpty() ? "" : " (" + form + ")").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" | Nature : ").withStyle(ChatFormatting.GRAY))
                .append(Component.translatableWithFallback(i.getString("nature_key"), i.getString("nature_name")).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" | Poké Ball : ").withStyle(ChatFormatting.GRAY))
                .append(Component.translatableWithFallback(i.getString("ball_key"), i.getString("ball_name")).withStyle(ChatFormatting.WHITE)));
        out.add(Component.literal("• Talent : ").withStyle(ChatFormatting.GRAY)
                .append(Component.translatableWithFallback(i.getString("ability_key"), i.getString("ability_name")).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(i.getBoolean("hidden") ? " (Caché)" : " (Normal)").withStyle(i.getBoolean("hidden") ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.WHITE))
                .append(Component.literal(" | Shiny : ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(i.getBoolean("shiny") ? "Oui ⭐" : "Non").withStyle(i.getBoolean("shiny") ? ChatFormatting.YELLOW : ChatFormatting.WHITE))
                .append(Component.literal(" | Sexe : ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(EggData.gender(i.getString("gender"))).withStyle(ChatFormatting.WHITE)));
        int[] ivs = i.getIntArray("ivs");
        int tot = 0;
        StringBuilder b = new StringBuilder();
        String[] lab = {"PV", "Atk", "Def", "SpA", "SpD", "Spe"};
        for (int k = 0; k < 6; k++) {
            int v = k < ivs.length ? ivs[k] : 0;
            tot += v;
            b.append(k == 0 ? "" : " | ").append(lab[k]).append(": ").append(v);
        }
        out.add(Component.literal("• IVs : ").withStyle(ChatFormatting.GRAY).append(Component.literal(b + String.format(Locale.US, " (Total: %d/186 - %.1f%%)", tot, tot * 100.0 / 186))
                .withStyle(ChatFormatting.WHITE)));
        out.add(Component.literal("• EVs : ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal("PV: 0 | Atk: 0 | Def: 0 | SpA: 0 | SpD: 0 | Spe: 0 (Total: 0/510)").withStyle(ChatFormatting.WHITE)));
        List<String> moves = EggData.eggMoveNames(t);
        out.add(Component.literal("• Egg Moves : ").withStyle(ChatFormatting.GRAY).append(Component.literal(moves.isEmpty() ? "aucune" : String.join(", ", moves)).withStyle(ChatFormatting.WHITE)));
        long left = Math.round(EggData.steps(t));
        long total = EggData.total(t);
        out.add(Component.literal("• Progression Éclosion : ").withStyle(ChatFormatting.GRAY).append(Component.literal(EggData.fmt(total - left) + " / "
                + EggData.fmt(total) + " pas (" + Math.round(EggData.progress(t) * 100) + "%)").withStyle(ChatFormatting.WHITE)));
        out.add(Component.literal("--------------------------------------------------").withStyle(ChatFormatting.DARK_GRAY));
        return out;
    }

    // ================================================================================================= castration

    static int neuter(ServerPlayer p, int slot, boolean confirm) {
        if (!ConfigManager.get().neutering.enabled) {
            Msg.err(p, "La castration est désactivée sur ce serveur.");
            return 0;
        }
        PlayerPartyStore party = Stores.party(p);
        Pokemon pk = party.get(slot - 1);
        if (pk == null) {
            Msg.err(p, "Emplacement d'équipe vide.");
            return 0;
        }
        if (Traits.neutered(pk)) {
            Msg.err(p, RanchService.label(pk) + " est déjà castré.");
            return 0;
        }
        if (!Traits.isOriginalTrainer(pk, p.getUUID())) {
            Msg.err(p, "Seul le Dresseur d'Origine de " + RanchService.label(pk) + " peut le castrer.");
            return 0;
        }
        if (!confirm) {
            p.sendSystemMessage(Msg.prefix().append(Component.literal("Castrer " + RanchService.label(pk) + " ? C'est DÉFINITIF : il ne pourra plus jamais se "
                    + "reproduire, et ce sera visible au GTS. Tape ").withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal("/castrer " + slot + " confirmer").withStyle(ChatFormatting.RED))
                    .append(Component.literal(" pour confirmer.").withStyle(ChatFormatting.YELLOW)));
            return 1;
        }
        Traits.setNeutered(pk, true, p.getUUID());
        party.onPokemonChanged(pk);
        Stores.flush(party);
        Msg.ok(p, RanchService.label(pk) + " est maintenant castré.");
        CobbleRanch.LOG.info("[CobblePulse-Ranch] {} a castre {} ({})", p.getGameProfile().getName(), RanchService.label(pk), pk.getUuid());
        return 1;
    }

    // ================================================================================================= staff

    private static List<String> capStats() {
        List<String> l = new ArrayList<>(List.of(fr.cobblepulse.ranch.service.BottleCaps.KEYS));
        l.add(fr.cobblepulse.ranch.service.BottleCaps.ALL);
        return l;
    }

    private static List<String> giveIds() {
        List<String> l = new ArrayList<>(List.of("ranch_block", "silver_hourglass", "gold_hourglass", "silver_bottle_cap", "gold_bottle_cap"));
        for (String s : ModContent.INCENSES) l.add(s + "_incense");
        return l;
    }

    private static int give(CommandContext<CommandSourceStack> ctx, int n) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "joueur");
        String id = StringArgumentType.getString(ctx, "objet");
        ResourceLocation rl = ResourceLocation.tryParse(id.contains(":") ? id : CobbleRanch.MOD_ID + ":" + id);
        if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) {
            ctx.getSource().sendFailure(Component.literal("Objet inconnu : " + id));
            return 0;
        }
        Item it = BuiltInRegistries.ITEM.get(rl);
        ItemStack st = new ItemStack(it, n);
        if (!target.getInventory().add(st) && !st.isEmpty()) target.drop(st, false);
        ctx.getSource().sendSuccess(() -> Component.literal("[CobbleRanch] " + n + " × " + rl + " donné(s) à " + target.getGameProfile().getName()), true);
        return 1;
    }

    private static int partyEdit(CommandContext<CommandSourceStack> ctx, Function<Pokemon, String> edit) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "joueur");
        int slot = IntegerArgumentType.getInteger(ctx, "emplacement");
        PlayerPartyStore party = Stores.party(target);
        Pokemon pk = party.get(slot - 1);
        if (pk == null) {
            ctx.getSource().sendFailure(Component.literal("Emplacement vide"));
            return 0;
        }
        String what = edit.apply(pk);
        party.onPokemonChanged(pk);
        Stores.flush(party);
        ctx.getSource().sendSuccess(() -> Component.literal("[CobbleRanch] " + RanchService.label(pk) + " de " + target.getGameProfile().getName() + " : " + what), true);
        CobbleRanch.LOG.info("[CobblePulse-Ranch] {} : {} de {} -> {}", ctx.getSource().getTextName(), RanchService.label(pk), target.getGameProfile().getName(), what);
        return 1;
    }
}
