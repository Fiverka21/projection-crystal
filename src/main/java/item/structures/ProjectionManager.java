package item.structures;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

final class ProjectionManager {
    private static final long LIFETIME_TICKS = 5 * 60 * 20L;
    private static final Map<UUID, ActiveProjection> ACTIVE = new HashMap<>();

    private ProjectionManager() {}

    static void toggle(ServerPlayer player, ListTag entries) {
        if (ACTIVE.containsKey(player.getUUID())) {
            remove(player);
            return;
        }

        Map<BlockPos, OriginalBlock> placed = new HashMap<>();
        BlockPos center = player.blockPosition().below();
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            BlockPos pos = center.offset(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"));
            BlockState state = NbtUtils.readBlockState(player.level().holderLookup(net.minecraft.core.registries.Registries.BLOCK),
                    entry.getCompound("state"));
            placed.put(pos, new OriginalBlock(player.level().getBlockState(pos), saveBlockEntity(player, pos)));
            player.level().setBlock(pos, state, 3);
            if (entry.contains("block_entity")) {
                BlockEntity blockEntity = player.level().getBlockEntity(pos);
                if (blockEntity != null) {
                    blockEntity.loadWithComponents(entry.getCompound("block_entity"), player.level().registryAccess());
                    blockEntity.setChanged();
                }
            }
        }
        ACTIVE.put(player.getUUID(), new ActiveProjection(player, placed, player.level().getGameTime() + LIFETIME_TICKS));
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "item.itemstructures.projection_crystal.projected"), true);
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
        ACTIVE.values().removeIf(projection -> {
            if (gameTime >= projection.expiresAt) {
                projection.blocks.forEach((pos, original) -> restore(projection.player, pos, original));
                return true;
            }
            return false;
        });
    }

    static void remove(ServerPlayer player) {
        ActiveProjection projection = ACTIVE.remove(player.getUUID());
        if (projection != null) {
            projection.blocks.forEach((pos, original) -> restore(player, pos, original));
        }
    }

    private static void restore(ServerPlayer player, BlockPos pos, OriginalBlock original) {
        player.level().setBlock(pos, original.state, 3);
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
    private record ActiveProjection(ServerPlayer player, Map<BlockPos, OriginalBlock> blocks, long expiresAt) {}
}
