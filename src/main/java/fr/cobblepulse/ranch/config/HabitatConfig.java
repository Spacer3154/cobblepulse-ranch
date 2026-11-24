package fr.cobblepulse.ranch.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * config/cobbleranch/habitat_blocks.json : pour chaque groupe d'oeufs, les blocs qui comptent dans la zone 5x5 (couches
 * Y et Y+1), leurs points par bloc et leur plafond de contribution. "block" accepte un identifiant de bloc ou un tag
 * ("#minecraft:coral_blocks"). La cle "default" sert aux groupes sans liste.
 */
public final class HabitatConfig {
    public static final class Entry {
        public String block;
        public int points;
        public int max_points;

        public Entry() {}

        Entry(String block, int points, int max) {
            this.block = block;
            this.points = points;
            this.max_points = max;
        }
    }

    /** "water1", "water_1", "Water 1" -> "water1" ; "human_like" -> "humanlike". */
    public static String norm(String group) {
        return group == null ? "" : group.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static void put(Map<String, List<Entry>> m, String g, Object... e) {
        List<Entry> l = new ArrayList<>();
        for (int i = 0; i + 2 < e.length; i += 3) l.add(new Entry((String) e[i], (Integer) e[i + 1], (Integer) e[i + 2]));
        m.put(g, l);
    }

    public static Map<String, List<Entry>> defaults() {
        Map<String, List<Entry>> m = new LinkedHashMap<>();
        Object[] water = {"minecraft:water", 1, 15, "minecraft:lily_pad", 3, 15, "minecraft:kelp", 2, 10, "minecraft:seagrass", 2, 10,
                "#minecraft:coral_blocks", 4, 16, "minecraft:sea_lantern", 4, 12, "minecraft:prismarine", 4, 12};
        put(m, "water1", water);
        put(m, "water2", water);
        put(m, "water3", water);
        put(m, "grass", "minecraft:grass_block", 1, 12, "minecraft:moss_block", 2, 12, "minecraft:flowering_azalea", 3, 15,
                "minecraft:spore_blossom", 3, 15, "minecraft:oak_leaves", 2, 10, "minecraft:tall_grass", 2, 10);
        put(m, "dragon", "minecraft:obsidian", 3, 15, "minecraft:crying_obsidian", 3, 15, "minecraft:end_stone", 2, 12,
                "minecraft:magma_block", 3, 12, "minecraft:gold_block", 4, 12);
        put(m, "monster", "minecraft:stone", 1, 10, "minecraft:mossy_cobblestone", 2, 10, "minecraft:bone_block", 3, 12,
                "minecraft:dripstone_block", 2, 10, "minecraft:pointed_dripstone", 3, 12, "minecraft:dead_bush", 2, 8);
        put(m, "bug", "#minecraft:flowers", 2, 12, "minecraft:bee_nest", 4, 12, "minecraft:honeycomb_block", 3, 12,
                "minecraft:sweet_berry_bush", 3, 12, "#minecraft:logs", 1, 8, "minecraft:cobweb", 2, 8);
        put(m, "flying", "#minecraft:leaves", 1, 10, "minecraft:hay_block", 2, 10, "#minecraft:wool", 2, 10,
                "minecraft:scaffolding", 2, 10, "minecraft:bamboo", 2, 10, "minecraft:lightning_rod", 4, 8);
        put(m, "field", "minecraft:grass_block", 1, 12, "minecraft:hay_block", 3, 12, "#minecraft:flowers", 2, 10,
                "#minecraft:crops", 2, 10, "minecraft:composter", 4, 8, "minecraft:dirt_path", 1, 6);
        put(m, "fairy", "#minecraft:flowers", 2, 12, "minecraft:amethyst_block", 3, 12, "minecraft:amethyst_cluster", 4, 12,
                "minecraft:pink_petals", 2, 10, "minecraft:cake", 4, 8, "minecraft:glow_lichen", 2, 8);
        put(m, "humanlike", "minecraft:crafting_table", 2, 8, "minecraft:bookshelf", 2, 12, "minecraft:lectern", 4, 8,
                "#minecraft:beds", 3, 9, "minecraft:lantern", 2, 8, "minecraft:bricks", 1, 8, "minecraft:smooth_stone", 1, 8);
        put(m, "mineral", "minecraft:stone", 1, 8, "minecraft:iron_block", 4, 12, "minecraft:copper_block", 3, 9,
                "minecraft:calcite", 2, 10, "minecraft:tuff", 1, 8, "minecraft:magma_block", 2, 8, "minecraft:amethyst_block", 2, 8);
        put(m, "amorphous", "minecraft:soul_sand", 2, 10, "minecraft:soul_soil", 2, 10, "minecraft:soul_lantern", 3, 9,
                "minecraft:sculk", 2, 12, "minecraft:slime_block", 3, 12, "minecraft:honey_block", 3, 9);
        put(m, "default", "minecraft:grass_block", 1, 12, "#minecraft:flowers", 2, 12, "minecraft:water", 1, 10,
                "minecraft:hay_block", 2, 10, "#minecraft:logs", 1, 10);
        return m;
    }
}
