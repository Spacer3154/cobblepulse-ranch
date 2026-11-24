package fr.cobblepulse.ranch.client;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Ecran du Bloc de Pension : 3 emplacements independants (parents choisis dans le PC, objets tenus et leur effet,
 * fertilite, avancement et compte a rebours, conditions 5x5, oeufs a recuperer, sabliers), Rafraichir, Guide, Quitter.
 * Le compte a rebours est un affichage : a son terme, l'ecran demande au serveur de reevaluer la Pension.
 */
public final class RanchScreen extends RanchUi {
    JsonObject data;
    private long offset;
    private long lastAuto;

    public RanchScreen(JsonObject o) {
        super("Pension");
        update(o);
    }

    void update(JsonObject o) {
        data = o;
        offset = lng(o, "now") - System.currentTimeMillis();
        String m = str(o, "message");
        if (!m.isEmpty()) {
            status = m;
            statusColor = m.startsWith("✖") ? ERROR : OK;
        }
    }

    String id() {
        return str(data, "id");
    }

    private void send(String action, int slot, Boolean first, String extra, Object value) {
        JsonObject o = new JsonObject();
        o.addProperty("ranch", id());
        if (slot >= 0) o.addProperty("slot", slot);
        if (first != null) o.addProperty("first", first);
        if (extra != null) {
            if (value instanceof Boolean b) o.addProperty(extra, b);
            else o.addProperty(extra, String.valueOf(value));
        }
        RanchClient.send(action, o);
    }

    void refresh(boolean scan) {
        send("ranch_refresh", -1, null, "scan", scan);
    }

    @Override
    protected void init() {
        layout(560, 300);
    }

    @Override
    public void tick() {
        // fin d'un compte a rebours : demande de reevaluation (evenement explicite cote serveur)
        long now = System.currentTimeMillis() + offset;
        for (JsonObject s : arr(data, "slots")) {
            long start = lng(s, "start"), dur = lng(s, "duration");
            if (start > 0 && dur > 0 && now >= start + dur && System.currentTimeMillis() - lastAuto > 2500) {
                lastAuto = System.currentTimeMillis();
                refresh(false);
                break;
            }
        }
    }

    @Override
    protected void draw(GuiGraphics g, int mx, int my, float pt) {
        panel(g, px, py, pw, ph, PANEL, ACCENT);
        boolean remote = bool(data, "remote");
        g.drawString(font, remote ? "PENSION À DISTANCE" : "PENSION COBBLERANCH", px + 8, py + 7, GOLD, false);
        String sub = str(data, "where") + (bool(data, "orphan") ? "  —  BLOC INTROUVABLE (récupération)" : "");
        g.drawString(font, font.plainSubstrByWidth(sub, pw - 180), px + 150, py + 7, bool(data, "orphan") ? WARN : MUTED, false);
        List<JsonObject> slots = arr(data, "slots");
        int top = py + 20;
        int bottom = py + ph - 26;
        int n = Math.max(1, slots.size());
        int ch = Math.min(80, (bottom - top - (n - 1) * 4) / n);
        for (int i = 0; i < slots.size(); i++) drawSlot(g, slots.get(i), px + 6, top + i * (ch + 4), pw - 12, ch, mx, my);
        if (!status.isEmpty()) g.drawString(font, font.plainSubstrByWidth(status, pw - 260), px + 8, py + ph - 18, statusColor, false);
        btn(g, "refresh", px + pw - 246, py + ph - 22, 78, 16, "Rafraîchir", true, mx, my, () -> refresh(true));
        btn(g, "guide", px + pw - 164, py + ph - 22, 74, 16, "Guide", true, mx, my, () -> RanchClient.send("guide", new JsonObject()));
        btn(g, "close", px + pw - 86, py + ph - 22, 78, 16, "Quitter", true, mx, my, this::onClose);
    }

    private void drawSlot(GuiGraphics g, JsonObject s, int x, int y, int w, int h, int mx, int my) {
        panel(g, x, y, w, h, CARD, BORDER);
        int idx = num(s, "index");
        long now = System.currentTimeMillis() + offset;
        long start = lng(s, "start"), dur = lng(s, "duration");
        boolean running = start > 0 && dur > 0;
        String pause = str(s, "pause");
        JsonObject a = obj(s, "a"), b = obj(s, "b");
        g.drawString(font, "SLOT " + (idx + 1), x + 6, y + 4, ACCENT, false);
        String offspring = str(s, "offspring");
        if (!offspring.isEmpty()) g.drawString(font, "→ œuf de " + offspring, x + 50, y + 4, MUTED, false);
        int leftW = (int) (w * 0.52);
        parentRow(g, a, "A", idx, true, x + 6, y + 16, leftW - 10, mx, my);
        parentRow(g, b, "B", idx, false, x + 6, y + 16 + (h - 20) / 2, leftW - 10, mx, my);

        // colonne droite : progression, conditions, fertilite, oeufs, sabliers
        int rx = x + leftW, rw = w - leftW - 6;
        double prog = running ? Math.min(1.0, (now - start) / (double) dur) : 0;
        String ptxt;
        int pc;
        if (running) {
            ptxt = prog >= 1 ? "Ponte imminente…" : "Ponte dans " + time(start + dur - now);
            pc = OK;
        } else if (a == null && b == null && arr(s, "eggs").isEmpty()) {
            ptxt = "INACTIF";
            pc = MUTED;
        } else {
            ptxt = pause.isEmpty() ? "En attente" : pause;
            pc = WARN;
        }
        g.drawString(font, "Progression", rx, y + 4, MUTED, false);
        bar(g, rx + 62, y + 4, rw - 62, 8, prog, running ? OK : 0xFF3A4454);
        g.drawString(font, font.plainSubstrByWidth(ptxt, rw), rx, y + 15, pc, false);
        if (!pause.isEmpty() && !running) tip(rx, y + 13, rw, 11, List.of(Component.literal(pause)));

        String rating = str(s, "rating");
        int score = num(s, "score");
        int ratingColor = switch (rating) {
            case "EXCELLENT" -> OK;
            case "BON" -> ACCENT;
            case "MOYEN" -> WARN;
            case "À DISTANCE" -> MUTED;
            default -> ERROR;
        };
        String cond = bool(data, "remote") ? "Conditions : à distance (durée fixe)" : a == null && b == null ? "Conditions 5x5 : —"
                : "Conditions 5x5 : " + rating + (score >= 0 ? " (" + score + "/" + num(data, "optimal") + ")" : "");
        if (a == null && b == null) ratingColor = MUTED;
        g.drawString(font, font.plainSubstrByWidth(cond, rw), rx, y + 27, ratingColor, false);
        List<Component> ht = new ArrayList<>();
        ht.add(Component.literal("Habitat 5x5 (couches Y et Y+1)" + (str(s, "group").isEmpty() ? "" : " — groupe " + str(s, "group"))).withStyle(ChatFormatting.GOLD));
        for (JsonObject l : arr(s, "habitat")) {
            ht.add(Component.literal(blockName(str(l, "block")) + " : " + num(l, "count") + " × " + num(l, "points") + " = " + num(l, "total")
                    + " / " + num(l, "max")).withStyle(num(l, "total") >= num(l, "max") && num(l, "max") > 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        }
        if (dur > 0) ht.add(Component.literal("Durée d'un cycle : " + time(dur)).withStyle(ChatFormatting.AQUA));
        ht.add(Component.literal("Varie les blocs : chacun est plafonné (anti-monoculture).").withStyle(ChatFormatting.DARK_GRAY));
        tip(rx, y + 25, rw, 11, ht);

        int fert = num(s, "fertility");
        int ly = y + 39;
        if (bool(data, "fertility_on") && fert >= 0 && (a != null || b != null)) {
            g.drawString(font, "Fertilité : " + fert + "/" + (a != null ? num(a, "fertility_max") : num(b, "fertility_max")), rx, ly, fert > 0 ? TEXT : ERROR, false);
        }
        String notes = str(s, "notes");
        if (!notes.isEmpty()) tip(rx, ly - 2, rw, 11, List.of(Component.literal("Dernière ponte : " + notes)));

        int by = y + h - 19;
        List<JsonObject> eggs = arr(s, "eggs");
        int bw = Math.min(110, rw / 2 - 2);
        btn(g, "collect:" + idx, rx, by, bw, 16, eggs.isEmpty() ? "Récupération : VIDE" : "Récupérer l'œuf" + (eggs.size() > 1 ? " ×" + eggs.size() : ""),
                !eggs.isEmpty(), mx, my, () -> send("ranch_collect", idx, null, null, null));
        if (!eggs.isEmpty()) {
            List<Component> et = new ArrayList<>();
            for (JsonObject e : eggs) et.add(Component.literal("Œuf de " + speciesName(e) + (str(e, "form").isEmpty() ? "" : " (" + str(e, "form") + ")")
                    + (bool(e, "shiny") ? " ★" : "")).withStyle(bool(e, "shiny") ? ChatFormatting.YELLOW : ChatFormatting.WHITE));
            tip(rx, by, bw, 16, et);
        }
        int hx = rx + bw + 4, hw = (rw - bw - 8) / 2;
        int silver = num(data, "silver"), gold = num(data, "gold");
        if (bool(data, "perm_silver"))
            btn(g, "silver:" + idx, hx, by, hw, 16, "Argent ×" + silver, running && silver > 0, mx, my, () -> send("ranch_hourglass", idx, null, "gold", false));
        if (bool(data, "perm_gold"))
            btn(g, "gold:" + idx, hx + hw + 4, by, hw, 16, "Or ×" + gold, running && gold > 0, mx, my, () -> send("ranch_hourglass", idx, null, "gold", true));
        tip(hx, by, hw * 2 + 4, 16, List.of(Component.literal("Sablier d'Argent : réduit le temps restant").withStyle(ChatFormatting.GRAY),
                Component.literal("Sablier d'Or : ponte immédiate").withStyle(ChatFormatting.GOLD)));
    }

    private void parentRow(GuiGraphics g, JsonObject p, String label, int idx, boolean first, int x, int y, int w, int mx, int my) {
        g.drawString(font, "Parent " + label + " :", x, y + 4, MUTED, false);
        int tx = x + 54;
        boolean orphan = bool(data, "orphan");
        if (p == null) {
            g.drawString(font, "[ Vide ]", tx, y + 4, 0xFF59626F, false);
            btn(g, "pick:" + idx + ":" + label, x + w - 58, y, 58, 16, "Choisir", !orphan, mx, my, () -> send("ranch_pick", idx, first, null, null));
            return;
        }
        String name = pokeName(p) + " " + gender(p);
        int nameW = Math.min(font.width(name), w - 54 - 122);
        g.drawString(font, font.plainSubstrByWidth(name, w - 54 - 122), tx, y + 4, bool(p, "shiny") ? GOLD : genderColor(p), false);
        String held = str(p, "held");
        int ix = tx + nameW + 4;
        if (!held.isEmpty()) {
            ResourceLocation rl = ResourceLocation.tryParse(held);
            if (rl != null && BuiltInRegistries.ITEM.containsKey(rl)) {
                g.pose().pushPose();
                g.pose().translate(ix, y, 0);
                g.pose().scale(0.75f, 0.75f, 1f);
                g.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(rl)), 0, 0);
                g.pose().popPose();
            }
        }
        List<Component> t = new ArrayList<>();
        t.add(Component.literal(pokeName(p) + " " + gender(p) + "  Nv. " + num(p, "level")).withStyle(bool(p, "shiny") ? ChatFormatting.YELLOW : ChatFormatting.WHITE));
        if (!str(p, "form").isEmpty()) t.add(Component.literal(str(p, "form")).withStyle(ChatFormatting.GRAY));
        t.add(Component.literal("Groupes : " + String.join(", ", strs(p, "groups"))).withStyle(ChatFormatting.GRAY));
        t.add(Component.literal("Talent : " + str(p, "ability") + " | Nature : " + str(p, "nature")).withStyle(ChatFormatting.GRAY));
        t.add(Component.literal("IV : " + num(p, "iv_total") + "/186").withStyle(ChatFormatting.GRAY));
        if (!held.isEmpty()) t.add(Component.literal("Tient : " + str(p, "held_name") + (str(p, "held_effect").isEmpty() ? "" : " — " + str(p, "held_effect")))
                .withStyle(str(p, "held_effect").isEmpty() ? ChatFormatting.GRAY : ChatFormatting.AQUA));
        if (bool(data, "fertility_on")) t.add(Component.literal("Fertilité : " + num(p, "fertility") + "/" + num(p, "fertility_max")).withStyle(ChatFormatting.GRAY));
        if (bool(p, "neutered")) t.add(Component.literal("✂ Castré").withStyle(ChatFormatting.RED));
        tip(tx, y, w - 54 - 118, 14, t);
        btn(g, "pick:" + idx + ":" + label, x + w - 118, y, 58, 16, "Changer", !orphan, mx, my, () -> send("ranch_pick", idx, first, null, null));
        btn(g, "take:" + idx + ":" + label, x + w - 58, y, 58, 16, "Retirer", true, mx, my, () -> send("ranch_withdraw", idx, first, null, null));
    }

    static String blockName(String id) {
        if (id.startsWith("#")) {
            String t = id.substring(1);
            return Component.translatableWithFallback("tag.block." + t.replace(':', '.').replace('/', '.'), "#" + t.substring(t.indexOf(':') + 1)).getString();
        }
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl != null && BuiltInRegistries.BLOCK.containsKey(rl)) return BuiltInRegistries.BLOCK.get(rl).getName().getString();
        return id;
    }
}
