package fr.cobblepulse.ranch.test;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.abilities.Abilities;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.pokeball.PokeBalls;
import com.cobblemon.mod.common.api.pokemon.Natures;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import fr.cobblepulse.ranch.CobbleRanch;
import fr.cobblepulse.ranch.block.RanchBlockEntity;
import fr.cobblepulse.ranch.breeding.Compatibility;
import fr.cobblepulse.ranch.breeding.EggData;
import fr.cobblepulse.ranch.breeding.Genetics;
import fr.cobblepulse.ranch.breeding.Habitat;
import fr.cobblepulse.ranch.breeding.Traits;
import fr.cobblepulse.ranch.compat.UtilsItemLines;
import fr.cobblepulse.ranch.compat.UtilsPokemonLines;
import fr.cobblepulse.ranch.config.ConfigManager;
import fr.cobblepulse.ranch.data.Journal;
import fr.cobblepulse.ranch.data.PlayerStore;
import fr.cobblepulse.ranch.data.RanchData;
import fr.cobblepulse.ranch.data.RanchStore;
import fr.cobblepulse.ranch.data.SlotData;
import fr.cobblepulse.ranch.data.WorldState;
import fr.cobblepulse.ranch.registry.ModContent;
import fr.cobblepulse.ranch.service.Drops;
import fr.cobblepulse.ranch.service.HatchService;
import fr.cobblepulse.ranch.service.RanchService;
import fr.cobblepulse.ranch.service.Stores;
import fr.cobblepulse.ranch.service.Transfers;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * Auto-tests serveur (CI : -Dcobblepulse_ranch.selftest=true puis /cobbleranch selftest). Resultats dans le journal :
 * "[CobblePulse-Ranch][selftest] PASS/FAIL nom", puis "SELFTEST SUMMARY" et "SELFTEST DONE".
 */
public final class SelfTest {
    public static final boolean ENABLED = Boolean.getBoolean("cobblepulse_ranch.selftest");
    private static final String TAG = "[CobblePulse-Ranch][selftest] ";
    static final UUID FAKE = UUID.fromString("0000c0b1-0000-4000-8000-00000000ab01");
    static final UUID FAKE2 = UUID.fromString("0000c0b1-0000-4000-8000-00000000ab02");
    static final UUID CRASH_OWNER = UUID.fromString("0000c0b1-0000-4000-8000-00000000cccc");
    private static final int X = 6000, Y = 150, Z = 6000;
    private static int pass, fail;
    private static final List<Runnable> QUEUE = new ArrayList<>();
    private static int wait;

    private SelfTest() {}

    public static void install() {
        if (!ENABLED) return;
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (QUEUE.isEmpty()) return;
            if (wait > 0) {
                wait--;
                return;
            }
            Runnable r = QUEUE.remove(0);
            try {
                r.run();
            } catch (Throwable t) {
                check("exception", false, t.toString());
                CobbleRanch.LOG.error(TAG + "erreur", t);
            }
            wait = 2;
        });
    }

    static void check(String name, boolean ok, String detail) {
        if (ok) pass++;
        else fail++;
        CobbleRanch.LOG.info(TAG + "{} {}{}", ok ? "PASS" : "FAIL", name, detail == null || detail.isEmpty() ? "" : " - " + detail);
    }

    static Pokemon mon(String props) {
        return PokemonProperties.Companion.parse(props).create();
    }

    static Pokemon withForm(Pokemon p, String form) {
        for (FormData f : p.getSpecies().getForms()) if (f.getName().equalsIgnoreCase(form)) Genetics.applyForm(p, f);
        return p;
    }

    static void hold(Pokemon p, String item) {
        p.swapHeldItem(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(item))), false, false);
    }

    static void ivs(Pokemon p, int v) {
        for (int i = 0; i < 6; i++) p.getIvs().set(Genetics.STATS[i], v);
    }

    static Compatibility.Check pair(Pokemon a, Pokemon b) {
        return Compatibility.check(a, b, false);
    }

    public static LiteralArgumentBuilder<CommandSourceStack> commands() {
        return literal("selftest").requires(s -> s.hasPermission(2)).executes(ctx -> {
            ctx.getSource().sendSuccess(() -> Component.literal("[CobbleRanch] auto-tests lances"), false);
            start();
            return 1;
        }).then(literal("crash").executes(ctx -> {
            crashPrepare();
            return 1;
        })).then(literal("crashcheck").executes(ctx -> {
            crashCheck();
            return 1;
        })).then(literal("persistcheck").executes(ctx -> {
            persistCheck();
            return 1;
        })).then(literal("pc").then(argument("joueur", EntityArgument.player()).then(argument("props", StringArgumentType.greedyString()).executes(ctx -> {
            ServerPlayer p = EntityArgument.getPlayer(ctx, "joueur");
            Pokemon pk = mon(StringArgumentType.getString(ctx, "props"));
            pk.setOriginalTrainer(p.getUUID());
            Stores.pc(p).add(pk);
            ctx.getSource().sendSuccess(() -> Component.literal("[CobbleRanch] PC : " + pk.getSpecies().getName()), false);
            return 1;
        })))).then(literal("walk").then(argument("joueur", EntityArgument.player()).then(argument("blocs", DoubleArgumentType.doubleArg(0)).executes(ctx -> {
            HatchService.walk(EntityArgument.getPlayer(ctx, "joueur"), DoubleArgumentType.getDouble(ctx, "blocs"));
            return 1;
        })))).then(literal("egg").then(argument("joueur", EntityArgument.player()).then(argument("props", StringArgumentType.greedyString()).executes(ctx -> {
            ServerPlayer p = EntityArgument.getPlayer(ctx, "joueur");
            Pokemon a = mon(StringArgumentType.getString(ctx, "props") + " gender=female");
            Pokemon d = mon("ditto");
            Compatibility.Check c = pair(a, d);
            if (!c.ok()) return 0;
            Genetics.Result r = Genetics.breed(c, new Random());
            p.getInventory().add(EggData.toStack(EggData.build(r.child(), r, "test")));
            return 1;
        }))));
    }

    // ================================================================================================= suite

    static void start() {
        pass = 0;
        fail = 0;
        QUEUE.clear();
        QUEUE.add(SelfTest::content);
        QUEUE.add(SelfTest::fertilityOff);
        QUEUE.add(() -> ConfigManager.get().general_settings.enable_fertility_system = true);
        QUEUE.add(SelfTest::compatibility);
        QUEUE.add(SelfTest::species);
        QUEUE.add(SelfTest::forms);
        QUEUE.add(SelfTest::genetics);
        QUEUE.add(SelfTest::modsAndDatapacks);
        QUEUE.add(SelfTest::habitat);
        QUEUE.add(SelfTest::lazyEvaluation);
        QUEUE.add(() -> ConfigManager.get().general_settings.enable_fertility_system = false);
        QUEUE.add(SelfTest::persistenceAndAntiDup);
        QUEUE.add(SelfTest::journal);
        QUEUE.add(SelfTest::eggData);
        QUEUE.add(SelfTest::dropsAndNeuter);
        QUEUE.add(SelfTest::pokegiveEgg);
        QUEUE.add(SelfTest::performance);
        QUEUE.add(() -> {
            CobbleRanch.LOG.info(TAG + "SELFTEST SUMMARY: {} pass, {} fail", pass, fail);
            CobbleRanch.LOG.info(TAG + "SELFTEST DONE");
        });
    }

    static void content() {
        check("bloc_enregistre", BuiltInRegistries.BLOCK.getKey(ModContent.RANCH_BLOCK).toString().equals("cobblepulse_ranch:ranch_block"), null);
        check("objets", ModContent.POKEMON_EGG != null && ModContent.SILVER_HOURGLASS != null && ModContent.GOLD_HOURGLASS != null
                && ModContent.INCENSE_ITEMS.size() == 9, null);
        check("composant_oeuf", BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(ModContent.EGG_DATA) != null, null);
        check("config_creee", Files.exists(ConfigManager.dir().resolve("config.json")) && Files.exists(ConfigManager.dir().resolve("habitat_blocks.json"))
                && Files.exists(ConfigManager.dir().resolve("drops.json")), ConfigManager.dir().toString());
        check("config_valeurs_cdc", ConfigManager.get().environment_5x5.base_breed_duration_seconds == 5400 && ConfigManager.get().genetics.base_shiny_rate == 8192
                && ConfigManager.get().general_settings.slots_per_ranch_block == 3 && ConfigManager.get().command_cooldowns_seconds.hatch_command == 7200, null);
        BlockState st = ModContent.RANCH_BLOCK.defaultBlockState();
        check("pas_de_ticker", st.getBlock() instanceof net.minecraft.world.level.block.EntityBlock eb
                && eb.getTicker(CobbleRanch.server().overworld(), st, ModContent.RANCH_BE) == null, null);
        check("non_waterloggable_et_solide", !st.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED) && st.blocksMotion(), null);
        check("resistance_explosions", ModContent.RANCH_BLOCK.getExplosionResistance() >= 3600000f, String.valueOf(ModContent.RANCH_BLOCK.getExplosionResistance()));
        check("pistons", st.getPistonPushReaction() == net.minecraft.world.level.material.PushReaction.BLOCK, null);
        check("tags_hache_wither", st.is(BlockTags.MINEABLE_WITH_AXE) && st.is(BlockTags.WITHER_IMMUNE) && st.is(BlockTags.DRAGON_IMMUNE), null);
        var recipe = CobbleRanch.server().getRecipeManager().byKey(CobbleRanch.id("ranch_block"));
        check("recette_bloc", recipe.isPresent(), null);
        var loot = CobbleRanch.server().reloadableRegistries().getLootTable(ModContent.RANCH_BLOCK.getLootTable());
        check("loot_bloc", loot != net.minecraft.world.level.storage.loot.LootTable.EMPTY, null);
        check("objets_cobblemon", BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(Traits.DESTINY_KNOT))
                && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(Traits.EVERSTONE)) && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(Traits.MIRROR_HERB))
                && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse("cobblemon:power_weight")), null);
        var fl = net.fabricmc.fabric.api.registry.FlammableBlockRegistry.getDefaultInstance().get(ModContent.RANCH_BLOCK);
        check("ininflammable", !st.ignitedByLava() && (fl == null || fl.getBurnChance() == 0 && fl.getSpreadChance() == 0), fl == null ? "" : fl.getBurnChance() + "/" + fl.getSpreadChance());
    }

    /** Fertilite desactivee par defaut : aucune limite de pontes, aucune donnee ecrite sur les Pokemon. */
    static void fertilityOff() {
        check("fertilite_desactivee_par_defaut", !ConfigManager.get().general_settings.enable_fertility_system && !Traits.fertilityEnabled(), null);
        Pokemon s = mon("eevee gender=female");
        Traits.setFertility(s, 0);
        check("fertilite_ignoree", pair(s, mon("eevee gender=male")).ok(), pair(s, mon("eevee gender=male")).reason());
        RanchData r = RanchData.create(FAKE, "Testeur", true, 3);
        RanchStore.add(r);
        SlotData sl = r.slot(0);
        Pokemon a = mon("eevee gender=female"), b = mon("eevee gender=male");
        sl.set(true, a);
        sl.set(false, b);
        long now = System.currentTimeMillis();
        RanchService.evaluate(r, now);
        int eggs = 0;
        for (int i = 0; i < 12; i++) {
            sl.start = now - sl.duration - 1;
            RanchService.evaluate(r, now);
            eggs += sl.eggs.size();
            sl.eggs.clear();
        }
        Pokemon pa = sl.pokemon(true);
        check("pontes_illimitees", eggs == 12 && !sl.pause.contains("fertile"), eggs + " oeufs, pause '" + sl.pause + "'");
        check("aucune_donnee_de_fertilite", !pa.getPersistentData().contains(Traits.KEY) || !pa.getPersistentData().getCompound(Traits.KEY).contains("fertility"),
                pa.getPersistentData().toString());
        RanchStore.remove(r);
    }

    static void compatibility() {
        Pokemon cm = mon("charmander gender=male"), cf = mon("charmander gender=female");
        check("compat_meme_espece", pair(cm, cf).ok(), pair(cm, cf).reason());
        check("compat_meme_sexe_refuse", !pair(cm, mon("charmander gender=male")).ok(), pair(cm, mon("charmander gender=male")).reason());
        Compatibility.Check sq = pair(cf, mon("squirtle gender=male"));
        check("compat_groupe_commun", sq.ok() && sq.mother() == cf, sq.reason());
        Compatibility.Check pk = pair(mon("pikachu gender=female"), cm);
        check("compat_aucun_groupe", !pk.ok(), pk.reason());
        Pokemon ditto = mon("ditto");
        Compatibility.Check d = pair(ditto, cm);
        check("compat_metamorph", d.ok() && d.mother() == cm && d.dittoInvolved(), d.reason());
        Compatibility.Check dd = pair(ditto, mon("ditto"));
        check("ditto_x_ditto_refuse", !dd.ok(), dd.reason());
        WorldState.setDittoOverride(true);
        check("ditto_x_ditto_override", pair(ditto, mon("ditto")).ok(), null);
        WorldState.setDittoOverride(null);
        Compatibility.Check mt = pair(mon("mewtwo"), ditto);
        check("groupe_inconnu_refuse", !mt.ok() && mt.reason().contains("Inconnu"), mt.reason());
        Compatibility.Check baby = pair(mon("pichu gender=female"), ditto);
        check("bebe_refuse", !baby.ok(), baby.reason());
        Compatibility.Check gl = pair(mon("magnemite"), ditto);
        check("asexue_avec_metamorph", gl.ok(), gl.reason());
        check("asexue_sans_metamorph_refuse", !pair(mon("magnemite"), mon("magnemite")).ok(), null);
        Pokemon n = mon("charmander gender=female");
        Traits.setNeutered(n, true, FAKE);
        check("castre_refuse", !pair(n, cm).ok() && pair(n, cm).reason().contains("castré"), pair(n, cm).reason());
        Pokemon s = mon("charmander gender=female");
        Traits.setFertility(s, 0);
        check("sterile_refuse", !pair(s, cm).ok(), pair(s, cm).reason());
        check("sterile_contournement", Compatibility.check(s, cm, true).ok(), null);
        check("fertilite_defaut", Traits.fertility(mon("eevee")) == 5, null);
    }

    static void species() {
        Pokemon ditto = mon("ditto");
        Random r = new Random(1);
        check("oeuf_premier_stade", Genetics.baseOffspring(mon("charizard gender=female"), ditto, r).getSpecies().getName().equalsIgnoreCase("charmander"), null);
        Pokemon marill = mon("marill gender=female");
        String noIncense = Genetics.baseOffspring(marill, ditto, r).getSpecies().getName();
        check("encens_absent_stade_suivant", noIncense.equalsIgnoreCase("marill"), noIncense);
        Pokemon marill2 = mon("marill gender=female");
        hold(marill2, "cobblepulse_ranch:sea_incense");
        String withIncense = Genetics.baseOffspring(marill2, ditto, r).getSpecies().getName();
        check("encens_mer_azurill", withIncense.equalsIgnoreCase("azurill"), withIncense);
        Pokemon dittoIncense = mon("ditto");
        hold(dittoIncense, "cobblepulse_ranch:sea_incense");
        check("encens_porte_par_le_pere", Genetics.baseOffspring(mon("azumarill gender=female"), dittoIncense, r).getSpecies().getName().equalsIgnoreCase("azurill"), null);
        Set<String> nido = new HashSet<>();
        for (int i = 0; i < 200; i++) nido.add(Genetics.key(Genetics.baseOffspring(mon("nidoranf gender=female"), ditto, r).getSpecies().getName()));
        check("nidoran_deux_especes", nido.contains("nidoranf") && nido.contains("nidoranm"), nido.toString());
        Pokemon pichuBaby = mon("raichu gender=female");
        String pichu = Genetics.baseOffspring(pichuBaby, ditto, r).getSpecies().getName();
        check("raichu_donne_pichu", pichu.equalsIgnoreCase("pichu"), pichu);
        Compatibility.Check dd = Compatibility.check(mon("ditto"), mon("ditto"), false);
        WorldState.setDittoOverride(true);
        Compatibility.Check d2 = Compatibility.check(mon("ditto"), mon("ditto"), false);
        Genetics.Result res = Genetics.breed(d2, r);
        check("ditto_x_ditto_donne_metamorph", res.child().getSpecies().getName().equalsIgnoreCase("ditto"), res.child().getSpecies().getName());
        WorldState.setDittoOverride(null);
        check("ditto_check_restaure", !dd.ok(), null);
    }

    static void forms() {
        Random r = new Random(2);
        Pokemon ditto = mon("ditto");
        // deux parents Alola : forme garantie
        int alola = 0;
        for (int i = 0; i < 60; i++) {
            Compatibility.Check c = pair(withForm(mon("vulpix gender=female"), "Alola"), withForm(mon("vulpix gender=male"), "Alola"));
            if (Genetics.breed(c, r).child().getForm().getName().toLowerCase().contains("alola")) alola++;
        }
        check("forme_regionale_identique_100", alola == 60, alola + "/60");
        // formes differentes : ~30 % la forme de la mere
        alola = 0;
        int n = 400;
        for (int i = 0; i < n; i++) {
            Compatibility.Check c = pair(withForm(mon("vulpix gender=female"), "Alola"), mon("vulpix gender=male"));
            if (Genetics.breed(c, r).child().getForm().getName().toLowerCase().contains("alola")) alola++;
        }
        check("forme_regionale_differente_30", alola > n * 0.18 && alola < n * 0.42, alola + "/" + n);
        alola = 0;
        for (int i = 0; i < 200; i++) {
            Compatibility.Check c = pair(withForm(mon("vulpix gender=female"), "Alola"), ditto);
            if (Genetics.breed(c, r).child().getForm().getName().toLowerCase().contains("alola")) alola++;
        }
        check("forme_regionale_avec_metamorph_30", alola > 200 * 0.15 && alola < 200 * 0.45, alola + "/200");
        // pere Alola sans Pierre Stase : jamais (la forme vient de la mere)
        int fromFather = 0;
        for (int i = 0; i < 100; i++) {
            Compatibility.Check c = pair(mon("vulpix gender=female"), withForm(mon("vulpix gender=male"), "Alola"));
            if (Genetics.breed(c, r).child().getForm().getName().toLowerCase().contains("alola")) fromFather++;
        }
        check("forme_du_pere_sans_pierre_stase", fromFather == 0, fromFather + "/100");
        // Pierre Stase sur le pere Alola : forme garantie
        int stone = 0;
        for (int i = 0; i < 60; i++) {
            Pokemon f = withForm(mon("vulpix gender=male"), "Alola");
            hold(f, Traits.EVERSTONE);
            if (Genetics.breed(pair(mon("vulpix gender=female"), f), r).child().getForm().getName().toLowerCase().contains("alola")) stone++;
        }
        check("pierre_stase_forme_garantie", stone == 60, stone + "/60");
        // forme absente chez le bebe : forme normale (Raichu d'Alola -> Pichu)
        Genetics.Result pr = Genetics.breed(pair(withForm(mon("raichu gender=female"), "Alola"), withForm(mon("raichu gender=male"), "Alola")), r);
        String pf = pr.child().getForm().getName().toLowerCase();
        check("bebe_lignee_regionale", pr.child().getSpecies().getName().equalsIgnoreCase("pichu")
                && (pr.child().getForm() == pr.child().getSpecies().getStandardForm() || pf.contains("bias")), pr.child().getSpecies().getName() + " " + pf);
        // Mega / forme de combat jamais heritee
        check("mots_cles_formes", Genetics.regionalKey(mon("charizard").getSpecies().getStandardForm()).isEmpty(), null);
    }

    static void genetics() {
        Random r = new Random(3);
        int minNormal = 6, minKnot = 6;
        for (int i = 0; i < 150; i++) {
            Pokemon a = mon("charmander gender=female"), b = mon("charmander gender=male");
            ivs(a, 30);
            ivs(b, 1);
            Pokemon c = Genetics.breed(pair(a, b), r).child();
            int inh = 0;
            for (int k = 0; k < 6; k++) {
                int v = c.getIvs().getOrDefault(Genetics.STATS[k]);
                if (v == 30 || v == 1) inh++;
            }
            minNormal = Math.min(minNormal, inh);
            hold(b, Traits.DESTINY_KNOT);
            c = Genetics.breed(pair(a, b), r).child();
            inh = 0;
            for (int k = 0; k < 6; k++) {
                int v = c.getIvs().getOrDefault(Genetics.STATS[k]);
                if (v == 30 || v == 1) inh++;
            }
            minKnot = Math.min(minKnot, inh);
        }
        check("iv_3_herites", minNormal >= 3, "min " + minNormal);
        check("iv_noeud_destin_5", minKnot >= 5, "min " + minKnot);
        boolean power = true;
        for (int i = 0; i < 80; i++) {
            Pokemon a = mon("charmander gender=female"), b = mon("charmander gender=male");
            ivs(a, 30);
            ivs(b, 1);
            hold(a, "cobblemon:power_weight");
            power &= Genetics.breed(pair(a, b), r).child().getIvs().getOrDefault(Genetics.STATS[0]) == 30;
        }
        check("objet_pouvoir_garanti", power, null);
        boolean nature = true;
        for (int i = 0; i < 60; i++) {
            Pokemon a = mon("charmander gender=female nature=adamant"), b = mon("charmander gender=male nature=timid");
            hold(a, Traits.EVERSTONE);
            nature &= Genetics.breed(pair(a, b), r).child().getNature().getName().getPath().equals("adamant");
        }
        check("pierre_stase_nature", nature, null);
        int bothA = 0, bothT = 0;
        for (int i = 0; i < 200; i++) {
            Pokemon a = mon("charmander gender=female nature=adamant"), b = mon("charmander gender=male nature=timid");
            hold(a, Traits.EVERSTONE);
            hold(b, Traits.EVERSTONE);
            String nn = Genetics.breed(pair(a, b), r).child().getNature().getName().getPath();
            if (nn.equals("adamant")) bothA++;
            if (nn.equals("timid")) bothT++;
        }
        check("pierre_stase_deux_porteurs_50_50", bothA + bothT == 200 && bothA > 60 && bothT > 60, bothA + "/" + bothT);
        int ha = 0;
        for (int i = 0; i < 300; i++) {
            Pokemon c = Genetics.breed(pair(mon("charmander gender=female"), mon("charmander gender=male")), r).child();
            if (Genetics.isHidden(c)) ha++;
        }
        check("pas_de_talent_cache_sans_la_mere", ha == 0, ha + "/300");
        ha = 0;
        for (int i = 0; i < 400; i++) {
            Pokemon m = mon("charmander gender=female");
            m.updateAbility(Abilities.get("solarpower").create(false, Priority.LOWEST));
            if (Genetics.breed(pair(m, mon("charmander gender=male")), r).child().getAbility().getName().equals("solarpower")) ha++;
        }
        check("talent_cache_de_la_mere_30", ha > 400 * 0.18 && ha < 400 * 0.42, ha + "/400");
        Pokemon mb = mon("charmander gender=female");
        mb.setCaughtBall(PokeBalls.getPokeBall(ResourceLocation.parse("cobblemon:master_ball")));
        check("master_ball_convertie", Genetics.breed(pair(mb, mon("charmander gender=male")), r).child().getCaughtBall().getName().getPath().equals("poke_ball"), null);
        Pokemon gb = mon("charmander gender=female");
        gb.setCaughtBall(PokeBalls.getPokeBall(ResourceLocation.parse("cobblemon:great_ball")));
        Pokemon fb = mon("charmander gender=male");
        fb.setCaughtBall(PokeBalls.getPokeBall(ResourceLocation.parse("cobblemon:ultra_ball")));
        check("ball_de_la_mere", Genetics.breed(pair(gb, fb), r).child().getCaughtBall().getName().getPath().equals("great_ball"), null);
        // capacites oeuf
        Species ch = PokemonSpecies.getByName("charmander");
        List<MoveTemplate> eggs = ch.getStandardForm().getMoves().getEggMoves();
        if (eggs.isEmpty()) check("capacites_oeuf", false, "Salameche sans capacite oeuf");
        else {
            MoveTemplate em = eggs.get(0);
            Pokemon f = mon("charmander gender=male");
            f.getMoveSet().setMove(0, em.create());
            Genetics.Result res = Genetics.breed(pair(mon("charmander gender=female"), f), r);
            boolean known = false;
            for (var m : res.child().getMoveSet().getMoves()) known |= m.getTemplate().getName().equals(em.getName());
            check("capacite_oeuf_du_pere", known && res.eggMoves().contains(em.getName()), em.getName() + " " + res.eggMoves());
            // Herbe Miroir : la mere (porteuse) apprend la capacite oeuf connue du pere
            Pokemon mother = mon("charmander gender=female");
            hold(mother, Traits.MIRROR_HERB);
            Pokemon father = mon("charmander gender=male");
            father.getMoveSet().setMove(0, em.create());
            Genetics.Result mr = Genetics.breed(pair(mother, father), r);
            boolean learned = false;
            for (var m : mother.getMoveSet().getMoves()) learned |= m.getTemplate().getName().equals(em.getName());
            for (var b : mother.getBenchedMoves()) learned |= b.getMoveTemplate().getName().equals(em.getName());
            check("herbe_miroir", learned && mr.parentsChanged(), mr.notes().toString());
        }
        // Masuda : langues des DO
        Pokemon ma = mon("charmander gender=female"), fa = mon("charmander gender=male");
        ma.setOriginalTrainer(FAKE);
        fa.setOriginalTrainer(FAKE2);
        PlayerStore.get(FAKE).language = "fr_fr";
        PlayerStore.get(FAKE2).language = "fr_fr";
        boolean same = Genetics.masuda(ma, fa);
        PlayerStore.get(FAKE2).language = "en_us";
        boolean diff = Genetics.masuda(ma, fa);
        check("masuda_langues", !same && diff, same + " " + diff);
        int shiny = 0;
        for (int i = 0; i < 3000; i++) if (Genetics.breed(pair(ma, fa), r).child().getShiny()) shiny++;
        check("chromatique_taux_raisonnable", shiny < 30, shiny + "/3000 (Masuda 6/8192)");
        // niveau 1, equipe complete
        Pokemon c = Genetics.breed(pair(mon("eevee gender=female level=50"), mon("eevee gender=male level=50")), r).child();
        check("oeuf_niveau_1", c.getLevel() == 1 && c.getSpecies().getName().equalsIgnoreCase("eevee"), c.getLevel() + " " + c.getSpecies().getName());
    }

    static void modsAndDatapacks() {
        Random r = new Random(4);
        Species z = PokemonSpecies.getByName("zamtrios");
        if (z == null) check("fakemon_ecology", true, "CobblePulse-Ecology absent : test ignore");
        else {
            Pokemon f = mon("zamtrios gender=female");
            Compatibility.Check c = pair(f, mon("ditto"));
            Genetics.Result res = c.ok() ? Genetics.breed(c, r) : null;
            check("fakemon_ecology_zamtrios", res != null && res.child().getSpecies() == z, c.reason());
            Species gm = PokemonSpecies.getByName("goremagala");
            if (gm != null) check("fakemon_legendaire_refuse", !pair(mon("goremagala"), mon("ditto")).ok(), null);
        }
        Species adult = PokemonSpecies.getByName("ranchadulte");
        if (adult == null) check("datapack_espece", true, "datapack de test absent : test ignore");
        else {
            Genetics.Result res = Genetics.breed(pair(mon("ranchadulte gender=female"), mon("ditto")), r);
            check("datapack_premier_stade", res.child().getSpecies().getName().equalsIgnoreCase("ranchbebe"), res.child().getSpecies().getName());
            CompoundTag egg = EggData.build(res.child(), res, "test");
            check("datapack_nom_traduit", EggData.info(egg).getString("species_name").equals("Ranchbébé"), EggData.info(egg).getString("species_name"));
        }
        int breedable = 0;
        for (Species s : PokemonSpecies.getSpecies()) {
            var g = s.getEggGroups();
            if (g != null && !g.isEmpty() && !g.contains(com.cobblemon.mod.common.api.pokemon.egg.EggGroup.UNDISCOVERED)) breedable++;
        }
        check("especes_reproductibles", breedable > 500, breedable + " especes");
    }

    static ServerLevel level() {
        return CobbleRanch.server().overworld();
    }

    static RanchData placeRanch(BlockPos pos, UUID owner) {
        ServerLevel l = level();
        l.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
        l.setBlock(pos, ModContent.RANCH_BLOCK.defaultBlockState(), 3);
        RanchData r = RanchData.create(owner, "Testeur", false, 3);
        r.dimension = RanchService.dim(l);
        r.pos = pos;
        RanchStore.add(r);
        ((RanchBlockEntity) l.getBlockEntity(pos)).link(r);
        return r;
    }

    static void habitat() {
        ServerLevel l = level();
        BlockPos c = new BlockPos(X, Y, Z);
        l.setChunkForced(X >> 4, Z >> 4, true);
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
            l.setBlock(c.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
            for (int dy = 0; dy <= 2; dy++) l.setBlock(c.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
        }
        RanchData r = placeRanch(c, FAKE);
        var groups = EnumSet.of(com.cobblemon.mod.common.api.pokemon.egg.EggGroup.WATER_1);
        check("habitat_vide_0", Habitat.scan(l, c, groups).score() == 0, null);
        check("duree_0_point", Habitat.durationMs(0) == 5400_000L, String.valueOf(Habitat.durationMs(0)));
        check("duree_optimale", Habitat.durationMs(50) == 1200_000L && Habitat.durationMs(80) == 1200_000L, null);
        check("duree_interpolee", Habitat.durationMs(25) == 3300_000L, String.valueOf(Habitat.durationMs(25)));
        // monoculture : 24 sources d'eau = 15 points max
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) if (dx != 0 || dz != 0) l.setBlock(c.offset(dx, 0, dz), Blocks.WATER.defaultBlockState(), 3);
        int water = Habitat.scan(l, c, groups).score();
        check("anti_monoculture_eau", water == 15, water + " points");
        // diversite : nenuphars + lanternes des mers + coraux
        for (int dx = -2; dx <= 2; dx++) l.setBlock(c.offset(dx, 1, -2), Blocks.LILY_PAD.defaultBlockState(), 3);
        l.setBlock(c.offset(-2, 0, 2), Blocks.SEA_LANTERN.defaultBlockState(), 3);
        l.setBlock(c.offset(-1, 0, 2), Blocks.SEA_LANTERN.defaultBlockState(), 3);
        l.setBlock(c.offset(0, 0, 2), Blocks.SEA_LANTERN.defaultBlockState(), 3);
        l.setBlock(c.offset(1, 0, 2), Blocks.BRAIN_CORAL_BLOCK.defaultBlockState(), 3);
        l.setBlock(c.offset(2, 0, 2), Blocks.TUBE_CORAL_BLOCK.defaultBlockState(), 3);
        l.setBlock(c.offset(2, 1, 2), Blocks.HORN_CORAL_BLOCK.defaultBlockState(), 3);
        Habitat.Result h = Habitat.scan(l, c, groups);
        check("habitat_diversifie", h.score() >= 50, h.score() + " " + h.lines());
        check("habitat_note", Habitat.rating(h.score()).equals("EXCELLENT") && Habitat.rating(10).equals("INCOMPLET"), Habitat.rating(h.score()));
        check("coraux_derives", h.lines().stream().anyMatch(x -> x.block().contains("coral") && x.count() >= 3), null);
        // sous le bloc : rien n'est compte
        l.setBlock(c.offset(0, -1, 0), Blocks.SEA_LANTERN.defaultBlockState(), 3);
        check("pas_de_scan_sous_le_bloc", Habitat.scan(l, c, groups).score() == h.score(), null);
        // le meilleur groupe du couple est retenu (Metamorph prend celui du partenaire)
        SlotData s = r.slot(0);
        s.set(true, mon("psyduck gender=female"));
        s.set(false, mon("ditto"));
        RanchService.scan(r, s, true, System.currentTimeMillis());
        check("scan_groupe_du_couple", s.score >= 50 && s.duration == 1200_000L, s.score + " " + s.group + " " + s.duration);
        // cooldown de 2 s
        long at = s.scanAt;
        check("scan_cooldown", !RanchService.scan(r, s, false, at + 500) && RanchService.scan(r, s, false, at + 2500), null);
        // l'eau ne remplace pas le bloc
        check("eau_ne_detruit_pas", l.getBlockState(c).is(ModContent.RANCH_BLOCK), null);
        RanchService.restituteAll(r, "test");
    }

    static void lazyEvaluation() {
        RanchData r = RanchData.create(FAKE, "Testeur", true, 3);
        RanchStore.add(r);
        SlotData s = r.slot(0);
        Pokemon m = mon("eevee gender=female"), f = mon("eevee gender=male");
        s.set(true, m);
        s.set(false, f);
        long now = System.currentTimeMillis();
        RanchService.evaluate(r, now);
        check("cycle_demarre", s.start == now && s.duration == ConfigManager.get().remote_breed.duration_seconds * 1000L, s.start + " " + s.duration);
        // personne ne regarde pendant 3 cycles : une seule ponte (1 oeuf max), puis pause
        RanchService.evaluate(r, now + 3 * s.duration + 1000);
        check("ponte_a_la_demande", s.eggs.size() == 1 && s.start == 0 && s.pause.contains("Œuf"), s.eggs.size() + " " + s.start + " " + s.pause);
        check("fertilite_decrementee", Traits.fertility(s.pokemon(true)) == 4 && Traits.fertility(s.pokemon(false)) == 4, null);
        CompoundTag egg = s.eggs.get(0);
        check("oeuf_espece", EggData.info(egg).getString("species").endsWith("eevee"), EggData.info(egg).getString("species"));
        // recuperation puis nouveau cycle
        s.eggs.clear();
        long t2 = now + 4 * s.duration;
        RanchService.evaluate(r, t2);
        check("reprise_apres_recuperation", s.start == t2 && s.pause.isEmpty(), s.start + " " + s.pause);
        // fertilite epuisee
        Traits.setFertility(s.pokemon(true), 1);
        s.refresh(true);
        RanchService.evaluate(r, t2 + s.duration + 1);
        s.eggs.clear();
        RanchService.evaluate(r, t2 + 2 * s.duration + 2);
        check("sterile_arrete_la_ponte", s.start == 0 && s.pause.contains("fertile"), s.pause);
        // contournement de fertilite
        r.ownerBypassFertility = true;
        RanchService.evaluate(r, t2 + 3 * s.duration);
        check("contournement_fertilite", s.start > 0, s.pause);
        r.ownerBypassFertility = false;
        // ponte forcee (admin)
        check("ponte_forcee", RanchService.forceLay(r, 0) != null || s.eggs.size() >= 1, null);
        // modification des parents : nouveau cycle
        s.eggs.clear();
        Traits.setFertility(s.pokemon(true), 5);
        s.refresh(true);
        s.set(false, mon("ditto"));
        s.start = 0;
        RanchService.evaluate(r, now);
        check("changement_de_parent", s.start == now, null);
        RanchStore.remove(r);
    }

    static void persistenceAndAntiDup() {
        ServerLevel l = level();
        BlockPos p1 = new BlockPos(X + 20, Y, Z);
        RanchData r = placeRanch(p1, FAKE);
        r.slot(1).set(true, mon("bulbasaur gender=female"));
        r.slot(1).set(false, mon("ditto"));
        RanchService.save(r);
        long rev = r.revision;
        CompoundTag disk = null;
        try {
            disk = net.minecraft.nbt.NbtIo.readCompressed(RanchStore.dataDir().resolve("ranches").resolve(r.id + ".dat"), net.minecraft.nbt.NbtAccounter.unlimitedHeap());
        } catch (Exception ignored) {
        }
        check("fichier_monde", disk != null && RanchData.load(disk).revision == rev && RanchData.load(disk).slot(1).full(), String.valueOf(rev));
        RanchBlockEntity be = (RanchBlockEntity) l.getBlockEntity(p1);
        CompoundTag beTag = be.saveWithoutMetadata(l.registryAccess());
        check("copie_dans_le_bloc", beTag.contains("mirror") && RanchData.load(beTag.getCompound("mirror")).slot(1).full(), null);
        // copie WorldEdit/structure ailleurs : videe
        BlockPos p2 = new BlockPos(X + 22, Y, Z);
        l.setBlock(p2, ModContent.RANCH_BLOCK.defaultBlockState(), 3);
        RanchBlockEntity copy = (RanchBlockEntity) l.getBlockEntity(p2);
        copy.loadWithComponents(beTag, l.registryAccess());
        check("copie_preparee", r.id.equals(copy.ranchId()), null);
        RanchService.onChunkLoad(l, l.getChunkAt(p2));
        check("anti_duplication_copie_videe", copy.ranchId() == null && RanchStore.get(r.id).pos.equals(p1), null);
        // fichier perdu : la copie du bloc restaure la pension
        RanchStore.remove(r);
        check("pension_retiree", RanchStore.get(r.id) == null, null);
        be.loadWithComponents(beTag, l.registryAccess());
        RanchService.onChunkLoad(l, l.getChunkAt(p1));
        RanchData back = RanchStore.get(r.id);
        check("restauration_depuis_le_bloc", back != null && back.slot(1).full(), null);
        // bloc disparu : orpheline, contenu garde
        RanchData ghost = RanchData.create(FAKE, "Testeur", false, 3);
        ghost.dimension = RanchService.dim(l);
        ghost.pos = new BlockPos(X + 24, Y, Z);
        ghost.slot(0).set(true, mon("oddish gender=female"));
        RanchStore.add(ghost);
        RanchService.onChunkLoad(l, l.getChunkAt(ghost.pos));
        check("pension_orpheline", ghost.orphan && ghost.slot(0).has(true), null);
        // casse du bloc : parents rendus au PC du proprietaire
        int before = count(Stores.pc(FAKE));
        l.destroyBlock(p1, false);
        int after = count(Stores.pc(FAKE));
        check("casse_rend_les_pokemon", after == before + 2 && RanchStore.get(r.id) == null, before + " -> " + after);
        int b2 = count(Stores.pc(FAKE));
        RanchService.restituteAll(ghost, "test");
        RanchStore.remove(ghost);
        check("restitution_orpheline", count(Stores.pc(FAKE)) == b2 + 1, null);
        // limite par chunk
        ConfigManager.get().general_settings.max_ranch_blocks_per_chunk = 1;
        check("limite_par_chunk", RanchStore.countInChunk(RanchService.dim(l), new net.minecraft.world.level.ChunkPos(new BlockPos(X, Y, Z)).toLong()) >= 1, null);
        ConfigManager.get().general_settings.max_ranch_blocks_per_chunk = -1;
    }

    static int count(PCStore pc) {
        int n = 0;
        for (Pokemon ignored : pc) n++;
        return n;
    }

    static void journal() {
        // depot interrompu : present au PC ET dans la pension -> retire de la pension
        RanchData r = RanchData.create(FAKE, "Testeur", true, 3);
        RanchStore.add(r);
        Pokemon pk = mon("squirtle gender=female");
        Stores.pc(FAKE).add(pk);
        CompoundTag dest = Journal.where("RANCH");
        dest.putUUID("ranch", r.id);
        dest.putInt("slot", 0);
        dest.putBoolean("first", true);
        Journal.begin("DEPOSIT", FAKE, pk.getUuid(), pk.saveToNBT(CobbleRanch.registries(), new CompoundTag()), Journal.where("PC"), dest);
        r.slot(0).set(true, pk);
        RanchStore.save(r);
        Transfers.recoverAtStartup();
        check("journal_doublon_retire", Stores.pc(FAKE).get(pk.getUuid()) != null && !r.slot(0).has(true) && Journal.pending().isEmpty(), null);
        // perdu des deux cotes -> remis a l'origine
        Pokemon lost = mon("totodile gender=male");
        Journal.begin("WITHDRAW", FAKE, lost.getUuid(), lost.saveToNBT(CobbleRanch.registries(), new CompoundTag()), dest.copy(), Journal.where("PC"));
        Transfers.recoverAtStartup();
        check("journal_perte_rattrapee", r.slot(0).has(true) && lost.getUuid().equals(r.slot(0).uuid(true)), null);
        // transfert termine : garde
        Pokemon done = mon("cyndaquil gender=male");
        CompoundTag d2 = dest.copy();
        d2.putInt("slot", 1);
        Journal.begin("DEPOSIT", FAKE, done.getUuid(), done.saveToNBT(CobbleRanch.registries(), new CompoundTag()), Journal.where("PC"), d2);
        r.slot(1).set(true, done);
        RanchStore.save(r);
        Transfers.recoverAtStartup();
        check("journal_transfert_fini_garde", r.slot(1).has(true) && Stores.pc(FAKE).get(done.getUuid()) == null, null);
        RanchService.restituteAll(r, "test");
        RanchStore.remove(r);
    }

    static void eggData() {
        Compatibility.Check lc = pair(mon("lucario gender=female"), mon("ditto"));
        check("lucario_metamorph", lc.ok(), lc.reason());
        Genetics.Result res = Genetics.breed(lc, new Random(5));
        check("lucario_donne_riolu", res.child().getSpecies().getName().equalsIgnoreCase("riolu"), res.child().getSpecies().getName());
        CompoundTag egg = EggData.build(res.child(), res, "Lucario ♀ x Métamorph");
        check("types_de_l_oeuf", EggData.info(egg).getString("type1").equals("fighting") && EggData.info(egg).getString("type2").isEmpty(),
                EggData.info(egg).getString("type1") + "/" + EggData.info(egg).getString("type2"));
        Genetics.Result dual = Genetics.breed(pair(mon("bulbasaur gender=female"), mon("ditto")), new Random(6));
        CompoundTag de = EggData.build(dual.child(), dual, "test");
        check("types_double", EggData.info(de).getString("type1").equals("grass") && EggData.info(de).getString("type2").equals("poison"),
                EggData.info(de).getString("type1") + "/" + EggData.info(de).getString("type2"));
        ItemStack st = EggData.toStack(egg);
        check("objet_oeuf", EggData.of(st) != null && EggData.id(EggData.of(st)).equals(EggData.id(egg)), null);
        ItemStack copy = st.copy();
        check("donnees_figees_copie", EggData.of(copy).equals(EggData.of(st)), null);
        Pokemon back = EggData.pokemon(egg);
        check("pokemon_identique", back != null && back.getUuid().equals(res.child().getUuid()) && back.getNature() == res.child().getNature()
                && back.getShiny() == res.child().getShiny(), null);
        int total = EggData.total(egg);
        check("pas_cycles", total == res.child().getSpecies().getEggCycles() * 256 || total == 20 * 256, total + "");
        List<String> sheet = new UtilsItemLines().apply(st);
        check("fiche_gts", sheet.size() >= 12 && String.join("\n", sheet).contains("FICHE GTS") && String.join("\n", sheet).contains("TOTAL IVs"), sheet.toString());
        check("fiche_gts_vide_pour_autre_objet", new UtilsItemLines().apply(new ItemStack(net.minecraft.world.item.Items.DIAMOND)).isEmpty(), null);
        CompoundTag half = EggData.withSteps(egg, total / 2.0);
        check("progression", Math.abs(EggData.progress(half) - 0.5) < 0.01 && EggData.status(0.65).contains("agiter"), null);
        check("eggsee_format", fr.cobblepulse.ranch.command.RanchCommands.eggseeLines(egg).size() == 8, null);
    }

    static void dropsAndNeuter() {
        Drops.Config old = Drops.get();
        double c0 = old.drops.get(0).chance;
        for (Drops.Drop d : old.drops) d.chance = 1.0;
        check("drop_chaffreux_noeud", Drops.roll(mon("purugly"), "defeat", null, null) == 1, null);
        check("drop_chaglam_noeud", Drops.roll(mon("glameow"), "capture", null, null) == 1, null);
        check("drop_leuphorie_pierre", Drops.roll(mon("blissey"), "kill", null, null) == 1, null);
        check("pas_de_drop_autre_espece", Drops.roll(mon("pidgey"), "defeat", null, null) == 0, null);
        for (Drops.Drop d : old.drops) d.chance = c0;
        double h0 = old.held_on_capture.get(0).chance;
        for (Drops.Held h : old.held_on_capture) h.chance = 1.0;
        Pokemon geo = mon("geodude");
        try {
            java.lang.reflect.Method m = Drops.class.getDeclaredMethod("onCapture", Pokemon.class, ServerPlayer.class);
            m.setAccessible(true);
            m.invoke(null, geo, null);
        } catch (Exception e) {
            check("drop_capture_appel", false, e.toString());
        }
        check("racaillou_tient_pierre_stase", Traits.holds(geo, Traits.EVERSTONE), Traits.heldId(geo));
        for (Drops.Held h : old.held_on_capture) h.chance = h0;
        Pokemon p = mon("eevee gender=female");
        p.setOriginalTrainer(FAKE);
        check("dresseur_origine", Traits.isOriginalTrainer(p, FAKE) && !Traits.isOriginalTrainer(p, FAKE2), null);
        Traits.setNeutered(p, true, FAKE);
        CompoundTag nbt = p.saveToNBT(CobbleRanch.registries(), new CompoundTag());
        Pokemon reload = new Pokemon().loadFromNBT(CobbleRanch.registries(), nbt);
        check("castration_persistante", Traits.neutered(reload) && Traits.neuteredTag(nbt), null);
        List<String> lines = new UtilsPokemonLines().apply(reload);
        check("castration_visible_gts", !lines.isEmpty() && lines.get(0).contains("Castré"), lines.toString());
        check("fertilite_absente_gts", new UtilsPokemonLines().apply(mon("eevee")).isEmpty(), null);
        utilsIntegration(reload);
    }

    /** Avec CobblePulse Utils installe : sa fiche de Pokemon (GTS) affiche la castration, et la fiche d'un oeuf est fournie. */
    static void utilsIntegration(Pokemon neutered) {
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cobblepulse_utils")) {
            check("utils_gts", true, "CobblePulse Utils absent : test ignore");
            return;
        }
        try {
            Class<?> ext = Class.forName("fr.cobblepulse.utils.core.Extensions");
            @SuppressWarnings("unchecked")
            List<String> lines = (List<String>) Class.forName("fr.cobblepulse.utils.pokemon.PokemonText").getMethod("lines", Pokemon.class).invoke(null, neutered);
            check("utils_gts_castre", String.join("\n", lines).contains("Castré"), lines.toString());
            Genetics.Result res = Genetics.breed(pair(mon("eevee gender=female"), mon("ditto")), new Random(9));
            @SuppressWarnings("unchecked")
            List<String> sheet = (List<String>) ext.getMethod("itemLines", ItemStack.class).invoke(null, EggData.toStack(EggData.build(res.child(), res, "test")));
            check("utils_gts_fiche_oeuf", String.join("\n", sheet).contains("FICHE GTS"), sheet.size() + " lignes");
        } catch (ClassNotFoundException e) {
            check("utils_gts", true, "CobblePulse Utils sans extensions (version precedente) : test ignore");
        } catch (Throwable t) {
            check("utils_gts", false, t.toString());
        }
    }

    /** /pokegive egg : oeufs de n'importe quelle espece ou au hasard ; /pokegive de Cobblemon garde sa permission. */
    static void pokegiveEgg() {
        CompoundTag c = fr.cobblepulse.ranch.command.EggGive.create("charizard shiny=yes nature=adamant", "test");
        Pokemon p = EggData.pokemon(c);
        check("pokegive_egg_espece", p != null && p.getSpecies().getName().equalsIgnoreCase("charizard") && p.getShiny() && p.getLevel() == 1
                && p.getNature().getName().getPath().equals("adamant"), p == null ? "" : p.getSpecies().getName() + " " + p.getLevel());
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 30; i++) seen.add(EggData.info(fr.cobblepulse.ranch.command.EggGive.create("random", "test")).getString("species"));
        check("pokegive_egg_random", seen.size() >= 20, seen.size() + " especes sur 30");
        boolean breedable = true;
        for (int i = 0; i < 30; i++) {
            Pokemon r = EggData.pokemon(fr.cobblepulse.ranch.command.EggGive.create("random_reproductible shiny=yes", "test"));
            var g = r.getSpecies().getEggGroups();
            breedable &= g != null && !g.isEmpty() && !g.contains(com.cobblemon.mod.common.api.pokemon.egg.EggGroup.UNDISCOVERED) && r.getShiny();
        }
        check("pokegive_egg_random_reproductible", breedable, null);
        if (PokemonSpecies.getByName("zamtrios") != null)
            check("pokegive_egg_fakemon", EggData.info(fr.cobblepulse.ranch.command.EggGive.create("zamtrios", "test")).getString("species").endsWith("zamtrios"), null);
        boolean refused = false;
        try {
            fr.cobblepulse.ranch.command.EggGive.create("pasunpokemon", "test");
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        check("pokegive_egg_espece_inconnue", refused, null);
        var root = CobbleRanch.server().getCommands().getDispatcher().getRoot();
        var give = root.getChild("pokegive");
        var other = root.getChild("pokegiveother");
        var anyone = CobbleRanch.server().createCommandSourceStack().withPermission(0);
        check("pokegive_egg_greffe", give != null && give.getChild("egg") != null && other != null && other.getChild("player") != null
                && other.getChild("player").getChild("egg") != null, null);
        check("pokegive_permission_cobblemon_conservee", give != null && !give.canUse(anyone), null);
        check("pokegive_normal_intact", give != null && give.getChildren().size() >= 2, give == null ? "" : give.getChildren().size() + " enfants");
    }

    static void performance() {
        List<RanchData> many = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            RanchData r = RanchData.create(FAKE2, "Perf", true, 3);
            for (SlotData s : r.slots) {
                s.set(true, mon("eevee gender=female"));
                s.set(false, mon("eevee gender=male"));
            }
            many.add(r);
        }
        long t0 = System.nanoTime();
        long now = System.currentTimeMillis();
        for (RanchData r : many) RanchService.evaluate(r, now);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check("evaluation_900_emplacements", ms < 1500, ms + " ms (a la demande, jamais par tick)");
        // aucune pension n'est evaluee par le tick du serveur : seul le passage d'eclosion par joueur existe
        check("aucun_tick_de_pension", ModContent.RANCH_BLOCK.defaultBlockState().getTicker(level(), ModContent.RANCH_BE) == null, null);
    }

    // ================================================================================================= arret brutal

    /** Prepare un depot interrompu : PC enregistre AVEC le Pokemon, pension enregistree AVEC lui aussi, journal ouvert. */
    static void crashPrepare() {
        Pokemon pk = mon("larvitar gender=female");
        PCStore pc = Stores.pc(CRASH_OWNER);
        pc.add(pk);
        Stores.flush(pc);
        RanchData r = RanchData.create(CRASH_OWNER, "Crash", true, 3);
        RanchStore.add(r);
        CompoundTag dest = Journal.where("RANCH");
        dest.putUUID("ranch", r.id);
        dest.putInt("slot", 0);
        dest.putBoolean("first", true);
        Journal.begin("DEPOSIT", CRASH_OWNER, pk.getUuid(), pk.saveToNBT(CobbleRanch.registries(), new CompoundTag()), Journal.where("PC"), dest);
        pc.remove(pk);
        r.slot(0).set(true, pk);
        RanchStore.save(r);
        CobbleRanch.LOG.info(TAG + "CRASH READY {} {}", pk.getUuid(), r.id);
    }

    static void crashCheck() {
        int inPc = 0, inRanch = 0;
        for (Pokemon p : Stores.pc(CRASH_OWNER)) if (p.getSpecies().getName().equalsIgnoreCase("larvitar")) inPc++;
        for (RanchData r : RanchStore.byOwner(CRASH_OWNER)) for (SlotData s : r.slots) for (boolean f : new boolean[]{true, false}) {
            Pokemon p = s.pokemon(f);
            if (p != null && p.getSpecies().getName().equalsIgnoreCase("larvitar")) inRanch++;
        }
        boolean ok = inPc + inRanch == 1 && Journal.pending().isEmpty();
        CobbleRanch.LOG.info(TAG + "{} arret_brutal_sans_doublon - PC {} / Pension {} / journal {}", ok ? "PASS" : "FAIL", inPc, inRanch, Journal.pending().size());
        CobbleRanch.LOG.info(TAG + "CRASHCHECK DONE");
    }

    /** Apres redemarrage : les pensions du test sont toujours la. */
    static void persistCheck() {
        int n = RanchStore.byOwner(FAKE).size();
        boolean files = RanchStore.all().stream().allMatch(r -> Files.exists(RanchStore.dataDir().resolve("ranches").resolve(r.id + ".dat")));
        CobbleRanch.LOG.info(TAG + "{} persistance_apres_redemarrage - {} pension(s), fichiers {}", files ? "PASS" : "FAIL", RanchStore.all().size(), files);
        CobbleRanch.LOG.info(TAG + "{} journal_vide_au_demarrage - {}", Journal.pending().isEmpty() ? "PASS" : "FAIL", Journal.pending().size());
        CobbleRanch.LOG.info(TAG + "PERSISTCHECK DONE {}", n);
    }
}
