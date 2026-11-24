package fr.cobblepulse.ranch.net;

import com.cobblemon.mod.common.api.pokemon.egg.EggGroup;
import com.cobblemon.mod.common.api.storage.pc.PCBox;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import fr.cobblepulse.ranch.breeding.Compatibility;
import fr.cobblepulse.ranch.breeding.EggData;
import fr.cobblepulse.ranch.breeding.Genetics;
import fr.cobblepulse.ranch.breeding.Habitat;
import fr.cobblepulse.ranch.breeding.Traits;
import fr.cobblepulse.ranch.config.ConfigManager;
import fr.cobblepulse.ranch.config.HabitatConfig;
import fr.cobblepulse.ranch.config.RanchConfig;
import fr.cobblepulse.ranch.data.PlayerStore;
import fr.cobblepulse.ranch.data.RanchData;
import fr.cobblepulse.ranch.data.RanchStore;
import fr.cobblepulse.ranch.data.SlotData;
import fr.cobblepulse.ranch.perm.Perms;
import fr.cobblepulse.ranch.registry.ModContent;
import fr.cobblepulse.ranch.service.RanchService;
import fr.cobblepulse.ranch.service.Stores;
import fr.cobblepulse.ranch.util.Names;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Donnees envoyees aux ecrans (JSON). Le client n'affiche que ce que le serveur lui donne. */
public final class Views {
    private Views() {}

    public static JsonObject pokemon(Pokemon p, UUID viewer) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", p.getUuid().toString());
        o.addProperty("species", p.getSpecies().getResourceIdentifier().toString());
        o.addProperty("species_key", Names.speciesKey(p.getSpecies()));
        o.addProperty("species_name", Names.species(p.getSpecies()));
        String nick = p.getNickname() == null ? "" : p.getNickname().getString();
        o.addProperty("nickname", nick);
        o.addProperty("form", Names.form(p.getForm()));
        o.addProperty("gender", p.getGender() == null ? "genderless" : p.getGender().name().toLowerCase(Locale.ROOT));
        o.addProperty("level", p.getLevel());
        o.addProperty("shiny", p.getShiny());
        ItemStack held = p.heldItem();
        o.addProperty("held", held.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
        o.addProperty("held_name", Names.item(held));
        o.addProperty("held_effect", Traits.breedingItemEffect(p));
        JsonArray asp = new JsonArray();
        for (String a : p.getAspects()) asp.add(a);
        o.add("aspects", asp);
        JsonArray g = new JsonArray();
        for (EggGroup e : Traits.eggGroups(p)) g.add(Names.eggGroup(e.name()));
        o.add("groups", g);
        o.addProperty("fertility", Traits.fertility(p));
        o.addProperty("fertility_max", Traits.maxFertility());
        o.addProperty("neutered", Traits.neutered(p));
        o.addProperty("ditto", Traits.isDitto(p));
        o.addProperty("is_ot", viewer != null && Traits.isOriginalTrainer(p, viewer));
        o.addProperty("ability", Names.ability(p.getAbility().getTemplate()) + (Genetics.isHidden(p) ? " (caché)" : ""));
        o.addProperty("nature", Names.nature(p.getNature()));
        int tot = 0;
        for (int i = 0; i < 6; i++) tot += p.getIvs().getOrDefault(Genetics.STATS[i]);
        o.addProperty("iv_total", tot);
        return o;
    }

    public static JsonObject eggInfo(CompoundTag egg) {
        CompoundTag i = EggData.info(egg);
        JsonObject o = new JsonObject();
        o.addProperty("species_key", i.getString("species_key"));
        o.addProperty("species_name", i.getString("species_name"));
        o.addProperty("form", i.getString("form"));
        o.addProperty("shiny", i.getBoolean("shiny"));
        o.addProperty("color", i.getInt("color"));
        return o;
    }

    public static JsonObject ranch(ServerPlayer p, RanchData r, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("id", r.id.toString());
        o.addProperty("owner", r.ownerName);
        o.addProperty("remote", r.remote);
        o.addProperty("orphan", r.orphan);
        o.addProperty("where", r.where());
        o.addProperty("now", System.currentTimeMillis());
        o.addProperty("silver", RanchService.count(p, ModContent.SILVER_HOURGLASS));
        o.addProperty("gold", RanchService.count(p, ModContent.GOLD_HOURGLASS));
        o.addProperty("perm_silver", Perms.has(p, Perms.HOURGLASS_SILVER));
        o.addProperty("perm_gold", Perms.has(p, Perms.HOURGLASS_GOLD));
        o.addProperty("optimal", ConfigManager.get().environment_5x5.optimal_points_required);
        o.addProperty("egg_cap", ConfigManager.get().general_settings.max_eggs_per_slot);
        o.addProperty("fertility_on", Traits.fertilityEnabled());
        if (message != null) o.addProperty("message", message);
        JsonArray slots = new JsonArray();
        for (int i = 0; i < r.slots.size(); i++) slots.add(slot(r, i, p.getUUID()));
        o.add("slots", slots);
        return o;
    }

    static JsonObject slot(RanchData r, int i, UUID viewer) {
        SlotData s = r.slots.get(i);
        JsonObject o = new JsonObject();
        o.addProperty("index", i);
        Pokemon a = s.pokemon(true), b = s.pokemon(false);
        if (a != null) o.add("a", pokemon(a, viewer));
        if (b != null) o.add("b", pokemon(b, viewer));
        o.addProperty("start", s.start);
        o.addProperty("duration", s.duration);
        o.addProperty("pause", s.pause);
        JsonArray eggs = new JsonArray();
        for (CompoundTag e : s.eggs) eggs.add(eggInfo(e));
        o.add("eggs", eggs);
        o.addProperty("eggs_laid", s.eggsLaid);
        int fert = -1;
        for (Pokemon p : new Pokemon[]{a, b}) if (p != null) fert = fert < 0 ? Traits.fertility(p) : Math.min(fert, Traits.fertility(p));
        o.addProperty("fertility", fert);
        o.addProperty("score", s.score);
        o.addProperty("rating", r.remote ? "À DISTANCE" : Habitat.rating(s.score));
        o.addProperty("group", s.group.isEmpty() ? "" : Names.eggGroup(s.group));
        JsonArray lines = new JsonArray();
        ListTag l = s.habitat.getList("lines", Tag.TAG_COMPOUND);
        for (int k = 0; k < l.size(); k++) {
            CompoundTag e = l.getCompound(k);
            JsonObject x = new JsonObject();
            x.addProperty("block", e.getString("block"));
            x.addProperty("count", e.getInt("count"));
            x.addProperty("points", e.getInt("points"));
            x.addProperty("max", e.getInt("max"));
            x.addProperty("total", e.getInt("total"));
            lines.add(x);
        }
        o.add("habitat", lines);
        o.addProperty("notes", s.habitat.getString("last_notes"));
        if (a != null && b != null) {
            Compatibility.Check c = Compatibility.check(a, b, r.ownerBypassFertility);
            if (c.ok()) {
                try {
                    var base = Genetics.baseOffspring(c.mother(), c.father(), new java.util.Random(0));
                    o.addProperty("offspring", Names.species(base.getSpecies()));
                } catch (Throwable ignored) {
                }
            }
        }
        return o;
    }

    /** Liste de choix d'un parent : PC (pension posee) ou equipe (pension a distance), compatibilite calculee. */
    public static JsonObject picker(ServerPlayer p, RanchData r, int slot, boolean first) {
        JsonObject o = new JsonObject();
        o.addProperty("ranch", r.id.toString());
        o.addProperty("slot", slot);
        o.addProperty("first", first);
        o.addProperty("source", r.remote ? "party" : "pc");
        SlotData s = r.slot(slot);
        Pokemon other = s == null ? null : s.pokemon(!first);
        if (other != null) o.add("other", pokemon(other, p.getUUID()));
        boolean bypass = Perms.has(p, Perms.BYPASS_FERTILITY);
        o.addProperty("fertility_on", Traits.fertilityEnabled());
        JsonArray list = new JsonArray();
        if (r.remote) {
            int i = 0;
            for (Pokemon pk : Stores.party(p).toGappyList()) {
                if (pk != null) list.add(entry(p, pk, other, first, bypass, -1, i));
                i++;
            }
        } else {
            List<PCBox> boxes = Stores.pc(p).getBoxes();
            for (int bi = 0; bi < boxes.size(); bi++) {
                PCBox box = boxes.get(bi);
                for (int k = 0; k < 30; k++) {
                    Pokemon pk = box.get(k);
                    if (pk != null) list.add(entry(p, pk, other, first, bypass, bi, k));
                }
            }
        }
        o.add("entries", list);
        return o;
    }

    private static JsonObject entry(ServerPlayer p, Pokemon pk, Pokemon other, boolean first, boolean bypass, int box, int pos) {
        JsonObject e = pokemon(pk, p.getUUID());
        e.addProperty("box", box);
        e.addProperty("pos", pos);
        String reason = Compatibility.blockReason(pk, bypass);
        if (reason.isEmpty() && other != null) {
            Compatibility.Check c = Compatibility.check(first ? pk : other, first ? other : pk, bypass);
            if (!c.ok()) reason = c.reason();
        }
        e.addProperty("ok", reason.isEmpty());
        e.addProperty("reason", reason);
        return e;
    }

    public static JsonObject guide() {
        RanchConfig c = ConfigManager.get();
        JsonObject o = new JsonObject();
        o.addProperty("base", c.environment_5x5.base_breed_duration_seconds);
        o.addProperty("min", c.environment_5x5.min_breed_duration_seconds);
        o.addProperty("optimal", c.environment_5x5.optimal_points_required);
        o.addProperty("fertility_on", c.general_settings.enable_fertility_system);
        o.addProperty("fertility", c.general_settings.default_fertility_uses);
        o.addProperty("knot", c.genetics.destiny_knot_ivs);
        o.addProperty("ivs", c.genetics.default_inherited_ivs);
        o.addProperty("mother_ability", c.genetics.mother_ability_inheritance_chance);
        o.addProperty("shiny", c.genetics.base_shiny_rate);
        o.addProperty("masuda", c.genetics.masuda_shiny_multiplier);
        o.addProperty("regional", c.genetics.regional_form_mother_chance);
        o.addProperty("steps", c.hatching_and_items.steps_per_egg_cycle);
        o.addProperty("flame", c.hatching_and_items.flame_body_hatch_multiplier);
        o.addProperty("silver_mode", c.hatching_and_items.silver_hourglass_reduction_mode);
        o.addProperty("silver_value", c.hatching_and_items.silver_hourglass_value);
        o.addProperty("ditto_ditto", Compatibility.dittoXDittoAllowed());
        JsonArray groups = new JsonArray();
        for (Map.Entry<String, List<HabitatConfig.Entry>> e : ConfigManager.habitat().entrySet()) {
            JsonObject g = new JsonObject();
            g.addProperty("id", e.getKey());
            g.addProperty("name", Names.eggGroup(e.getKey()));
            JsonArray en = new JsonArray();
            int sum = 0;
            for (HabitatConfig.Entry x : e.getValue()) {
                JsonObject j = new JsonObject();
                j.addProperty("block", x.block);
                j.addProperty("points", x.points);
                j.addProperty("max", x.max_points);
                sum += x.max_points;
                en.add(j);
            }
            g.add("entries", en);
            g.addProperty("max_total", sum);
            groups.add(g);
        }
        o.add("groups", groups);
        return o;
    }

    /** Recapitulatif /daycare : toutes les pensions du joueur. */
    public static JsonObject daycare(ServerPlayer p) {
        JsonObject o = new JsonObject();
        o.addProperty("now", System.currentTimeMillis());
        boolean remote = Perms.has(p, Perms.CMD_DAYCARE_REMOTE);
        o.addProperty("can_remote", remote);
        o.addProperty("remote_cooldown", Perms.has(p, Perms.BYPASS_COOLDOWN) ? 0 : PlayerStore.remaining(p.getUUID(), "daycare_remote"));
        JsonArray list = new JsonArray();
        for (RanchData r : RanchStore.byOwner(p.getUUID())) {
            RanchService.refresh(r, false);
            JsonObject x = new JsonObject();
            x.addProperty("id", r.id.toString());
            x.addProperty("where", r.where());
            x.addProperty("remote", r.remote);
            x.addProperty("orphan", r.orphan);
            JsonArray slots = new JsonArray();
            for (int i = 0; i < r.slots.size(); i++) slots.add(slot(r, i, p.getUUID()));
            x.add("slots", slots);
            list.add(x);
        }
        o.add("ranches", list);
        PlayerStore.Rec rec = PlayerStore.peek(p.getUUID());
        o.addProperty("mail", rec == null ? 0 : rec.pokemon.size() + rec.eggs.size() + rec.items.size());
        return o;
    }
}
