package fr.cobblepulse.ranch.client;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Choix d'un parent dans le PC (equipe pour la pension a distance). Les Pokemon deja en Pension ne sont plus dans le
 * PC, donc absents. Les incompatibles sont grises avec la raison (groupe, sexe, castre, sterile...).
 */
public final class PickerScreen extends RanchUi {
    final JsonObject data;
    final RanchScreen back;
    private EditBox search;
    private double scroll;
    private boolean onlyOk;

    public PickerScreen(JsonObject o, RanchScreen back) {
        super("Choisir un parent");
        this.data = o;
        this.back = back;
    }

    @Override
    protected void init() {
        layout(460, 300);
        search = new EditBox(font, px + 8, py + 22, 180, 14, Component.literal("Recherche"));
        search.setHint(Component.literal("Rechercher…").withStyle(ChatFormatting.DARK_GRAY));
        search.setResponder(s -> scroll = 0);
        addRenderableWidget(search);
    }

    List<JsonObject> filtered() {
        String q = norm(search == null ? "" : search.getValue());
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject e : arr(data, "entries")) {
            if (onlyOk && !bool(e, "ok")) continue;
            if (!q.isEmpty() && !norm(pokeName(e) + " " + speciesName(e) + " " + String.join(" ", strs(e, "groups"))).contains(q)) continue;
            out.add(e);
        }
        return out;
    }

    void choose(JsonObject e) {
        JsonObject o = new JsonObject();
        o.addProperty("ranch", str(data, "ranch"));
        o.addProperty("slot", num(data, "slot"));
        o.addProperty("first", bool(data, "first"));
        o.addProperty("uuid", str(e, "uuid"));
        RanchClient.send("ranch_deposit", o);
    }

    @Override
    protected void draw(GuiGraphics g, int mx, int my, float pt) {
        panel(g, px, py, pw, ph, PANEL, ACCENT);
        boolean party = str(data, "source").equals("party");
        g.drawString(font, "Choisir le parent " + (bool(data, "first") ? "A" : "B") + " — slot " + (num(data, "slot") + 1)
                + (party ? " (équipe)" : " (PC)"), px + 8, py + 7, GOLD, false);
        JsonObject other = obj(data, "other");
        if (other != null) g.drawString(font, font.plainSubstrByWidth("Partenaire : " + pokeName(other) + " " + gender(other)
                + " — " + String.join(", ", strs(other, "groups")), pw - 200), px + 196, py + 25, MUTED, false);
        btn(g, "filter", px + pw - 120, py + 5, 112, 13, onlyOk ? "Compatibles : oui" : "Compatibles : tous", true, mx, my, () -> onlyOk = !onlyOk);
        List<JsonObject> list = filtered();
        int top = py + 42, bottom = py + ph - 26, rowH = 24;
        int visible = Math.max(1, (bottom - top) / rowH);
        int maxScroll = Math.max(0, list.size() - visible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        int first = (int) scroll;
        if (list.isEmpty()) g.drawString(font, party ? "Aucun Pokémon dans l'équipe." : "Aucun Pokémon dans le PC.", px + 10, top + 4, MUTED, false);
        for (int i = 0; i < visible && first + i < list.size(); i++) {
            JsonObject e = list.get(first + i);
            int y = top + i * rowH;
            boolean ok = bool(e, "ok");
            boolean hover = ok && in(mx, my, px + 6, y, pw - 12, rowH - 2);
            panel(g, px + 6, y, pw - 12, rowH - 2, hover ? CARD_ON : CARD, hover ? ACCENT : BORDER);
            String where = party ? "Équipe " + (num(e, "pos") + 1) : "Boîte " + (num(e, "box") + 1) + " · " + (num(e, "pos") + 1);
            g.drawString(font, where, px + 10, y + 3, MUTED, false);
            String name = pokeName(e) + " " + gender(e) + "  Nv." + num(e, "level");
            g.drawString(font, font.plainSubstrByWidth(name, 150), px + 76, y + 3, !ok ? 0xFF59626F : bool(e, "shiny") ? GOLD : genderColor(e), false);
            String groups = String.join(", ", strs(e, "groups"));
            g.drawString(font, font.plainSubstrByWidth(groups, 150), px + 76, y + 12, MUTED, false);
            String held = str(e, "held");
            if (!held.isEmpty()) {
                ResourceLocation rl = ResourceLocation.tryParse(held);
                if (rl != null && BuiltInRegistries.ITEM.containsKey(rl)) g.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(rl)), px + 232, y + 2);
            }
            String right;
            int rc;
            if (bool(e, "neutered")) {
                right = "✂ Castré";
                rc = ERROR;
            } else if (!ok) {
                right = "✖ " + str(e, "reason");
                rc = ERROR;
            } else {
                right = "✔ Compatible" + (bool(data, "fertility_on") || num(e, "fertility_max") > 0 ? "  Fert. " + num(e, "fertility") + "/" + num(e, "fertility_max") : "");
                rc = OK;
            }
            g.drawString(font, font.plainSubstrByWidth(right, pw - 270), px + 254, y + 7, rc, false);
            List<Component> t = new ArrayList<>();
            t.add(Component.literal(pokeName(e) + " " + gender(e)).withStyle(ChatFormatting.WHITE));
            t.add(Component.literal("Talent : " + str(e, "ability") + " | Nature : " + str(e, "nature")).withStyle(ChatFormatting.GRAY));
            t.add(Component.literal("IV : " + num(e, "iv_total") + "/186").withStyle(ChatFormatting.GRAY));
            if (!held.isEmpty()) t.add(Component.literal("Tient : " + str(e, "held_name") + (str(e, "held_effect").isEmpty() ? "" : " — " + str(e, "held_effect"))).withStyle(ChatFormatting.AQUA));
            if (!ok) t.add(Component.literal(str(e, "reason")).withStyle(ChatFormatting.RED));
            tip(px + 6, y, pw - 12, rowH - 2, t);
            if (ok) btns.add(new Btn("entry:" + str(e, "uuid"), px + 6, y, pw - 12, rowH - 2, name, true, () -> choose(e)));
        }
        if (maxScroll > 0) {
            int track = bottom - top;
            int th = Math.max(12, track * visible / list.size());
            int ty = top + (int) ((track - th) * (scroll / maxScroll));
            g.fill(px + pw - 5, ty, px + pw - 3, ty + th, ACCENT);
        }
        g.drawString(font, list.size() + " Pokémon", px + 8, py + ph - 18, MUTED, false);
        btn(g, "back", px + pw - 86, py + ph - 22, 78, 16, "Retour", true, mx, my, () -> {
            minecraft.setScreen(back);
            back.refresh(false);
        });
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        scroll -= dy;
        return true;
    }
}
