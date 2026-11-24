package fr.cobblepulse.ranch.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Sauvegarde immediate des donnees d'un seul joueur (inventaire) pendant une transaction d'oeuf. */
@Mixin(PlayerList.class)
public interface PlayerListInvoker {
    @Invoker("save")
    void cobbleranch$save(ServerPlayer player);
}
