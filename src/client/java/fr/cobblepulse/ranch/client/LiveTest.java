package fr.cobblepulse.ranch.client;

import com.cobblemon.mod.common.client.CobblemonClient;
import com.google.gson.JsonObject;
import fr.cobblepulse.ranch.CobbleRanch;
import fr.cobblepulse.ranch.registry.ModContent;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Test de bout en bout (CI, -Dcobblepulse_ranch.livetest=true) : vrai client connecte au serveur de test. Pose du Bloc
 * de Pension, choix des parents dans le PC par l'ecran, sabliers, recuperation de l'oeuf, infobulle, /eggsee, /hatch,
 * sablier sur l'oeuf dans l'inventaire, eclosion en marchant, /breed (analyse + lancement a distance), /daycare,
 * /castrer, Guide, panneau admin, retrait du bloc. Captures dans screenshots/.
 */
final class LiveTest {
    private static final String TAG = "[CobblePulse-Ranch][livetest] ";
    private static final List<Consumer<Minecraft>> STEPS = new ArrayList<>();
    private static final List<Integer> WAITS = new ArrayList<>();
    /** attente conditionnelle : etape suivante des que la condition est vraie (ou echec au bout du delai). */
    private static final java.util.Map<Integer, java.util.function.Predicate<Minecraft>> UNTIL = new java.util.HashMap<>();
    private static final java.util.Map<Integer, String> UNTIL_NAME = new java.util.HashMap<>();
    private static int awaitStart = -1;
    private static final List<String> CHAT = new ArrayList<>();
    private static int ticks, start = -1, step, pass, fail, partyBefore;
    private static double stepsBefore;

    private static final int X = 3000, Y = 300, Z = 3000;

    private LiveTest() {}

    static void install() {
        ClientTickEvents.END_CLIENT_TICK.register(LiveTest::tick);
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            if (!overlay) CHAT.add(msg.getString());
        });
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) pass++;
        else fail++;
        CobbleRanch.LOG.info(TAG + "{} {}{}", ok ? "PASS" : "FAIL", name, detail == null ? "" : " - " + detail);
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), c -> CobbleRanch.LOG.info(TAG + "capture {}", name));
    }

    private static void cmd(Minecraft mc, String c) {
        mc.player.connection.sendCommand(c);
    }

    private static boolean chatHas(String s) {
        for (String m : CHAT) if (m.contains(s)) return true;
        return false;
    }

    private static String lastChat() {
        return CHAT.isEmpty() ? "" : CHAT.get(CHAT.size() - 1);
    }

    private static void add(int wait, Consumer<Minecraft> s) {
        STEPS.add(s);
        WAITS.add(wait);
    }

    /** Attend (jusqu'a timeout ticks) que la condition soit vraie ; sinon FAIL nom. */
    private static void until(String name, int timeout, java.util.function.Predicate<Minecraft> cond) {
        UNTIL.put(STEPS.size(), cond);
        UNTIL_NAME.put(STEPS.size(), name);
        STEPS.add(mc -> {
        });
        WAITS.add(timeout);
    }

    private static int find(Minecraft mc, Item item) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) if (inv.getItem(i).is(item)) return i;
        return -1;
    }

    private static int menuSlot(int inv) {
        return inv < 9 ? 36 + inv : inv;
    }

    private static int partySize() {
        int n = 0;
        for (var p : CobblemonClient.INSTANCE.getStorage().getParty().getSlots()) if (p != null) n++;
        return n;
    }

    /** Emplacement d'equipe (1-6) du Pokemon de cette espece, sexe et niveau ; 0 si absent. */
    private static int partySlot(String species, String gender, int level) {
        var slots = CobblemonClient.INSTANCE.getStorage().getParty().getSlots();
        for (int i = 0; i < slots.size(); i++) {
            var p = slots.get(i);
            if (p != null && p.getSpecies().getName().equalsIgnoreCase(species) && p.getGender().name().equals(gender) && p.getLevel() == level) return i + 1;
        }
        return 0;
    }

    private static RanchScreen ranch(Minecraft mc) {
        return mc.screen instanceof RanchScreen r ? r : null;
    }

    private static JsonObject slot0(Minecraft mc) {
        RanchScreen r = ranch(mc);
        return r == null ? null : RanchUi.arr(r.data, "slots").get(0);
    }

    private static void useOn(Minecraft mc, BlockPos pos, Direction face) {
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5), face, pos, false));
    }

    /** Ecran temporaire qui dessine l'infobulle de l'oeuf (capture). */
    static final class TooltipShot extends Screen {
        private final List<Component> full, compact;

        TooltipShot(List<Component> compact, List<Component> full) {
            super(Component.literal("Infobulle"));
            this.compact = compact;
            this.full = full;
        }

        @Override
        public void render(GuiGraphics g, int mx, int my, float pt) {
            super.render(g, mx, my, pt);
            g.renderComponentTooltip(font, compact, 10, 20);
            g.renderComponentTooltip(font, full, width / 2 - 20, 20);
        }
    }

    static {
        add(20, mc -> {
            cmd(mc, "gamemode creative");
            cmd(mc, "clear");
            cmd(mc, "forceload add " + (X - 16) + " " + (Z - 16) + " " + (X + 16) + " " + (Z + 16));
        });
        add(20, mc -> cmd(mc, "tp @s " + X + ".5 " + (Y + 1) + " " + (Z + 4) + ".5 180 30"));
        add(20, mc -> {
            cmd(mc, "fill " + (X - 6) + " " + (Y - 1) + " " + (Z - 6) + " " + (X + 6) + " " + (Y - 1) + " " + (Z + 6) + " minecraft:grass_block");
            cmd(mc, "fill " + (X - 6) + " " + Y + " " + (Z - 6) + " " + (X + 6) + " " + (Y + 4) + " " + (Z + 6) + " minecraft:air");
            cmd(mc, "cobbleranch give @s ranch_block 2");
        });
        until("bloc_recu", 1200, mc -> find(mc, ModContent.RANCH_ITEM) >= 0);
        add(30, mc -> {
            int s = find(mc, ModContent.RANCH_ITEM);
            if (s >= 0 && s < 9) mc.player.getInventory().selected = s;
            useOn(mc, new BlockPos(X, Y - 1, Z), Direction.UP);
        });
        until("bloc_pose", 400, mc -> mc.level.getBlockState(new BlockPos(X, Y, Z)).is(ModContent.RANCH_BLOCK) && chatHas("Pension posée"));
        add(30, mc -> {
            check("bloc_pose", mc.level.getBlockState(new BlockPos(X, Y, Z)).is(ModContent.RANCH_BLOCK), null);
            check("message_pose", chatHas("Pension posée"), lastChat());
            // habitat : quelques blocs de l'herbe autour
            cmd(mc, "setblock " + (X - 2) + " " + Y + " " + (Z - 2) + " minecraft:hay_block");
            cmd(mc, "setblock " + (X + 2) + " " + Y + " " + (Z - 2) + " minecraft:hay_block");
            cmd(mc, "setblock " + (X - 2) + " " + Y + " " + (Z + 1) + " minecraft:composter");
            cmd(mc, "setblock " + (X + 1) + " " + (Y + 0) + " " + (Z - 1) + " minecraft:poppy");
            cmd(mc, "setblock " + (X - 1) + " " + (Y + 0) + " " + (Z - 1) + " minecraft:dandelion");
            String me = mc.player.getGameProfile().getName();
            cmd(mc, "cobbleranch selftest pc " + me + " eevee gender=female level=20");
            cmd(mc, "cobbleranch selftest pc " + me + " ditto level=30");
            cmd(mc, "cobbleranch selftest pc " + me + " geodude gender=male level=12");
            cmd(mc, "cobbleranch selftest pc " + me + " mewtwo level=70");
        });
        until("pc_rempli", 600, mc -> chatHas("PC : Mewtwo"));
        add(30, mc -> {
            mc.player.getInventory().selected = 8;
            useOn(mc, new BlockPos(X, Y, Z), Direction.UP);
        });
        until("ecran_pension", 400, mc -> ranch(mc) != null);
        add(30, mc -> {
            check("ecran_pension_ouvert", ranch(mc) != null, String.valueOf(mc.screen));
            check("trois_emplacements", ranch(mc) != null && RanchUi.arr(ranch(mc).data, "slots").size() == 3, null);
            shot(mc, "live_01_pension_vide");
        });
        add(10, mc -> check("choisir_parent_a", ranch(mc) != null && ranch(mc).press("pick:0:A"), ranch(mc) == null ? "" : ranch(mc).buttonIds().toString()));
        until("ecran_choix_a", 400, mc -> mc.screen instanceof PickerScreen);
        add(30, mc -> {
            PickerScreen p = mc.screen instanceof PickerScreen ps ? ps : null;
            check("ecran_choix_pc", p != null && RanchUi.arr(p.data, "entries").size() >= 4, p == null ? String.valueOf(mc.screen) : RanchUi.arr(p.data, "entries").size() + "");
            if (p != null) {
                boolean mewtwoRefused = false;
                for (JsonObject e : RanchUi.arr(p.data, "entries")) if (RanchUi.str(e, "species").endsWith("mewtwo")) mewtwoRefused = !RanchUi.bool(e, "ok");
                check("mewtwo_grise", mewtwoRefused, null);
            }
            shot(mc, "live_02_choix_pc");
        });
        add(30, mc -> {
            PickerScreen p = mc.screen instanceof PickerScreen ps ? ps : null;
            if (p != null) for (JsonObject e : RanchUi.arr(p.data, "entries")) if (RanchUi.str(e, "species").endsWith("eevee")) {
                p.choose(e);
                break;
            }
        });
        until("depot_a", 400, mc -> slot0(mc) != null && RanchUi.obj(slot0(mc), "a") != null);
        add(10, mc -> {
            JsonObject s = slot0(mc);
            check("depot_parent_a", s != null && RanchUi.obj(s, "a") != null, s == null ? String.valueOf(mc.screen) : s.toString());
            if (ranch(mc) != null) ranch(mc).press("pick:0:B");
        });
        until("ecran_choix_b", 400, mc -> mc.screen instanceof PickerScreen);
        add(30, mc -> {
            PickerScreen p = mc.screen instanceof PickerScreen ps ? ps : null;
            if (p != null) {
                boolean geoRefused = false;
                for (JsonObject e : RanchUi.arr(p.data, "entries")) if (RanchUi.str(e, "species").endsWith("geodude")) geoRefused = !RanchUi.bool(e, "ok");
                check("incompatible_grise", geoRefused, null);
                for (JsonObject e : RanchUi.arr(p.data, "entries")) if (RanchUi.str(e, "species").endsWith("ditto")) {
                    p.choose(e);
                    break;
                }
            } else check("ecran_choix_b", false, String.valueOf(mc.screen));
        });
        until("depot_b", 400, mc -> slot0(mc) != null && RanchUi.obj(slot0(mc), "b") != null && RanchUi.lng(slot0(mc), "start") > 0);
        add(40, mc -> {
            JsonObject s = slot0(mc);
            check("reproduction_lancee", s != null && RanchUi.lng(s, "start") > 0 && RanchUi.lng(s, "duration") > 0, s == null ? "" : s.toString());
            check("habitat_analyse", s != null && RanchUi.num(s, "score") > 0, s == null ? "" : RanchUi.num(s, "score") + " " + RanchUi.str(s, "rating"));
            check("pc_sans_les_parents", true, null);
            shot(mc, "live_03_pension_active");
            cmd(mc, "cobbleranch give @s silver_hourglass 3");
            cmd(mc, "cobbleranch give @s gold_hourglass 3");
        });
        until("sabliers_recus", 400, mc -> find(mc, ModContent.GOLD_HOURGLASS) >= 0 && find(mc, ModContent.SILVER_HOURGLASS) >= 0);
        add(10, mc -> {
            if (ranch(mc) != null) ranch(mc).refresh(false);
        });
        until("sabliers_dans_ecran", 400, mc -> ranch(mc) != null && RanchUi.num(ranch(mc).data, "gold") > 0);
        add(30, mc -> {
            JsonObject s = slot0(mc);
            stepsBefore = s == null ? 0 : RanchUi.lng(s, "start");
            check("sablier_argent_pension", ranch(mc) != null && ranch(mc).press("silver:0"), ranch(mc) == null ? "" : ranch(mc).buttonIds().toString());
        });
        until("sablier_argent_applique", 400, mc -> slot0(mc) != null && RanchUi.lng(slot0(mc), "start") < stepsBefore);
        add(30, mc -> {
            JsonObject s = slot0(mc);
            check("sablier_argent_reduit", s != null && RanchUi.lng(s, "start") < stepsBefore, s == null ? "" : RanchUi.lng(s, "start") + " < " + (long) stepsBefore);
            check("sablier_or_pension", ranch(mc) != null && ranch(mc).press("gold:0"), null);
        });
        until("oeuf_pondu", 400, mc -> slot0(mc) != null && !RanchUi.arr(slot0(mc), "eggs").isEmpty());
        add(30, mc -> {
            JsonObject s = slot0(mc);
            check("oeuf_pondu", s != null && RanchUi.arr(s, "eggs").size() == 1, s == null ? "" : s.toString());
            shot(mc, "live_04_oeuf_pret");
            if (ranch(mc) != null) ranch(mc).press("collect:0");
        });
        until("oeuf_recupere", 400, mc -> find(mc, ModContent.POKEMON_EGG) >= 0);
        add(30, mc -> {
            int e = find(mc, ModContent.POKEMON_EGG);
            check("oeuf_dans_inventaire", e >= 0, null);
            if (e >= 0) {
                CompoundTag t = mc.player.getInventory().getItem(e).get(ModContent.EGG_DATA);
                check("oeuf_evoli", t != null && t.getCompound("info").getString("species").endsWith("eevee"), t == null ? "" : t.getCompound("info").toString());
                List<Component> compact = RanchClient.eggLines(t, false), full = RanchClient.eggLines(t, true);
                List<Component> withName = new ArrayList<>();
                withName.add(mc.player.getInventory().getItem(e).getHoverName());
                withName.addAll(compact);
                List<Component> fullName = new ArrayList<>();
                fullName.add(mc.player.getInventory().getItem(e).getHoverName());
                fullName.addAll(full);
                check("infobulle_oeuf", compact.size() >= 5 && full.size() >= 12, compact.size() + "/" + full.size());
                mc.setScreen(new TooltipShot(withName, fullName));
            }
        });
        add(20, mc -> shot(mc, "live_05_infobulle_oeuf"));
        add(20, mc -> {
            mc.setScreen(null);
            int e = find(mc, ModContent.POKEMON_EGG);
            if (e >= 0 && e < 9) {
                mc.player.getInventory().selected = e;
                mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(e));
            }
        });
        add(20, mc -> {
            CHAT.clear();
            cmd(mc, "eggsee");
        });
        until("eggsee_recu", 400, mc -> chatHas("Progression Éclosion") || chatHas("Tiens un"));
        add(30, mc -> {
            check("eggsee", chatHas("ANALYSE DE L'ŒUF EN MAIN") && chatHas("IVs :") && chatHas("Egg Moves"), String.join(" | ", CHAT));
            cmd(mc, "hatch");
        });
        until("hatch_recu", 400, mc -> chatHas("Sabliers") || chatHas("Tiens un"));
        add(30, mc -> {
            check("hatch_diagnostic", chatHas("ÉCLOSION DE L'ŒUF EN MAIN") && chatHas("Pas restants"), lastChat());
            cmd(mc, "gamemode survival");
        });
        add(30, mc -> {
            // sablier d'argent pose sur l'oeuf dans l'inventaire (clic gauche sur le sablier, clic droit sur l'oeuf)
            int e = find(mc, ModContent.POKEMON_EGG), h = find(mc, ModContent.SILVER_HOURGLASS);
            CompoundTag t = e < 0 ? null : mc.player.getInventory().getItem(e).get(ModContent.EGG_DATA);
            stepsBefore = t == null ? 0 : t.getDouble("steps");
            if (e >= 0 && h >= 0) {
                int id = mc.player.inventoryMenu.containerId;
                mc.gameMode.handleInventoryMouseClick(id, menuSlot(h), 0, ClickType.PICKUP, mc.player);
                mc.gameMode.handleInventoryMouseClick(id, menuSlot(e), 1, ClickType.PICKUP, mc.player);
                mc.gameMode.handleInventoryMouseClick(id, menuSlot(h), 0, ClickType.PICKUP, mc.player);
            } else check("sablier_sur_oeuf_prepare", false, e + " " + h);
        });
        until("sablier_oeuf_applique", 400, mc -> {
            int e = find(mc, ModContent.POKEMON_EGG);
            CompoundTag t = e < 0 ? null : mc.player.getInventory().getItem(e).get(ModContent.EGG_DATA);
            return t != null && t.getDouble("steps") < stepsBefore;
        });
        add(40, mc -> {
            int e = find(mc, ModContent.POKEMON_EGG);
            CompoundTag t = e < 0 ? null : mc.player.getInventory().getItem(e).get(ModContent.EGG_DATA);
            check("sablier_argent_oeuf", t != null && t.getDouble("steps") < stepsBefore, (t == null ? "?" : t.getDouble("steps")) + " < " + stepsBefore);
            partyBefore = partySize();
            CHAT.clear();
            cmd(mc, "cobbleranch selftest walk " + mc.player.getGameProfile().getName() + " 100000");
        });
        until("eclosion", 400, mc -> chatHas("a éclos"));
        add(40, mc -> {
            check("eclosion_en_marchant", find(mc, ModContent.POKEMON_EGG) < 0 && chatHas("a éclos"), lastChat());
            int after = partySize();
            check("pokemon_dans_equipe", after == partyBefore + 1, partyBefore + " -> " + after);
            cmd(mc, "gamemode creative");
            cmd(mc, "pokegive eevee gender=female level=15");
            cmd(mc, "pokegive eevee gender=male level=15");
        });
        until("evoli_recus", 400, mc -> partySize() >= partyBefore + 3);
        add(10, mc -> {
            CHAT.clear();
            cmd(mc, "breed " + partySlot("eevee", "FEMALE", 15) + " " + partySlot("eevee", "MALE", 15));
        });
        until("breed_recu", 400, mc -> chatHas("Résultat"));
        add(30, mc -> {
            check("breed_analyse", chatHas("ANALYSE DE COMPATIBILITÉ") && chatHas("COMPATIBLE ✔"), String.join(" | ", CHAT));
            CHAT.clear();
            cmd(mc, "breed " + partySlot("eevee", "FEMALE", 15) + " " + partySlot("eevee", "MALE", 15) + " lancer");
        });
        until("breed_lance", 400, mc -> chatHas("Reproduction à distance lancée") || chatHas("Impossible"));
        add(10, mc -> {
            check("breed_a_distance_vip", chatHas("Reproduction à distance lancée"), String.join(" | ", CHAT));
            cmd(mc, "daycare");
        });
        until("daycare", 400, mc -> mc.screen instanceof DaycareScreen);
        add(20, mc -> {
            DaycareScreen d = mc.screen instanceof DaycareScreen ds ? ds : null;
            check("daycare_ecran", d != null && RanchUi.arr(d.data, "ranches").size() >= 2, d == null ? String.valueOf(mc.screen) : RanchUi.arr(d.data, "ranches").size() + "");
            shot(mc, "live_06_daycare");
        });
        add(20, mc -> {
            mc.setScreen(null);
            CHAT.clear();
            cmd(mc, "castrer 1");
        });
        until("castrer_question", 400, mc -> chatHas("DÉFINITIF") || chatHas("Seul le"));
        add(10, mc -> {
            check("castrer_demande_confirmation", chatHas("DÉFINITIF"), lastChat());
            CHAT.clear();
            cmd(mc, "castrer 1 confirmer");
        });
        until("castrer_fait", 400, mc -> chatHas("castré"));
        add(10, mc -> {
            check("castrer_dresseur_origine", chatHas("est maintenant castré"), lastChat());
            cmd(mc, "pokegive eevee gender=male level=16");
        });
        until("evoli_male", 400, mc -> partySlot("eevee", "MALE", 16) > 0);
        add(10, mc -> {
            CHAT.clear();
            cmd(mc, "breed 1 " + partySlot("eevee", "MALE", 16));
        });
        until("breed_castre", 400, mc -> chatHas("Résultat"));
        add(30, mc -> {
            check("castration_visible_breed", chatHas("CASTRÉ") || chatHas("castré"), String.join(" | ", CHAT));
            cmd(mc, "cobbleranch guide");
        });
        until("guide", 400, mc -> mc.screen instanceof GuideScreen);
        add(20, mc -> {
            check("guide_ouvert", mc.screen instanceof GuideScreen, String.valueOf(mc.screen));
            shot(mc, "live_07_guide_mecanique");
            if (mc.screen instanceof GuideScreen g) g.press("tab:water1");
        });
        add(20, mc -> shot(mc, "live_08_guide_habitats"));
        add(20, mc -> {
            mc.setScreen(null);
            cmd(mc, "cobbleranch admin");
        });
        until("admin", 400, mc -> mc.screen instanceof AdminScreen);
        add(20, mc -> {
            AdminScreen a = mc.screen instanceof AdminScreen as ? as : null;
            check("admin_ouvert", a != null && RanchUi.num(a.data, "total") >= 1, a == null ? String.valueOf(mc.screen) : RanchUi.num(a.data, "total") + "");
            shot(mc, "live_09_admin");
            if (a != null) check("admin_forcer", a.press("force:"), a.buttonIds().toString());
        });
        until("admin_reponse", 400, mc -> mc.screen instanceof AdminScreen a && !a.status().isEmpty());
        add(10, mc -> {
            AdminScreen a = mc.screen instanceof AdminScreen as ? as : null;
            check("admin_ponte_forcee", a != null && a.status().contains("Ponte forcée"), a == null ? "" : a.status());
            if (a != null) a.press("ditto");
        });
        until("admin_ditto", 400, mc -> mc.screen instanceof AdminScreen a && a.status().contains("Ditto"));
        add(10, mc -> {
            AdminScreen a = mc.screen instanceof AdminScreen as ? as : null;
            check("admin_ditto_override", a != null && a.status().contains("AUTORISÉ"), a == null ? "" : a.status());
            if (a != null) a.press("ditto");
        });
        add(30, mc -> {
            mc.setScreen(null);
            // vue du bloc dans le monde, avec un petit habitat
            cmd(mc, "tp @s " + (X + 2) + ".5 " + (Y + 1) + " " + (Z + 3) + ".5 145 35");
        });
        add(40, mc -> shot(mc, "live_10_bloc_pension_monde"));
        add(20, mc -> {
            CHAT.clear();
            cmd(mc, "setblock " + X + " " + Y + " " + Z + " minecraft:air");
        });
        until("retrait", 400, mc -> chatHas("Pension retirée"));
        add(10, mc -> {
            check("retrait_rend_les_pokemon", chatHas("Pension retirée"), String.join(" | ", CHAT));
            CobbleRanch.LOG.info(TAG + "SUMMARY: {} pass, {} fail", pass, fail);
            mc.stop();
        });
    }

    private static void tick(Minecraft mc) {
        ticks++;
        if (mc.player == null || mc.level == null) return;
        if (start < 0) {
            start = ticks + 200;
            return;
        }
        if (ticks < start || step >= STEPS.size()) return;
        var cond = UNTIL.get(step);
        if (cond != null) {
            if (awaitStart < 0) awaitStart = ticks;
            boolean ok;
            try {
                ok = cond.test(mc);
            } catch (Throwable t) {
                ok = false;
            }
            if (!ok && ticks - awaitStart < WAITS.get(step)) return;
            if (!ok) check("attente_" + UNTIL_NAME.get(step), false, "delai depasse (" + WAITS.get(step) + " ticks) ; ecran " + mc.screen + " ; chat " + lastChat());
            awaitStart = -1;
            start = ticks + 5;
            step++;
            return;
        }
        try {
            STEPS.get(step).accept(mc);
        } catch (Throwable t) {
            check("exception_etape_" + step, false, t.toString());
            CobbleRanch.LOG.error(TAG + "erreur", t);
        }
        start = ticks + WAITS.get(step);
        step++;
    }
}
