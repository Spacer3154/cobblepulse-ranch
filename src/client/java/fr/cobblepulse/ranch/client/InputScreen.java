package fr.cobblepulse.ranch.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** Saisie d'un nombre par-dessus un ecran (Annuler y revient). */
public final class InputScreen extends RanchUi {
    private final Screen back;
    private final String hint;
    private final Consumer<Long> done;
    private EditBox box;
    private String error = "";

    public InputScreen(Screen back, String title, String hint, Consumer<Long> done) {
        super(title);
        this.back = back;
        this.hint = hint;
        this.done = done;
    }

    @Override
    protected void init() {
        layout(260, 90);
        box = new EditBox(font, px + 10, py + 30, pw - 20, 16, Component.literal(hint));
        box.setFilter(s -> s.matches("\\d{0,6}"));
        addRenderableWidget(box);
        setInitialFocus(box);
    }

    void submit() {
        if (box.getValue().isEmpty()) {
            error = "Entre un nombre";
            return;
        }
        long v = Long.parseLong(box.getValue());
        minecraft.setScreen(back);
        done.accept(v);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 257 || key == 335) {
            submit();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    protected void draw(GuiGraphics g, int mx, int my, float pt) {
        panel(g, px, py, pw, ph, PANEL, ACCENT);
        g.drawString(font, getTitle().getString(), px + 10, py + 8, GOLD, false);
        g.drawString(font, hint, px + 10, py + 19, MUTED, false);
        if (!error.isEmpty()) g.drawString(font, error, px + 10, py + 50, ERROR, false);
        btn(g, "ok", px + pw - 170, py + ph - 22, 78, 16, "Valider", true, mx, my, this::submit);
        btn(g, "cancel", px + pw - 88, py + ph - 22, 78, 16, "Annuler", true, mx, my, () -> minecraft.setScreen(back));
    }

    void setValue(String s) {
        box.setValue(s);
    }
}
