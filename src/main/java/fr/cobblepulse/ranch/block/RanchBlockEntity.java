package fr.cobblepulse.ranch.block;

import fr.cobblepulse.ranch.data.RanchData;
import fr.cobblepulse.ranch.data.RanchStore;
import fr.cobblepulse.ranch.perm.Perms;
import fr.cobblepulse.ranch.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * Lien entre le bloc et sa pension du registre central. Les Pokemon n'y sont pas des entites : le NBT du chunk garde
 * une copie miroir complete de la pension (seconde sauvegarde en cas de fichier perdu). Pas de ticker.
 */
public final class RanchBlockEntity extends BlockEntity {
    private UUID ranchId;
    private UUID owner;
    private CompoundTag mirror;

    public RanchBlockEntity(BlockPos pos, BlockState state) {
        super(ModContent.RANCH_BE, pos, state);
    }

    public UUID ranchId() {
        return ranchId;
    }

    public UUID owner() {
        return owner;
    }

    public CompoundTag mirror() {
        return mirror;
    }

    public void link(RanchData r) {
        ranchId = r.id;
        owner = r.owner;
        mirror = null;
        setChanged();
        if (level != null && !level.isClientSide()) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    public void unlink() {
        ranchId = null;
        mirror = null;
        setChanged();
    }

    /** Casse autorisee : proprietaire, bloc sans proprietaire, ou staff (cote serveur). */
    public boolean canBreak(Player p) {
        if (owner == null || p.getUUID().equals(owner)) return true;
        if (p instanceof ServerPlayer sp) return Perms.has(sp, Perms.ADMIN_DELETE);
        return p.isCreative() && p.hasPermissions(2);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (ranchId != null) tag.putUUID("ranch_id", ranchId);
        if (owner != null) tag.putUUID("owner", owner);
        RanchData r = ranchId == null ? null : RanchStore.get(ranchId);
        CompoundTag m = r != null ? r.save() : mirror;
        if (m != null) tag.put("mirror", m);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        ranchId = tag.hasUUID("ranch_id") ? tag.getUUID("ranch_id") : null;
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        mirror = tag.contains("mirror") ? tag.getCompound("mirror") : null;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag t = new CompoundTag();
        if (owner != null) t.putUUID("owner", owner);
        return t;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
