package fr.cobblepulse.ranch.client;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/** /daycare : etat de toutes les Pensions du dresseur ; ouverture a distance (VIP), pensions a distance et orphelines. */
public final class DaycareScreen extends RanchUi {
    final JsonObject data;
    private final long offset;
    private double scroll;

    public DaycareScreen(JsonObject o) {
        super("Mes Pensions");
        data = o;
        offset = lng(o, "now") - System.currentTimeMillis();
    }

    @Override
    protected void init() {
        layout(520, 300);
    }

    void open(String id) {
        JsonObject o = new JsonObject();
        o.addProperty("ranch", id);
        RanchClient.send("daycare_open", o);
    }

    @Override
    protected void draw(GuiGraphics g, int mx, int my, float pt) {
        panel(g, px, py, pw, ph, PANEL, ACCENT);
        List<JsonObject> ranches = arr(data, "ranches");
        g.drawString(font, "MES PENSIONS (" + ranches.size() + ")", px + 8, py + 7, GOLD, false);
        boolean vip = bool(data, "can_remote");
        long cd = lng(data, "remote_cooldown");
        g.drawString(font, vip ? (cd > 0 ? "Accès à distance dans " + time(cd * 1000) : "VIP : accès à distance disponible") : "Accès à distance : VIP",
                px + pw - 230, py + 7, vip ? (cd > 0 ? WARN : OK) : MUTED, false);
        int top = py + 20, bottom = py + ph - 26;
        g.enableScissor(px + 4, top, px + pw - 4, bottom);
        int y = top - (int) scroll;
        long now = System.currentTimeMillis() + offset;
        if (ranches.isEmpty()) g.drawString(font, "Aucune Pension. Pose un Bloc de Pension pour commencer.", px + 10, y + 4, MUTED, false);
        for (JsonObject r : ranches) {
            List<JsonObject> slots = arr(r, "slots");
            int h = 16 + slots.size() * 12 + 4;
            panel(g, px + 6, y, pw - 12, h, CARD, bool(r, "orphan") ? WARN : BORDER);
            g.drawString(font, (bool(r, "remote") ? "À distance (/breed)" : str(r, "where")) + (bool(r, "orphan") ? "  — bloc introuvable" : ""), px + 12, y + 4,
                    bool(r, "orphan") ? WARN : ACCENT, false);
            boolean direct = bool(r, "remote") || bool(r, "orphan");
            boolean can = direct || (vip && cd <= 0);
            if (y > top - 20 && y < bottom - 14)
                btn(g, "open:" + str(r, "id"), px + pw - 96, y + 2, 84, 13, direct ? "Ouvrir" : "Ouvrir (VIP)", can, mx, my, () -> open(str(r, "id")));
            int sy = y + 17;
            for (JsonObject s : slots) {
                JsonObject a = obj(s, "a"), b = obj(s, "b");
                String pair = (a == null ? "—" : pokeName(a) + gender(a)) + " x " + (b == null ? "—" : pokeName(b) + gender(b));
                g.drawString(font, "Slot " + (num(s, "index") + 1) + " : " + font.plainSubstrByWidth(pair, 200), px + 14, sy, a == null && b == null ? MUTED : TEXT, false);
                long start = lng(s, "start"), dur = lng(s, "duration");
                int eggs = arr(s, "eggs").size();
                String st;
                int col;
                if (start > 0 && dur > 0) {
                    double prog = Math.min(1, (now - start) / (double) dur);
                    bar(g, px + 270, sy, 80, 8, prog, OK);
                    st = time(start + dur - now);
                    col = OK;
                } else {
                    st = str(s, "pause").isEmpty() ? (a == null && b == null ? "inactif" : "en attente") : str(s, "pause");
                    col = a == null && b == null ? MUTED : WARN;
                }
                g.drawString(font, font.plainSubstrByWidth(st, pw - 380 - 60), px + 356, sy, col, false);
                if (eggs > 0) g.drawString(font, "Œufs ×" + eggs, px + pw - 60, sy, GOLD, false);
                sy += 12;
            }
            y += h + 4;
        }
        g.disableScissor();
        int content = y + (int) scroll - top;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, content - (bottom - top))));
        int mail = num(data, "mail");
        if (mail > 0) g.drawString(font, mail + " élément(s) dans ta boîte de réception (remis à la reconnexion).", px + 8, py + ph - 18, WARN, false);
        btn(g, "refresh", px + pw - 170, py + ph - 22, 78, 16, "Rafraîchir", true, mx, my, () -> RanchClient.send("daycare", new JsonObject()));
        btn(g, "close", px + pw - 86, py + ph - 22, 78, 16, "Fermer", true, mx, my, this::onClose);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        scroll -= dy * 12;
        return true;
    }
}
