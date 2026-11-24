package fr.cobblepulse.ranch.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Base des ecrans CobblePulse Ranch (memes couleurs que les ecrans de CobblePulse Utils). Les boutons sont redessines a
 * chaque image (listes defilantes simples) ; un clic envoie une intention au serveur, qui renvoie l'etat a jour.
 */
abstract class RanchUi extends Screen {
    static final int PANEL = 0xF0141922, CARD = 0xFF1A202A, CARD_ON = 0xFF263347, BORDER = 0xFF2E3A4C, ACCENT = 0xFF5FD3F3,
            GOLD = 0xFFFFD24A, TEXT = 0xFFE8ECF2, MUTED = 0xFF8E9AAB, OK = 0xFF6BE07A, WARN = 0xFFFFA94D, ERROR = 0xFFFF6B6B,
            MALE = 0xFF6FA8FF, FEMALE = 0xFFFF7FB0, SILVER = 0xFFC9D3DD;

    record Btn(String id, int x, int y, int w, int h, String label, boolean enabled, Runnable action) {}

    record Tip(int x, int y, int w, int h, List<Component> lines) {}

    protected final List<Btn> btns = new ArrayList<>();
    protected final List<Btn> lastBtns = new ArrayList<>();
    protected final List<Tip> tips = new ArrayList<>();
    protected int px, py, pw, ph;
    protected String status = "";
    protected int statusColor = MUTED;

    protected RanchUi(String title) {
        super(Component.literal(title));
    }

    protected void layout(int maxW, int maxH) {
        pw = Math.min(width - 12, maxW);
        ph = Math.min(height - 10, maxH);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
    }

    static void panel(GuiGraphics g, int x, int y, int w, int h, int fill, int border) {
        g.fill(x, y, x + w, y + h, fill);
        g.fill(x, y, x + w, y + 1, border);
        g.fill(x, y + h - 1, x + w, y + h, border);
        g.fill(x, y, x + 1, y + h, border);
        g.fill(x + w - 1, y, x + w, y + h, border);
    }

    static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && my >= y && mx < x + w && my < y + h;
    }

    static String norm(String s) {
        String n = java.text.Normalizer.normalize(s == null ? "" : s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT);
    }

    static String str(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    static int num(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : 0;
    }

    static long lng(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsLong() : 0;
    }

    static boolean bool(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() && o.get(k).getAsBoolean();
    }

    static JsonObject obj(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonObject() ? o.getAsJsonObject(k) : null;
    }

    static List<JsonObject> arr(JsonObject o, String k) {
        List<JsonObject> l = new ArrayList<>();
        if (o == null || !o.has(k) || !o.get(k).isJsonArray()) return l;
        for (JsonElement e : o.getAsJsonArray(k)) if (e.isJsonObject()) l.add(e.getAsJsonObject());
        return l;
    }

    static List<String> strs(JsonObject o, String k) {
        List<String> l = new ArrayList<>();
        if (o == null || !o.has(k) || !o.get(k).isJsonArray()) return l;
        for (JsonElement e : (JsonArray) o.get(k)) l.add(e.getAsString());
        return l;
    }

    /** Nom affiche d'un Pokemon (surnom, sinon espece traduite, avec le nom du serveur en secours). */
    static String pokeName(JsonObject p) {
        String nick = str(p, "nickname");
        if (!nick.isEmpty()) return nick;
        return Component.translatableWithFallback(str(p, "species_key"), str(p, "species_name")).getString();
    }

    static String speciesName(JsonObject p) {
        return Component.translatableWithFallback(str(p, "species_key"), str(p, "species_name")).getString();
    }

    static String gender(JsonObject p) {
        return switch (str(p, "gender")) {
            case "male" -> "♂";
            case "female" -> "♀";
            default -> "";
        };
    }

    static int genderColor(JsonObject p) {
        return switch (str(p, "gender")) {
            case "male" -> MALE;
            case "female" -> FEMALE;
            default -> MUTED;
        };
    }

    static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format(Locale.ROOT, "%dh %02dm %02ds", h, m, sec);
        if (m > 0) return String.format(Locale.ROOT, "%dm %02ds", m, sec);
        return sec + "s";
    }

    protected void btn(GuiGraphics g, String id, int x, int y, int w, int h, String label, boolean enabled, int mx, int my, Runnable action) {
        boolean hover = enabled && in(mx, my, x, y, w, h);
        panel(g, x, y, w, h, !enabled ? 0xFF161A20 : hover ? CARD_ON : 0xFF202838, hover ? ACCENT : BORDER);
        String l = font.plainSubstrByWidth(label, w - 6);
        g.drawString(font, l, x + (w - font.width(l)) / 2, y + (h - 8) / 2, enabled ? TEXT : 0xFF59626F, false);
        btns.add(new Btn(id, x, y, w, h, label, enabled, action));
    }

    protected void tip(int x, int y, int w, int h, List<Component> lines) {
        tips.add(new Tip(x, y, w, h, lines));
    }

    protected void bar(GuiGraphics g, int x, int y, int w, int h, double progress, int color) {
        g.fill(x, y, x + w, y + h, 0xFF0E1218);
        int f = (int) Math.round(Math.max(0, Math.min(1, progress)) * (w - 2));
        if (f > 0) g.fill(x + 1, y + 1, x + 1 + f, y + h - 1, color);
        panel(g, x, y, w, h, 0x00000000, BORDER);
    }

    protected int wrap(GuiGraphics g, String text, int x, int y, int w, int color) {
        for (FormattedCharSequence l : font.split(Component.literal(text), w)) {
            g.drawString(font, l, x, y, color, false);
            y += 10;
        }
        return y;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        btns.clear();
        tips.clear();
        renderTransparentBackground(g);
        draw(g, mx, my, pt);
        super.render(g, mx, my, pt);
        for (Tip t : tips) {
            if (in(mx, my, t.x(), t.y(), t.w(), t.h())) {
                g.renderComponentTooltip(font, t.lines(), mx, my);
                break;
            }
        }
        lastBtns.clear();
        lastBtns.addAll(btns);
    }

    protected abstract void draw(GuiGraphics g, int mx, int my, float pt);

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (Btn b : new ArrayList<>(lastBtns)) {
                if (b.enabled() && in(mx, my, b.x(), b.y(), b.w(), b.h())) {
                    Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
                    b.action().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Auto-test : appuie sur le bouton dont l'identifiant commence par ce texte (image precedente). */
    boolean press(String idPrefix) {
        for (Btn b : new ArrayList<>(lastBtns)) {
            if (b.id().startsWith(idPrefix) && b.enabled()) {
                b.action().run();
                return true;
            }
        }
        return false;
    }

    List<String> buttonIds() {
        List<String> l = new ArrayList<>();
        for (Btn b : lastBtns) l.add(b.id() + (b.enabled() ? "" : "(off)"));
        return l;
    }

    String status() {
        return status;
    }
}
