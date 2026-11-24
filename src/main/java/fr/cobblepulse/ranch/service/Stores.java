package fr.cobblepulse.ranch.service;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.PokemonStore;
import com.cobblemon.mod.common.api.storage.factory.FileBackedPokemonStoreFactory;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.api.storage.pc.PCStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import fr.cobblepulse.ranch.CobbleRanch;
import fr.cobblepulse.ranch.mixin.PlayerListInvoker;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Acces aux PC / equipes Cobblemon (joueurs en ligne ou non) et enregistrement immediat sur le disque. */
public final class Stores {
    private static List<Object> factories;
    private static boolean warned;

    private Stores() {}

    public static PCStore pc(UUID owner) {
        return Cobblemon.INSTANCE.getStorage().getPC(owner, CobbleRanch.registries());
    }

    public static PlayerPartyStore party(UUID owner) {
        return Cobblemon.INSTANCE.getStorage().getParty(owner, CobbleRanch.registries());
    }

    public static PlayerPartyStore party(ServerPlayer p) {
        return Cobblemon.INSTANCE.getStorage().getParty(p);
    }

    public static PCStore pc(ServerPlayer p) {
        return Cobblemon.INSTANCE.getStorage().getPC(p);
    }

    /** Le Pokemon est-il dans l'equipe ou le PC du joueur ? */
    public static boolean owns(UUID owner, UUID pokemon) {
        return party(owner).get(pokemon) != null || pc(owner).get(pokemon) != null;
    }

    /** Ajoute au PC (ou a l'equipe si demande) ; false si plein. */
    public static boolean give(UUID owner, Pokemon pk, boolean partyFirst) {
        if (partyFirst) {
            PlayerPartyStore party = party(owner);
            if (party.getFirstAvailablePosition() != null && party.add(pk)) return true;
        }
        PCStore pc = pc(owner);
        return pc.getFirstAvailablePosition() != null && pc.add(pk);
    }

    /** Enregistre tout de suite un stockage Cobblemon (sinon : sauvegarde automatique de Cobblemon, 30 s). */
    @SuppressWarnings("unchecked")
    public static void flush(PokemonStore<?> store) {
        if (store == null) return;
        try {
            if (factories == null) {
                Field f = com.cobblemon.mod.common.api.storage.PokemonStoreManager.class.getDeclaredField("factories");
                f.setAccessible(true);
                Object list = f.get(Cobblemon.INSTANCE.getStorage());
                List<Object> out = new ArrayList<>();
                if (list instanceof Iterable<?> it) for (Object o : it) out.add(o);
                factories = out;
            }
            for (Object o : factories) {
                if (o instanceof FileBackedPokemonStoreFactory<?> fb && fb.isCached(store)) {
                    fb.save(store, CobbleRanch.registries());
                    return;
                }
            }
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                CobbleRanch.LOG.warn("[CobblePulse-Ranch] Enregistrement immediat Cobblemon indisponible ({}), sauvegarde automatique utilisee", t.toString());
            }
            factories = new ArrayList<>();
        }
    }

    public static void flushOwner(UUID owner) {
        flush(party(owner));
        flush(pc(owner));
    }

    /** Enregistre tout de suite l'inventaire du joueur (transactions d'oeufs). */
    public static void savePlayer(ServerPlayer p) {
        try {
            ((PlayerListInvoker) p.server.getPlayerList()).cobbleranch$save(p);
        } catch (Throwable t) {
            CobbleRanch.LOG.warn("[CobblePulse-Ranch] Enregistrement du joueur {} impossible : {}", p.getGameProfile().getName(), t.toString());
        }
    }
}
