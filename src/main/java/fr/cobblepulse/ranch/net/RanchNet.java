package fr.cobblepulse.ranch.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fr.cobblepulse.ranch.CobbleRanch;
import fr.cobblepulse.ranch.config.ConfigManager;
import fr.cobblepulse.ranch.data.PlayerStore;
import fr.cobblepulse.ranch.data.RanchData;
import fr.cobblepulse.ranch.data.RanchStore;
import fr.cobblepulse.ranch.perm.Perms;
import fr.cobblepulse.ranch.service.AdminService;
import fr.cobblepulse.ranch.service.RanchService;
import fr.cobblepulse.ranch.util.Msg;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Deux paquets generiques (action + JSON). Le client emet des intentions ; le serveur re-verifie tout. */
public final class RanchNet {
    public static final Gson GSON = new Gson();
    private static final StreamCodec<ByteBuf, String> BIG = ByteBufCodecs.stringUtf8(1 << 22);
    private static final Map<UUID, Long> LAST = new HashMap<>();

    public record Action(String action, String json) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(CobbleRanch.id("action"));
        public static final StreamCodec<ByteBuf, Action> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Action::action, BIG, Action::json, Action::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Data(String kind, String json) implements CustomPacketPayload {
        public static final Type<Data> TYPE = new Type<>(CobbleRanch.id("data"));
        public static final StreamCodec<ByteBuf, Data> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Data::kind, BIG, Data::json, Data::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private RanchNet() {}

    public static void registerTypes() {
        PayloadTypeRegistry.playC2S().register(Action.TYPE, Action.CODEC);
        PayloadTypeRegistry.playS2C().register(Data.TYPE, Data.CODEC);
    }

    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(Action.TYPE, (payload, ctx) -> {
            ServerPlayer p = ctx.player();
            ctx.server().execute(() -> handle(p, payload.action(), payload.json()));
        });
    }

    public static boolean hasClient(ServerPlayer p) {
        try {
            return p.connection != null && ServerPlayNetworking.canSend(p, Data.TYPE);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void send(ServerPlayer p, String kind, JsonObject data) {
        if (!hasClient(p)) {
            Msg.err(p, "Il faut le mod CobblePulse Ranch côté client pour ouvrir cet écran.");
            return;
        }
        ServerPlayNetworking.send(p, new Data(kind, GSON.toJson(data)));
    }

    public static void forget(UUID u) {
        LAST.remove(u);
    }

    private static UUID uuid(JsonObject o, String k) {
        try {
            return o.has(k) ? UUID.fromString(o.get(k).getAsString()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void handle(ServerPlayer p, String action, String json) {
        JsonObject o;
        try {
            o = json == null || json.isBlank() ? new JsonObject() : JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST.put(p.getUUID(), now);
        if (last != null && now - last < 120 && !action.equals("ranch_refresh")) return;
        try {
            switch (action) {
                case "ranch_refresh", "ranch_pick", "ranch_deposit", "ranch_withdraw", "ranch_collect", "ranch_hourglass" -> ranch(p, action, o);
                case "guide" -> send(p, "guide", Views.guide());
                case "daycare" -> {
                    if (Perms.has(p, Perms.CMD_DAYCARE)) send(p, "daycare", Views.daycare(p));
                }
                case "daycare_open" -> daycareOpen(p, uuid(o, "ranch"));
                case "admin" -> AdminService.action(p, o);
                default -> {
                }
            }
        } catch (Throwable t) {
            CobbleRanch.LOG.error("[CobblePulse-Ranch] Erreur sur l'action {} de {}", action, p.getGameProfile().getName(), t);
            Msg.err(p, "Erreur interne : action annulée (voir le journal du serveur).");
        }
    }

    private static void ranch(ServerPlayer p, String action, JsonObject o) {
        RanchData r = RanchStore.get(uuid(o, "ranch"));
        if (r == null || !p.getUUID().equals(r.owner)) return;
        if (!Perms.has(p, Perms.USE)) {
            Msg.err(p, "Tu n'as pas la permission d'utiliser les Pensions.");
            return;
        }
        if (!RanchService.canAct(p, r)) {
            send(p, "ranch", Views.ranch(p, r, "Trop loin de la Pension : rapproche-toi ou utilise /daycare."));
            return;
        }
        int slot = o.has("slot") ? o.get("slot").getAsInt() : -1;
        boolean first = !o.has("first") || o.get("first").getAsBoolean();
        String err = null;
        switch (action) {
            case "ranch_refresh" -> RanchService.refresh(r, o.has("scan") && o.get("scan").getAsBoolean());
            case "ranch_pick" -> {
                send(p, "picker", Views.picker(p, r, slot, first));
                return;
            }
            case "ranch_deposit" -> {
                UUID u = uuid(o, "uuid");
                err = u == null ? "Pokémon invalide" : RanchService.deposit(p, r.id, slot, first, u);
            }
            case "ranch_withdraw" -> err = RanchService.withdraw(p, r.id, slot, first);
            case "ranch_collect" -> err = RanchService.collect(p, r.id, slot);
            case "ranch_hourglass" -> err = RanchService.hourglass(p, r.id, slot, o.has("gold") && o.get("gold").getAsBoolean());
            default -> {
            }
        }
        send(p, "ranch", Views.ranch(p, r, err == null ? null : "✖ " + err));
    }

    /** Ouverture depuis /daycare : pension a distance, orpheline (recuperation) ou VIP (acces a distance). */
    public static void daycareOpen(ServerPlayer p, UUID id) {
        RanchData r = RanchStore.get(id);
        if (r == null || !p.getUUID().equals(r.owner)) return;
        if (!r.remote && !r.orphan && !RanchService.canAct(p, r)) {
            if (!Perms.has(p, Perms.CMD_DAYCARE_REMOTE)) {
                Msg.err(p, "Ouvrir une Pension à distance est un avantage VIP.");
                return;
            }
            boolean bypass = Perms.has(p, Perms.BYPASS_COOLDOWN);
            long left = bypass ? 0 : PlayerStore.remaining(p.getUUID(), "daycare_remote");
            if (left > 0) {
                Msg.err(p, "Accès à distance disponible dans " + Msg.duration(left) + ".");
                return;
            }
            if (!bypass) PlayerStore.startCooldown(p.getUUID(), "daycare_remote", ConfigManager.get().command_cooldowns_seconds.daycare_remote);
            RanchService.grantRemote(p, r);
        }
        RanchService.open(p, r);
    }
}
