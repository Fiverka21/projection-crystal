package item.structures;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.joml.Vector3f;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.world.level.Level;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

final class ProjectionManager {
    private static final long LIFETIME_TICKS = 5 * 60 * 20L;
    private static final long CHARGE_TICKS = 8 * 20L;
    private static final Map<UUID, ActiveProjection> ACTIVE = new HashMap<>();
    private static final Map<UUID, PendingProjection> CHARGING = new HashMap<>();
    private static final DustParticleOptions CHARGE_PARTICLE = new DustParticleOptions(new Vector3f(0.35f, 0.8f, 1.0f), 1.0f);

    private ProjectionManager() {}

    static void toggle(ServerPlayer player, ListTag entries) {
        if (ACTIVE.containsKey(player.getUUID())) {
            remove(player);
            return;
        }
        if (CHARGING.remove(player.getUUID()) != null) {
            stopChargingSound(player);
            return;
        }
        if (entries.size() > 65_536) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "item.itemstructures.projection_crystal.too_large"), true);
            return;
        }

        BlockPos center = player.blockPosition().below();
        ListTag storedEntries = new ListTag();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i).copy();
            storedEntries.add(entry);
            minX = Math.min(minX, entry.getInt("x"));
            minY = Math.min(minY, entry.getInt("y"));
            minZ = Math.min(minZ, entry.getInt("z"));
            maxX = Math.max(maxX, entry.getInt("x"));
            maxY = Math.max(maxY, entry.getInt("y"));
            maxZ = Math.max(maxZ, entry.getInt("z"));
        }
        PendingProjection pending = new PendingProjection(player, storedEntries, center,
                minX, minY, minZ, maxX, maxY, maxZ, player.level().getGameTime() + CHARGE_TICKS);
        CHARGING.put(player.getUUID(), pending);
        player.level().playSound(null, center, ItemStructures.BELLS.get(), SoundSource.PLAYERS, 1.0f, 1.0f);
    }

    private static void spawn(PendingProjection pending) {
        Map<BlockPos, OriginalBlock> placed = new HashMap<>();
        for (int i = 0; i < pending.entries.size(); i++) {
            CompoundTag entry = pending.entries.getCompound(i);
            BlockPos pos = pending.center.offset(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"));
            BlockState state = NbtUtils.readBlockState(pending.player.level().holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                    entry.getCompound("state"));
            placed.put(pos, new OriginalBlock(pending.player.level().getBlockState(pos), saveBlockEntity(pending.player, pos)));
            pending.player.level().setBlock(pos, state, Block.UPDATE_CLIENTS);
            if (entry.contains("block_entity")) {
                BlockEntity blockEntity = pending.player.level().getBlockEntity(pos);
                if (blockEntity != null) {
                    blockEntity.loadWithComponents(entry.getCompound("block_entity"), pending.player.level().registryAccess());
                    blockEntity.setChanged();
                }
            }
        }
        ACTIVE.put(pending.player.getUUID(), new ActiveProjection(pending.player, placed,
                pending.player.level().getGameTime() + LIFETIME_TICKS));
    }

    static boolean isProjected(ServerPlayer player, BlockPos pos) {
        ActiveProjection projection = ACTIVE.get(player.getUUID());
        return projection != null && projection.blocks.containsKey(pos);
    }

    static boolean isProjected(Level level, BlockPos pos) {
        return ACTIVE.values().stream().anyMatch(projection ->
                projection.player.level() == level && projection.blocks.containsKey(pos));
    }

    static void destroyProjected(ServerPlayer player, BlockPos pos) {
        ActiveProjection projection = ACTIVE.get(player.getUUID());
        if (projection == null || !projection.blocks.containsKey(pos)) return;
        restore(player, pos, projection.blocks.remove(pos));
        if (projection.blocks.isEmpty()) ACTIVE.remove(player.getUUID());
    }

    static void tick(long gameTime) {
        CHARGING.values().removeIf(pending -> {
            spawnParticles(pending);
            if (gameTime >= pending.readyAt) {
                spawn(pending);
                return true;
            }
            return false;
        });
        ACTIVE.values().removeIf(projection -> {
            if (gameTime >= projection.expiresAt) {
                projection.blocks.forEach((pos, original) -> restore(projection.player, pos, original));
                return true;
            }
            return false;
        });
    }

    private static void spawnParticles(PendingProjection pending) {
        ServerLevel level = pending.player.serverLevel();
        for (int i = 0; i < 12; i++) {
            double x = pending.center.getX() + pending.minX + level.random.nextDouble() * (pending.maxX - pending.minX + 1) + 0.5;
            double y = pending.center.getY() + pending.minY + level.random.nextDouble() * (pending.maxY - pending.minY + 1) + 0.5;
            double z = pending.center.getZ() + pending.minZ + level.random.nextDouble() * (pending.maxZ - pending.minZ + 1) + 0.5;
            level.sendParticles(CHARGE_PARTICLE, x, y, z, 1, 0.05, 0.05, 0.05, 0.01);
        }
    }

    private static void stopChargingSound(ServerPlayer player) {
        ClientboundStopSoundPacket packet = new ClientboundStopSoundPacket(
                ItemStructures.BELLS.get().getLocation(), SoundSource.PLAYERS);
        for (ServerPlayer listener : player.serverLevel().players()) {
            listener.connection.send(packet);
        }
    }

    static void remove(ServerPlayer player) {
        ActiveProjection projection = ACTIVE.remove(player.getUUID());
        if (projection != null) {
            projection.blocks.forEach((pos, original) -> restore(player, pos, original));
        }
    }

    private static void restore(ServerPlayer player, BlockPos pos, OriginalBlock original) {
        player.level().setBlock(pos, original.state, Block.UPDATE_CLIENTS);
        if (original.blockEntity != null) {
            BlockEntity blockEntity = player.level().getBlockEntity(pos);
            if (blockEntity != null) {
                blockEntity.loadWithComponents(original.blockEntity, player.level().registryAccess());
                blockEntity.setChanged();
            }
        }
    }

    private static CompoundTag saveBlockEntity(ServerPlayer player, BlockPos pos) {
        BlockEntity blockEntity = player.level().getBlockEntity(pos);
        return blockEntity == null ? null : blockEntity.saveWithFullMetadata(player.level().registryAccess());
    }

    private record OriginalBlock(BlockState state, CompoundTag blockEntity) {}
    private record PendingProjection(ServerPlayer player, ListTag entries, BlockPos center,
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ, long readyAt) {}
    private record ActiveProjection(ServerPlayer player, Map<BlockPos, OriginalBlock> blocks, long expiresAt) {}
}
