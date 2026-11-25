package fr.cobblepulse.ranch.client;

import com.google.gson.JsonObject;
import fr.cobblepulse.ranch.registry.ModContent;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Entrainement Ultime. Capsule d'Argent : choix de la statistique a monter a 31 en combat. Capsule d'Or :
 * confirmation pour toutes. Les deux barres montrent l'IV d'origine (transmis en reproduction) et l'IV en combat.
 */
public final class CapsuleScreen extends RanchUi {
    private final JsonObject data;
    private final boolean gold;

    public CapsuleScreen(JsonObject data) {
        super(bool(data, "gold") ? "Capsule d'Or" : "Capsule d'Argent");
        this.data = data;
        this.gold = bool(data, "gold");
        String f = str(data, "flash");
        if (!f.isEmpty()) {
            status = f;
            statusColor = bool(data, "flash_ok") ? OK : ERROR;
        }
    }

    public boolean gold() {
        return gold;
    }

    @Override
    protected void init() {
        layout(320, 232);
    }

    private void choose(String stat) {
        JsonObject a = new JsonObject();
        a.addProperty("slot", num(data, "slot"));
        a.addProperty("stat", stat);
        a.addProperty("id", str(data, "id"));
        status = "Entraînement en cours...";
        statusColor = MUTED;
        RanchClient.send("capsule", a);
    }

    @Override
    protected void draw(GuiGraphics g, int mx, int my, float pt) {
        panel(g, px, py, pw, ph, PANEL, gold ? GOLD : SILVER);
        g.renderItem(new ItemStack(gold ? ModContent.GOLD_BOTTLE_CAP : ModContent.SILVER_BOTTLE_CAP), px + 8, py + 6);
        g.drawString(font, getTitle().getString() + " — Entraînement Ultime", px + 28, py + 7, gold ? GOLD : SILVER, false);
        String who = str(data, "name") + "  Niv. " + num(data, "level") + "   (" + num(data, "count") + " en main)";
        g.drawString(font, who, px + 28, py + 17, TEXT, false);
        g.drawString(font, gold ? "Toutes les statistiques passeront à 31 en combat :" : "Choisis la statistique à monter à 31 en combat :",
                px + 10, py + 32, MUTED, false);

        List<JsonObject> stats = arr(data, "stats");
        int y = py + 45;
        int barX = px + 92, barW = pw - (gold ? 156 : 208);
        for (JsonObject s : stats) {
            int base = num(s, "base"), eff = num(s, "eff");
            boolean can = bool(s, "trainable");
            panel(g, px + 8, y - 2, pw - 16, 18, CARD, BORDER);
            g.drawString(font, str(s, "name"), px + 13, y + 3, TEXT, false);
            // barre du haut : combat (apres capsule en apercu), barre du bas : IV d'origine
            int after = can ? 31 : eff;
            bar(g, barX, y + 1, barW, 6, after / 31.0, can ? 0x6640C8E8 : ACCENT);
            g.fill(barX + 1, y + 2, barX + 1 + (int) Math.round(eff / 31.0 * (barW - 2)), y + 6, ACCENT);
            bar(g, barX, y + 8, barW, 5, base / 31.0, MUTED);
            String v = can ? eff + " → 31" : "31 ✔";
            g.drawString(font, v, barX + barW + 6, y + 3, can ? OK : MUTED, false);
            tip(px + 8, y - 2, pw - (gold ? 16 : 70), 18, List.of(
                    Component.literal(str(s, "name")).withStyle(ChatFormatting.AQUA),
                    Component.literal("Combat : " + eff + (can ? " → 31" : " (maximum)")).withStyle(ChatFormatting.GREEN),
                    Component.literal("IV d'origine : " + base + " — c'est lui qui est transmis en reproduction").withStyle(ChatFormatting.GRAY)));
            if (!gold) btn(g, "stat_" + str(s, "key"), px + pw - 62, y - 1, 50, 14, can ? "Choisir" : "Max", can, mx, my, () -> choose(str(s, "key")));
            y += 20;
        }
        // legende des deux barres
        g.fill(px + 10, y + 3, px + 18, y + 7, ACCENT);
        g.drawString(font, "IV en combat" + (gold ? "" : " (aperçu)"), px + 22, y + 1, MUTED, false);
        int lx = px + 30 + font.width("IV en combat (aperçu)");
        g.fill(lx, y + 3, lx + 8, y + 7, MUTED);
        g.drawString(font, "IV d'origine (transmis)", lx + 12, y + 1, MUTED, false);
        y += 12;
        y = wrap(g, "Seules les statistiques de combat changent : la reproduction transmet toujours les IV d'origine.", px + 10, y + 2, pw - 20, MUTED);
        if (!status.isEmpty()) g.drawString(font, font.plainSubstrByWidth(status, pw - 20), px + 10, py + ph - 34, statusColor, false);
        if (gold) btn(g, "gold", px + 10, py + ph - 22, 150, 16, "Tout monter à 31", true, mx, my, () -> choose("tout"));
        btn(g, "close", px + pw - 88, py + ph - 22, 78, 16, "Fermer", true, mx, my, this::onClose);
    }
}
