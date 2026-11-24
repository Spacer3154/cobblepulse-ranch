package fr.cobblepulse.ranch.breeding;

import com.cobblemon.mod.common.api.pokemon.egg.EggGroup;
import fr.cobblepulse.ranch.CobbleRanch;
import fr.cobblepulse.ranch.config.ConfigManager;
import fr.cobblepulse.ranch.config.HabitatConfig;
import fr.cobblepulse.ranch.config.RanchConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Analyse de terrain 5x5 : 25 colonnes centrees sur la Pension, couches Y et Y+1 seulement (rien sous le bloc). Chaque
 * element rapporte points x nombre, plafonne a max_points (anti-monoculture). La duree d'un cycle est interpolee entre
 * base_breed_duration_seconds (0 point) et min_breed_duration_seconds (optimal_points_required atteint).
 * Lecture de 49 blocs, faite uniquement sur evenement (ouverture, depot/retrait, Rafraichir) avec un delai de 2 s.
 */
public final class Habitat {
    public record Line(String block, int count, int points, int max, int total) {}

    public record Result(String group, int score, List<Line> lines) {
        public CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("group", group);
            t.putInt("score", score);
            ListTag l = new ListTag();
            for (Line x : lines) {
                CompoundTag e = new CompoundTag();
                e.putString("block", x.block());
                e.putInt("count", x.count());
                e.putInt("points", x.points());
                e.putInt("max", x.max());
                e.putInt("total", x.total());
                l.add(e);
            }
            t.put("lines", l);
            return t;
        }
    }

    private record Matcher(Block block, TagKey<Block> tag, Fluid fluid) {
        boolean test(BlockState s) {
            if (fluid != null) {
                FluidState fs = s.getFluidState();
                return !fs.isEmpty() && fs.isSource() && fs.getType().isSame(fluid);
            }
            if (tag != null) return s.is(tag);
            return block != null && s.is(block);
        }
    }

    private static final Map<String, Matcher> CACHE = new HashMap<>();

    private Habitat() {}

    public static void clearCache() {
        CACHE.clear();
    }

    private static Matcher matcher(String id) {
        return CACHE.computeIfAbsent(id, k -> {
            if (k.startsWith("#")) {
                ResourceLocation rl = ResourceLocation.tryParse(k.substring(1));
                return rl == null ? new Matcher(null, null, null) : new Matcher(null, TagKey.create(Registries.BLOCK, rl), null);
            }
            ResourceLocation rl = ResourceLocation.tryParse(k);
            if (rl == null || !BuiltInRegistries.BLOCK.containsKey(rl)) {
                CobbleRanch.LOG.warn("[CobblePulse-Ranch] habitat_blocks.json : bloc inconnu {}", k);
                return new Matcher(null, null, null);
            }
            Block b = BuiltInRegistries.BLOCK.get(rl);
            // eau, lave, fluides de mods : sources seulement (le varech et l'herbe aquatique contiennent de l'eau, ce sont des blocs)
            if (b instanceof net.minecraft.world.level.block.LiquidBlock) {
                FluidState fs = b.defaultBlockState().getFluidState();
                if (!fs.isEmpty() && fs.getType() != Fluids.EMPTY) return new Matcher(null, null, fs.getType());
            }
            return new Matcher(b, null, null);
        });
    }

    public static List<HabitatConfig.Entry> entries(String group) {
        Map<String, List<HabitatConfig.Entry>> h = ConfigManager.habitat();
        List<HabitatConfig.Entry> l = h.get(HabitatConfig.norm(group));
        if (l == null || l.isEmpty()) l = h.getOrDefault("default", List.of());
        return l;
    }

    /** Meilleur score parmi les groupes d'oeufs du couple (Metamorph prend ceux de son partenaire). */
    public static Result scan(ServerLevel level, BlockPos center, Set<EggGroup> groups) {
        List<BlockState> states = new ArrayList<>(49);
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dy == 0 && dx == 0 && dz == 0) continue;
                    states.add(level.getBlockState(center.offset(dx, dy, dz)));
                }
            }
        }
        Result best = null;
        for (EggGroup g : groups) {
            Result r = score(Traits.groupId(g), states);
            if (best == null || r.score() > best.score()) best = r;
        }
        return best != null ? best : score("default", states);
    }

    public static Result score(String group, List<BlockState> states) {
        List<Line> lines = new ArrayList<>();
        int total = 0;
        for (HabitatConfig.Entry e : entries(group)) {
            Matcher m = matcher(e.block);
            int n = 0;
            for (BlockState s : states) if (m.test(s)) n++;
            int t = Math.min(n * e.points, e.max_points);
            total += t;
            lines.add(new Line(e.block, n, e.points, e.max_points, t));
        }
        return new Result(group, total, lines);
    }

    public static long durationMs(int score) {
        RanchConfig.Environment env = ConfigManager.get().environment_5x5;
        double ratio = Math.max(0, Math.min(1.0, score / (double) env.optimal_points_required));
        double sec = env.base_breed_duration_seconds - (env.base_breed_duration_seconds - env.min_breed_duration_seconds) * ratio;
        return Math.round(sec * 1000);
    }

    public static String rating(int score) {
        if (score < 0) return "NON ANALYSÉ";
        double r = score / (double) ConfigManager.get().environment_5x5.optimal_points_required;
        if (r >= 1.0) return "EXCELLENT";
        if (r >= 0.6) return "BON";
        if (r >= 0.3) return "MOYEN";
        return "INCOMPLET";
    }
}
