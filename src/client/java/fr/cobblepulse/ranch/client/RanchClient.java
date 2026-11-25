package fr.cobblepulse.ranch.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobblepulse.ranch.CobbleRanch;
import fr.cobblepulse.ranch.net.RanchNet;
import fr.cobblepulse.ranch.registry.ModContent;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Cote client : ecrans pilotes par le serveur, infobulle des oeufs, couleur des oeufs selon le type. */
public final class RanchClient implements ClientModInitializer {
    private static final String[] STATS = {"PV", "Atk", "Def", "SpA", "SpD", "Spe"};

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(RanchNet.Data.TYPE, (payload, ctx) -> ctx.client().execute(() -> handle(payload.kind(), payload.json())));
        BlockRenderLayerMap.INSTANCE.putBlock(ModContent.RANCH_BLOCK, RenderType.cutout());
        EggModels.register();
        ItemTooltipCallback.EVENT.register((stack, ctx, flag, lines) -> {
            if (stack.is(ModContent.POKEMON_EGG)) eggTooltip(stack, lines);
        });
        if (Boolean.getBoolean("cobblepulse_ranch.livetest")) LiveTest.install();
    }

    public static void send(String action, JsonObject args) {
        if (!ClientPlayNetworking.canSend(RanchNet.Action.TYPE)) return;
        ClientPlayNetworking.send(new RanchNet.Action(action, args == null ? "{}" : args.toString()));
    }

    private static void handle(String kind, String json) {
        Minecraft mc = Minecraft.getInstance();
        JsonObject o;
        try {
            o = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            return;
        }
        try {
            switch (kind) {
                case "ranch" -> {
                    if (mc.screen instanceof RanchScreen rs && rs.id().equals(RanchUi.str(o, "id"))) rs.update(o);
                    else mc.setScreen(new RanchScreen(o));
                }
                case "picker" -> {
                    RanchScreen back = mc.screen instanceof RanchScreen rs ? rs : mc.screen instanceof PickerScreen ps ? ps.back : null;
                    mc.setScreen(new PickerScreen(o, back));
                }
                case "guide" -> mc.setScreen(new GuideScreen(o, mc.screen));
                case "capsule" -> mc.setScreen(new CapsuleScreen(o));
                case "capsule_close" -> {
                    if (mc.screen instanceof CapsuleScreen) mc.setScreen(null);
                }
                case "daycare" -> mc.setScreen(new DaycareScreen(o));
                case "admin" -> {
                    if (mc.screen instanceof AdminScreen as) as.update(o);
                    else mc.setScreen(new AdminScreen(o));
                }
                default -> {
                }
            }
        } catch (Throwable t) {
            CobbleRanch.LOG.error("[CobblePulse-Ranch] Paquet {} illisible", kind, t);
        }
    }

    // ------------------------------------------------------------------------------------------ infobulle de l'oeuf

    static void eggTooltip(ItemStack stack, List<Component> lines) {
        CompoundTag t = stack.get(ModContent.EGG_DATA);
        if (t == null) return;
        // icone du GTS de CobblePulse Utils : la fiche complete est deja dans la description
        if (stack.has(DataComponents.LORE) && !stack.get(DataComponents.LORE).lines().isEmpty()) return;
        lines.addAll(eggLines(t, Screen.hasShiftDown()));
    }

    static List<Component> eggLines(CompoundTag t, boolean full) {
        List<Component> l = new ArrayList<>();
        CompoundTag i = t.getCompound("info");
        int total = Math.max(1, t.getInt("total"));
        double steps = t.getDouble("steps");
        double prog = Math.max(0, Math.min(1, 1 - steps / total));
        l.add(Component.literal("----------------------------------------").withStyle(ChatFormatting.DARK_GRAY));
        MutableComponent sp = Component.translatableWithFallback(i.getString("species_key"), i.getString("species_name"));
        String form = i.getString("form");
        l.add(Component.literal("Espèce : ").withStyle(ChatFormatting.GRAY).append(sp.withStyle(ChatFormatting.WHITE))
                .append(Component.literal(form.isEmpty() ? "" : " (" + form + ")").withStyle(ChatFormatting.WHITE))
                .append(Component.literal(i.getBoolean("shiny") ? "  ★" : "").withStyle(ChatFormatting.YELLOW)));
        int n = (int) Math.round(prog * 20);
        l.add(Component.literal("Progression : ").withStyle(ChatFormatting.GRAY).append(Component.literal("[" + "=".repeat(n) + " ".repeat(20 - n) + "] "
                + Math.round(prog * 100) + "%").withStyle(ChatFormatting.GREEN)));
        l.add(Component.literal("Statut : ").withStyle(ChatFormatting.GRAY).append(Component.literal(status(prog)).withStyle(ChatFormatting.WHITE)));
        if (!full) {
            l.add(Component.literal("Maj : fiche complète (talent, IV, capacités…)").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            l.add(Component.literal("Talent : ").withStyle(ChatFormatting.GRAY).append(Component.translatableWithFallback(i.getString("ability_key"), i.getString("ability_name"))
                    .withStyle(ChatFormatting.WHITE)).append(Component.literal(i.getBoolean("hidden") ? " (TALENT CACHÉ)" : " (Normal)")
                    .withStyle(i.getBoolean("hidden") ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.DARK_GRAY)));
            String plus = i.getString("nature_plus"), minus = i.getString("nature_minus");
            l.add(Component.literal("Nature : ").withStyle(ChatFormatting.GRAY).append(Component.translatableWithFallback(i.getString("nature_key"), i.getString("nature_name"))
                    .withStyle(ChatFormatting.WHITE)).append(Component.literal(plus.isEmpty() || plus.equals(minus) ? "" : " (+" + plus + ", -" + minus + ")").withStyle(ChatFormatting.DARK_GRAY)));
            l.add(Component.literal("Sexe : ").withStyle(ChatFormatting.GRAY).append(Component.literal(switch (i.getString("gender")) {
                case "male" -> "♂ Mâle";
                case "female" -> "♀ Femelle";
                default -> "Asexué";
            }).withStyle(ChatFormatting.WHITE)).append(Component.literal("  Poké Ball : ").withStyle(ChatFormatting.GRAY))
                    .append(Component.translatableWithFallback(i.getString("ball_key"), i.getString("ball_name")).withStyle(ChatFormatting.WHITE)));
            int[] ivs = i.getIntArray("ivs");
            int tot = 0;
            for (int v : ivs) tot += v;
            for (int k = 0; k + 1 < 6 && ivs.length >= 6; k += 2) {
                l.add(Component.literal(String.format(Locale.ROOT, "%-3s : ", STATS[k])).withStyle(ChatFormatting.GRAY).append(iv(ivs[k]))
                        .append(Component.literal(String.format(Locale.ROOT, "   %-3s : ", STATS[k + 1])).withStyle(ChatFormatting.GRAY)).append(iv(ivs[k + 1])));
            }
            l.add(Component.literal(String.format(Locale.US, "Total IVs : %d / 186 (%.1f%%)", tot, tot * 100.0 / 186)).withStyle(ChatFormatting.AQUA));
            ListTag moves = i.getList("egg_moves", Tag.TAG_COMPOUND);
            MutableComponent mv = Component.literal("Capacités œuf : ").withStyle(ChatFormatting.GRAY);
            if (moves.isEmpty()) mv.append(Component.literal("aucune").withStyle(ChatFormatting.DARK_GRAY));
            for (int k = 0; k < moves.size(); k++) {
                CompoundTag m = moves.getCompound(k);
                if (k > 0) mv.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
                mv.append(Component.translatableWithFallback(m.getString("key"), m.getString("name")).withStyle(ChatFormatting.WHITE));
            }
            l.add(mv);
            l.add(Component.literal(String.format(Locale.US, "Pas restants : %,d / %,d", Math.round(steps), total)).withStyle(ChatFormatting.GRAY));
            String parents = i.getString("parents");
            if (!parents.isEmpty()) l.add(Component.literal("Parents : " + parents).withStyle(ChatFormatting.DARK_GRAY));
        }
        l.add(Component.literal("----------------------------------------").withStyle(ChatFormatting.DARK_GRAY));
        return l;
    }

    private static Component iv(int v) {
        String label = v >= 31 ? "PARFAIT" : v >= 26 ? "EXCELLENT" : v >= 16 ? "BON" : v >= 6 ? "CORRECT" : "FAIBLE";
        return Component.literal(String.format(Locale.ROOT, "%2d [%s]", v, label)).withStyle(v >= 31 ? ChatFormatting.GREEN : v >= 16 ? ChatFormatting.WHITE : ChatFormatting.RED);
    }

    static String status(double p) {
        if (p >= 0.9) return "Des bruits proviennent de l'intérieur !";
        if (p >= 0.6) return "Semble s'agiter doucement !";
        if (p >= 0.3) return "Il bouge de temps en temps.";
        return "Il faudra encore du temps.";
    }
}
