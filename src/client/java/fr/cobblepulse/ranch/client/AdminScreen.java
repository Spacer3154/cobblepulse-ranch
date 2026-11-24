package fr.cobblepulse.ranch.client;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Panneau d'administration (/cobbleranch admin). Seuls les boutons autorises sont dessines ; le serveur re-verifie
 * chaque action. Actions globales en deux clics (confirmation renvoyee par le serveur).
 */
public final class AdminScreen extends RanchUi {
    JsonObject data;
    private long offset;
    private EditBox search;
    private double scroll;

    public AdminScreen(JsonObject o) {
        super("Administration CobbleRanch");
        update(o);
    }

    void update(JsonObject o) {
        data = o;
        offset = lng(o, "now") - System.currentTimeMillis();
        String m = str(o, "message");
        status = m;
        statusColor = m.startsWith("Refusé") || m.startsWith("Action refusée") ? ERROR : m.contains("encore") || m.startsWith("Confirmation") ? WARN : OK;
    }

    @Override
    protected void init() {
        layout(600, 320);
        search = new EditBox(font, px + 120, py + 20, 150, 14, Component.literal("Joueur"));
        search.setHint(Component.literal("Dresseur…").withStyle(ChatFormatting.DARK_GRAY));
        search.setValue(str(data, "query"));
        addRenderableWidget(search);
    }

    private boolean perm(String k) {
        JsonObject p = obj(data, "perms");
        return bool(p, k);
    }

    void act(String action, String ranch, int slot, Long value) {
        JsonObject o = new JsonObject();
        o.addProperty("action", action);
        o.addProperty("query", search == null ? str(data, "query") : search.getValue());
        o.addProperty("filter", str(data, "filter"));
        o.addProperty("sort", str(data, "sort"));
        if (ranch != null) o.addProperty("ranch", ranch);
        if (slot >= 0) o.addProperty("slot", slot);
        if (value != null) o.addProperty("value", value);
        RanchClient.send("admin", o);
    }

    private void setFilterSort(String filter, String sort) {
        data.addProperty("filter", filter);
        data.addProperty("sort", sort);
        act("refresh", null, -1, null);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (search != null && search.isFocused() && (key == 257 || key == 335)) {
            act("refresh", null, -1, null);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    protected void draw(GuiGraphics g, int mx, int my, float pt) {
        panel(g, px, py, pw, ph, PANEL, ACCENT);
        g.drawString(font, "PANNEAU D'ADMINISTRATION COBBLERANCH", px + 8, py + 7, GOLD, false);
        g.drawString(font, "Recherche joueur :", px + 8, py + 23, MUTED, false);
        String filter = str(data, "filter"), sort = str(data, "sort");
        String fl = switch (filter) {
            case "all" -> "Filtre : Tous";
            case "orphan" -> "Filtre : Orphelines";
            default -> "Filtre : Actifs uniquement";
        };
        btn(g, "filter", px + 278, py + 19, 140, 16, fl, true, mx, my, () -> setFilterSort(switch (filter) {
            case "active" -> "all";
            case "all" -> "orphan";
            default -> "active";
        }, sort));
        btn(g, "sort", px + 422, py + 19, 96, 16, sort.equals("location") ? "Tri : lieu" : "Tri : dresseur", true, mx, my,
                () -> setFilterSort(filter, sort.equals("location") ? "player" : "location"));
        btn(g, "search", px + 522, py + 19, pw - 530, 16, "OK", true, mx, my, () -> act("refresh", null, -1, null));

        int top = py + 40, bottom = py + ph - 62;
        if (!perm("view")) {
            g.drawString(font, "Liste des reproductions masquée (permission cobbleranch.admin.view).", px + 10, top + 4, MUTED, false);
        } else {
            g.drawString(font, "REPRODUCTIONS " + (filter.equals("active") ? "ACTIVES" : filter.equals("orphan") ? "ORPHELINES" : "") + " (TOTAL : "
                    + num(data, "total") + ")", px + 8, top, ACCENT, false);
            List<JsonObject> list = arr(data, "entries");
            int rowH = 34;
            int y0 = top + 12;
            int visible = Math.max(1, (bottom - y0) / rowH);
            int maxScroll = Math.max(0, list.size() - visible);
            scroll = Math.max(0, Math.min(scroll, maxScroll));
            long now = System.currentTimeMillis() + offset;
            for (int i = 0; i < visible && (int) scroll + i < list.size(); i++) {
                JsonObject e = list.get((int) scroll + i);
                int y = y0 + i * rowH;
                panel(g, px + 6, y, pw - 12, rowH - 2, CARD, bool(e, "orphan") ? WARN : BORDER);
                String head = "• " + str(e, "owner") + " | " + (str(e, "chunk").isEmpty() ? str(e, "where") : "Chunk [" + str(e, "chunk") + "] " + str(e, "where"))
                        + " | Slot " + (num(e, "slot") + 1);
                g.drawString(font, font.plainSubstrByWidth(head, pw - 24), px + 10, y + 3, TEXT, false);
                long start = lng(e, "start"), dur = lng(e, "duration");
                String t = start > 0 ? "Temps restant : " + time(start + dur - now) : str(e, "pause").isEmpty() ? "inactif" : str(e, "pause");
                String line = str(e, "parents") + " | " + t + (num(e, "eggs") > 0 ? " | œufs : " + num(e, "eggs") : "")
                        + (num(e, "fertility") >= 0 ? " | fertilité " + num(e, "fertility") : "");
                g.drawString(font, font.plainSubstrByWidth(line, pw - 330), px + 18, y + 17, MUTED, false);
                String r = str(e, "ranch");
                int s = num(e, "slot");
                int bx = px + pw - 312;
                if (perm("modify")) {
                    btn(g, "force:" + r + ":" + s, bx, y + 14, 66, 14, "Forcer ponte", true, mx, my, () -> act("force", r, s, null));
                    btn(g, "fert:" + r + ":" + s, bx + 68, y + 14, 56, 14, "Fertilité…", true, mx, my, () -> minecraft.setScreen(new InputScreen(this,
                            "Fertilité des deux parents", "Nouvelle valeur (0 = stérile)", v -> act("fertility", r, s, v))));
                    btn(g, "time:" + r + ":" + s, bx + 126, y + 14, 48, 14, "Temps…", start > 0, mx, my, () -> minecraft.setScreen(new InputScreen(this,
                            "Temps restant avant la ponte", "Minutes", v -> act("time", r, s, v))));
                }
                if (perm("cancel")) btn(g, "cancel:" + r + ":" + s, bx + 176, y + 14, 56, 14, "Annuler", true, mx, my, () -> act("cancel", r, s, null));
                if (perm("delete")) btn(g, "delete:" + r + ":" + s, bx + 234, y + 14, 66, 14, "Supprimer", true, mx, my, () -> act("delete", r, s, null));
            }
            if (list.size() < num(data, "total")) g.drawString(font, "(" + list.size() + " affichées sur " + num(data, "total") + " : affine la recherche)",
                    px + 300, top, MUTED, false);
        }
        // controles globaux
        int gy = py + ph - 58;
        g.drawString(font, "CONTRÔLES GLOBAUX :", px + 8, gy, ACCENT, false);
        int bx = px + 8;
        if (perm("gdelete")) {
            btn(g, "purge_all", bx, gy + 11, 110, 16, "PURGER TOUT", true, mx, my, () -> act("purge_all", null, -1, null));
            bx += 114;
        }
        if (perm("gcancel")) {
            btn(g, "restore_all", bx, gy + 11, 120, 16, "RESTITUER TOUT", true, mx, my, () -> act("restore_all", null, -1, null));
            bx += 124;
        }
        if (perm("gmodify")) {
            btn(g, "force_all", bx, gy + 11, 120, 16, "FORCER TOUTES", true, mx, my, () -> act("force_all", null, -1, null));
            bx += 124;
        }
        if (perm("ditto")) {
            btn(g, "ditto", bx, gy + 11, 190, 16, "OVERRIDE : DITTO x DITTO (" + (bool(data, "ditto") ? "ON" : "OFF") + ")", true, mx, my,
                    () -> act("ditto", null, -1, null));
        }
        if (!status.isEmpty()) g.drawString(font, font.plainSubstrByWidth(status, pw - 200), px + 8, py + ph - 18, statusColor, false);
        if (perm("override")) btn(g, "reload", px + pw - 230, py + ph - 22, 140, 16, "Recharger Config (Reload)", true, mx, my, () -> act("reload", null, -1, null));
        btn(g, "close", px + pw - 86, py + ph - 22, 78, 16, "Fermer", true, mx, my, this::onClose);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        scroll -= dy;
        return true;
    }
}
