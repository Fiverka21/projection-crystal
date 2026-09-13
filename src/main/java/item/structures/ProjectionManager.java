package item.structures;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import org.joml.Vector3f;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

final class ProjectionManager {
    private static final long LIFETIME_TICKS = 5 * 60 * 20L;
    private static final long CHARGE_TICKS = 8 * 20L;
    private static final Map<UUID, ActiveProjection> ACTIVE = new HashMap<>();
    private static final Map<UUID, PendingProjection> CHARGING = new HashMap<>();
    private static final Map<UUID, Placement> PLACING = new HashMap<>();
    private static final DustParticleOptions CHARGE_PARTICLE =
            new DustParticleOptions(new Vector3f(0.35f, 0.8f, 1.0f), 1.0f);

    private ProjectionManager() {}

    static void load(ServerLevel overworld) {
        ProjectionSavedData data = overworld.getDataStorage().computeIfAbsent(
                ProjectionSavedData.factory(), ProjectionSavedData.NAME);
        ACTIVE.clear();
        for (int i = 0; i < data.projections().size(); i++) {
            CompoundTag saved = data.projections().getCompound(i);
            try {
                UUID owner = saved.getUUID("Owner");
                ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
                        ResourceLocation.parse(saved.getString("Dimension")));
                ServerLevel level = overworld.getServer().getLevel(dimension);
                if (level == null) continue;
                Map<BlockPos, OriginalBlock> blocks = new HashMap<>();
                ListTag blockList = saved.getList("Blocks", CompoundTag.TAG_COMPOUND);
                for (int j = 0; j < blockList.size(); j++) {
                    CompoundTag block = blockList.getCompound(j);
                    BlockPos pos = NbtUtils.readBlockPos(block, "Pos").orElseThrow();
                    BlockState state = NbtUtils.readBlockState(level.holderLookup(
                            net.minecraft.core.registries.Registries.BLOCK), block.getCompound("State"));
                    CompoundTag blockEntity = block.contains("BlockEntity")
                            ? block.getCompound("BlockEntity") : null;
                    blocks.put(pos, new OriginalBlock(state, blockEntity));
                }
                if (!blocks.isEmpty()) {
                    ServerPlayer player = overworld.getServer().getPlayerList().getPlayer(owner);
                    ACTIVE.put(owner, new ActiveProjection(owner, player, level, blocks,
                            saved.getLong("ExpiresAt")));
                }
            } catch (RuntimeException ignored) {
                // Ignore malformed saved projections rather than preventing the world from loading.
            }
        }
    }

    static void reconnect(ServerPlayer player) {
        ActiveProjection active = ACTIVE.get(player.getUUID());
        if (active != null) active.player = player;
    }

    static void toggle(ServerPlayer player, ListTag entries) {
        UUID playerId = player.getUUID();
        if (ACTIVE.containsKey(playerId)) {
            remove(player);
            return;
        }
        Placement placement = PLACING.get(playerId);
        if (placement != null) {
            cancelPlacement(player, playerId, placement);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "item.itemstructures.projection_crystal.charge_cancelled"), true);
            return;
        }
        if (CHARGING.remove(playerId) != null) {
            stopChargingSound(player);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "item.itemstructures.projection_crystal.charge_cancelled"), true);
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
                minX, minY, minZ, maxX, maxY, maxZ,
                player.level().getGameTime() + CHARGE_TICKS);
        CHARGING.put(playerId, pending);
        player.level().playSound(null, center, ItemStructures.BELLS.get(), SoundSource.PLAYERS, 1.0f, 1.0f);
    }

    static boolean isProjected(ServerPlayer player, BlockPos pos) {
        ActiveProjection active = ACTIVE.get(player.getUUID());
        if (active != null && active.level == player.level() && active.blocks.containsKey(pos)) return true;
        Placement placement = PLACING.get(player.getUUID());
        return placement != null && placement.level == player.level() && placement.blocks.containsKey(pos);
    }

    static boolean isProjected(Level level, BlockPos pos) {
        return ACTIVE.values().stream().anyMatch(projection ->
                projection.level == level && projection.blocks.containsKey(pos))
                || PLACING.values().stream().anyMatch(placement ->
                placement.level == level && placement.blocks.containsKey(pos));
    }

    static void destroyProjected(ServerPlayer player, BlockPos pos) {
        ActiveProjection active = ACTIVE.get(player.getUUID());
        if (active != null && active.level == player.level() && active.blocks.containsKey(pos)) {
            restore(active.level, pos, active.blocks.remove(pos));
            if (active.blocks.isEmpty()) {
                ACTIVE.remove(player.getUUID());
                save(player.serverLevel().getServer());
            } else {
                save(player.serverLevel().getServer());
            }
            return;
        }
        Placement placement = PLACING.get(player.getUUID());
        if (placement != null && placement.level == player.level() && placement.blocks.containsKey(pos)) {
            cancelPlacement(player, player.getUUID(), placement);
        }
    }

    static void tick(long gameTime) {
        Iterator<Map.Entry<UUID, PendingProjection>> charging = CHARGING.entrySet().iterator();
        while (charging.hasNext()) {
            PendingProjection pending = charging.next().getValue();
            spawnParticles(pending);
            if (gameTime >= pending.readyAt) {
                charging.remove();
                PLACING.put(pending.player.getUUID(), new Placement(pending));
            }
        }

        Iterator<Map.Entry<UUID, Placement>> placing = PLACING.entrySet().iterator();
        while (placing.hasNext()) {
            Map.Entry<UUID, Placement> entry = placing.next();
            Placement placement = entry.getValue();
            if (placeNext(placement)) {
                placing.remove();
                ACTIVE.put(entry.getKey(), new ActiveProjection(entry.getKey(), placement.player,
                        placement.level, placement.blocks,
                        placement.level.getGameTime() + LIFETIME_TICKS));
                save(placement.level.getServer());
            }
        }

        ACTIVE.values().removeIf(projection -> {
            if (gameTime >= projection.expiresAt) {
                projection.blocks.forEach((pos, original) -> restore(projection.level, pos, original));
                save(projection.level.getServer());
                return true;
            }
            return false;
        });
    }

    private static boolean placeNext(Placement placement) {
        int end = (int) Math.min(placement.entries.size(),
                placement.nextIndex + ProjectionCrystalItem.MAX_BLOCKS_PER_TICK);
        for (int i = placement.nextIndex; i < end; i++) {
            CompoundTag entry = placement.entries.getCompound(i);
            BlockPos pos = placement.center.offset(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"));
            BlockState state = NbtUtils.readBlockState(placement.level.holderLookup(
                    net.minecraft.core.registries.Registries.BLOCK), entry.getCompound("state"));
            placement.blocks.put(pos, new OriginalBlock(placement.level.getBlockState(pos),
                    saveBlockEntity(placement.level, pos)));
            placement.level.setBlock(pos, state, Block.UPDATE_CLIENTS);
            if (entry.contains("block_entity")) {
                BlockEntity blockEntity = placement.level.getBlockEntity(pos);
                if (blockEntity != null) {
                    blockEntity.loadWithComponents(entry.getCompound("block_entity"),
                            placement.level.registryAccess());
                    blockEntity.setChanged();
                }
            }
        }
        placement.nextIndex = end;
        return placement.nextIndex >= placement.entries.size();
    }

    private static void spawnParticles(PendingProjection pending) {
        ServerLevel level = pending.player.serverLevel();
        for (int i = 0; i < 12; i++) {
            double x = pending.center.getX() + pending.minX
                    + level.random.nextDouble() * (pending.maxX - pending.minX + 1) + 0.5;
            double y = pending.center.getY() + pending.minY
                    + level.random.nextDouble() * (pending.maxY - pending.minY + 1) + 0.5;
            double z = pending.center.getZ() + pending.minZ
                    + level.random.nextDouble() * (pending.maxZ - pending.minZ + 1) + 0.5;
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
        UUID playerId = player.getUUID();
        ActiveProjection active = ACTIVE.remove(playerId);
        if (active != null) {
            active.blocks.forEach((pos, original) -> restore(active.level, pos, original));
            save(player.serverLevel().getServer());
        }
        Placement placement = PLACING.remove(playerId);
        if (placement != null) {
            cancelPlacement(player, playerId, placement);
        }
        if (CHARGING.remove(playerId) != null) {
            stopChargingSound(player);
        }
    }

    private static void cancelPlacement(ServerPlayer player, UUID playerId, Placement placement) {
        PLACING.remove(playerId);
        placement.blocks.forEach((pos, original) -> restore(placement.level, pos, original));
    }

    private static void restore(ServerLevel level, BlockPos pos, OriginalBlock original) {
        level.setBlock(pos, original.state, Block.UPDATE_CLIENTS);
        if (original.blockEntity != null) {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity != null) {
                blockEntity.loadWithComponents(original.blockEntity, level.registryAccess());
                blockEntity.setChanged();
            }
        }
    }

    private static CompoundTag saveBlockEntity(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity == null ? null : blockEntity.saveWithFullMetadata(level.registryAccess());
    }

    private static void save(net.minecraft.server.MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        ProjectionSavedData data = overworld.getDataStorage().computeIfAbsent(
                ProjectionSavedData.factory(), ProjectionSavedData.NAME);
        ListTag projections = new ListTag();
        ACTIVE.values().forEach(active -> {
            CompoundTag saved = new CompoundTag();
            saved.putUUID("Owner", active.owner);
            saved.putString("Dimension", active.level.dimension().location().toString());
            saved.putLong("ExpiresAt", active.expiresAt);
            ListTag blocks = new ListTag();
            active.blocks.forEach((pos, original) -> {
                CompoundTag block = new CompoundTag();
                block.put("Pos", NbtUtils.writeBlockPos(pos));
                block.put("State", NbtUtils.writeBlockState(original.state));
                if (original.blockEntity != null) block.put("BlockEntity", original.blockEntity.copy());
                blocks.add(block);
            });
            saved.put("Blocks", blocks);
            projections.add(saved);
        });
        data.replace(projections);
    }

    private record OriginalBlock(BlockState state, CompoundTag blockEntity) {}
    private record PendingProjection(ServerPlayer player, ListTag entries, BlockPos center,
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ, long readyAt) {}
    private static final class ActiveProjection {
        private final UUID owner;
        private ServerPlayer player;
        private final ServerLevel level;
        private final Map<BlockPos, OriginalBlock> blocks;
        private final long expiresAt;

        private ActiveProjection(UUID owner, ServerPlayer player, ServerLevel level,
                Map<BlockPos, OriginalBlock> blocks, long expiresAt) {
            this.owner = owner;
            this.player = player;
            this.level = level;
            this.blocks = blocks;
            this.expiresAt = expiresAt;
        }
    }

    private static final class Placement {
        private final ServerPlayer player;
        private final ServerLevel level;
        private final ListTag entries;
        private final BlockPos center;
        private final Map<BlockPos, OriginalBlock> blocks = new HashMap<>();
        private int nextIndex;

        private Placement(PendingProjection pending) {
            this.player = pending.player;
            this.level = pending.player.serverLevel();
            this.entries = pending.entries;
            this.center = pending.center;
        }
    }
}
