package fr.cobblepulse.ranch.client;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Guide : mecanique de la reproduction (valeurs de la config du serveur) + blocs d'habitat et leurs points par groupe. */
public final class GuideScreen extends RanchUi {
    private final JsonObject data;
    private final Screen back;
    private int tab = -1;
    private double scroll;

    public GuideScreen(JsonObject o, Screen back) {
        super("Guide de la Pension");
        this.data = o;
        this.back = back;
    }

    @Override
    protected void init() {
        layout(520, 300);
    }

    void tab(int t) {
        tab = t;
        scroll = 0;
    }

    @Override
    protected void draw(GuiGraphics g, int mx, int my, float pt) {
        panel(g, px, py, pw, ph, PANEL, ACCENT);
        g.drawString(font, "GUIDE DE LA PENSION", px + 8, py + 7, GOLD, false);
        List<JsonObject> groups = arr(data, "groups");
        int lx = px + 6, ly = py + 20, lw = 110;
        btn(g, "tab:mechanics", lx, ly, lw, 14, "Mécanique", tab != -1, mx, my, () -> tab(-1));
        for (int i = 0; i < groups.size(); i++) {
            final int k = i;
            int y = ly + 16 + i * 15;
            if (y > py + ph - 40) break;
            btn(g, "tab:" + str(groups.get(i), "id"), lx, y, lw, 14, str(groups.get(i), "name"), tab != i, mx, my, () -> tab(k));
        }
        int cx = lx + lw + 8, cy = py + 20, cw = pw - lw - 22, chh = ph - 48;
        panel(g, cx - 2, cy - 2, cw + 4, chh + 4, CARD, BORDER);
        g.enableScissor(cx, cy, cx + cw, cy + chh);
        int y = cy + 2 - (int) scroll;
        int endY = tab < 0 ? mechanics(g, cx + 2, y, cw - 8) : group(g, groups.get(tab), cx + 2, y, cw - 8, mx, my);
        g.disableScissor();
        int content = endY - (cy + 2 - (int) scroll);
        double max = Math.max(0, content - chh + 6);
        scroll = Math.max(0, Math.min(scroll, max));
        btn(g, "back", px + pw - 86, py + ph - 22, 78, 16, back != null ? "Retour" : "Fermer", true, mx, my, () -> minecraft.setScreen(back));
    }

    private int mechanics(GuiGraphics g, int x, int y, int w) {
        String pct = "%";
        List<String[]> p = new ArrayList<>();
        p.add(new String[]{"Le Bloc de Pension", "Pose le bloc, clic droit : 3 emplacements indépendants. Dans chacun, choisis deux Pokémon de ton PC "
                + "(pas de ton équipe) ; tant qu'ils sont en Pension, ils ne sont plus dans le PC. Tu peux les changer à tout moment ; sinon la "
                + "reproduction continue toute seule, même si tu es loin ou déconnecté."});
        p.add(new String[]{"Compatibilité", "Un mâle et une femelle qui partagent un groupe d'œufs, ou n'importe quel Pokémon avec Métamorph (sauf le "
                + "groupe Inconnu : légendaires, fabuleux, bébés, paradoxes). Métamorph x Métamorph : " + (bool(data, "ditto_ditto") ? "autorisé en ce moment." : "interdit.")
                + " Un Pokémon castré ne se reproduit plus jamais (/castrer, réservé au Dresseur d'Origine)."});
        if (bool(data, "fertility_on")) p.add(new String[]{"Fertilité", "Chaque Pokémon peut donner " + num(data, "fertility") + " œufs. Chaque ponte retire 1 "
                + "point aux deux parents ; à 0, il est stérile. La fertilité suit le Pokémon (PC, échanges, GTS)."});
        p.add(new String[]{"Durée et habitat 5x5", "Un cycle dure de " + time(num(data, "base") * 1000L) + " (0 point) à " + time(num(data, "min") * 1000L)
                + " (" + num(data, "optimal") + " points). Les blocs comptent sur 5x5 autour de la Pension, au niveau du bloc et juste au-dessus. "
                + "Chaque bloc rapporte des points jusqu'à un plafond : il faut mélanger plusieurs éléments. Barème dans les onglets de gauche, "
                + "selon le groupe d'œufs du couple (Métamorph prend celui de son partenaire)."});
        p.add(new String[]{"L'œuf", "Première forme de la lignée de la mère (ou du parent non-Métamorph). Bébé (Azurill, Okéoké...) seulement si un parent "
                + "tient l'encens correspondant, sinon le stade suivant. Toute la génétique est fixée à la ponte : la fiche complète est visible "
                + "dans l'infobulle (Maj), avec /eggsee et au GTS."});
        p.add(new String[]{"IV", num(data, "ivs") + " IV hérités des parents (" + num(data, "knot") + " avec un Nœud Destin), le reste au hasard. "
                + "Objet Pouvoir : sa statistique est toujours transmise (deux objets : 50/50)."});
        p.add(new String[]{"Nature et forme", "Pierre Stase : nature du porteur garantie (deux porteurs : 50/50) et sa forme régionale aussi. Sans Pierre Stase : "
                + "deux parents de la même forme régionale donnent cette forme ; sinon " + Math.round(data.get("regional").getAsDouble() * 100) + " " + pct
                + " de chances d'avoir la forme de la mère."});
        p.add(new String[]{"Talent", Math.round(data.get("mother_ability").getAsDouble() * 100) + " " + pct + " de chances d'avoir le talent exact de la mère "
                + "(talent caché compris) ; sinon un talent normal. Talent caché impossible si la mère ne l'a pas."});
        p.add(new String[]{"Ball, capacités, chromatique", "Ball de la mère (Master Ball et Mémoire Ball deviennent une Poké Ball). Capacités œuf connues "
                + "d'un parent transmises. Herbe Miroir : le porteur apprend les capacités œuf que connaît son partenaire. Chromatique : 1/"
                + num(data, "shiny") + ", ×" + data.get("masuda").getAsDouble() + " si les Dresseurs d'Origine sont de langues différentes (Masuda)."});
        p.add(new String[]{"Éclosion", "L'œuf éclot en marchant (pas = blocs parcourus). Corps Ardent, Armumagma ou Turbine dans l'équipe : ×"
                + data.get("flame").getAsDouble() + ". Sablier d'Argent : " + ("FIXED".equalsIgnoreCase(str(data, "silver_mode"))
                ? "-" + data.get("silver_value").getAsDouble() + " min de ponte" : "-" + data.get("silver_value").getAsDouble() + " " + pct + " du temps restant")
                + " ; Sablier d'Or : immédiat. Clic droit avec le sablier sur l'œuf dans l'inventaire, ou bouton dans la Pension."});
        p.add(new String[]{"Commandes", "/daycare : tes Pensions. /breed <emplacement> <emplacement> : compatibilité de deux Pokémon de l'équipe. "
                + "/hatch et /eggsee : l'œuf tenu en main. /castrer <emplacement>."});
        for (String[] s : p) {
            g.drawString(font, s[0], x, y, ACCENT, false);
            y = wrap(g, s[1], x, y + 11, w, TEXT) + 6;
        }
        return y;
    }

    private int group(GuiGraphics g, JsonObject grp, int x, int y, int w, int mx, int my) {
        g.drawString(font, "Groupe " + str(grp, "name") + " — objectif " + num(data, "optimal") + " points (total possible " + num(grp, "max_total") + ")", x, y, GOLD, false);
        y += 14;
        g.drawString(font, "Élément", x + 22, y, MUTED, false);
        g.drawString(font, "Points / bloc", x + w - 150, y, MUTED, false);
        g.drawString(font, "Plafond", x + w - 60, y, MUTED, false);
        y += 12;
        for (JsonObject e : arr(grp, "entries")) {
            g.renderItem(icon(str(e, "block")), x, y - 4);
            g.drawString(font, font.plainSubstrByWidth(RanchScreen.blockName(str(e, "block")), w - 180), x + 22, y, TEXT, false);
            g.drawString(font, "+" + num(e, "points"), x + w - 150, y, OK, false);
            g.drawString(font, num(e, "max") + " pts", x + w - 60, y, WARN, false);
            y += 18;
        }
        y = wrap(g, "Un seul type de bloc ne suffit jamais : chaque élément est plafonné. Mélange-les autour de la Pension (5x5, niveau du bloc et au-dessus).",
                x, y + 4, w, MUTED);
        return y;
    }

    static ItemStack icon(String id) {
        try {
            if (id.startsWith("#")) {
                ResourceLocation rl = ResourceLocation.tryParse(id.substring(1));
                if (rl != null) {
                    var tag = BuiltInRegistries.BLOCK.getTag(TagKey.create(Registries.BLOCK, rl));
                    if (tag.isPresent()) for (Holder<Block> h : tag.get()) {
                        ItemStack st = new ItemStack(h.value().asItem());
                        if (!st.isEmpty()) return st;
                    }
                }
                return new ItemStack(Items.BARRIER);
            }
            ResourceLocation rl = ResourceLocation.tryParse(id.toLowerCase(Locale.ROOT));
            if (rl == null) return ItemStack.EMPTY;
            if (rl.getPath().equals("water")) return new ItemStack(Items.WATER_BUCKET);
            if (rl.getPath().equals("lava")) return new ItemStack(Items.LAVA_BUCKET);
            Block b = BuiltInRegistries.BLOCK.get(rl);
            ItemStack st = new ItemStack(b.asItem());
            if (st.isEmpty() && BuiltInRegistries.ITEM.containsKey(rl)) st = new ItemStack(BuiltInRegistries.ITEM.get(rl));
            return st;
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        scroll -= dy * 12;
        return true;
    }
}
