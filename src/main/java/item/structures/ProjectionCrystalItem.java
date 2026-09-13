package item.structures;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** An item which stores a cuboid of block states and projects it around the player. */
public class ProjectionCrystalItem extends Item {
    private static final long MAX_CAPTURE_BLOCKS = 65_536L;
    private static final int MAX_COMPRESSED_BYTES = 1_500_000;
    private static final long MAX_DECOMPRESSED_NBT_BYTES = 32_000_000L;
    private static final int CLEAR_HOLD_TICKS = 4 * 20;
    private static final String CORNER_ONE = "CornerOne";
    private static final String CORNER_TWO = "CornerTwo";
    private static final String CENTER = "Center";
    private static final String BLOCKS = "Blocks";
    private static final String COMPRESSED_BLOCKS = "CompressedBlocks";

    public ProjectionCrystalItem(Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public void verifyComponentsAfterLoad(ItemStack stack) {
        super.verifyComponentsAfterLoad(stack);
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData != null) {
            CompoundTag data = customData.copyTag();
            boolean oldFormatTooLarge = data.contains(BLOCKS)
                    && data.getList(BLOCKS, CompoundTag.TAG_COMPOUND).size() > MAX_CAPTURE_BLOCKS;
            boolean compressedFormatTooLarge = data.contains(COMPRESSED_BLOCKS)
                    && data.getByteArray(COMPRESSED_BLOCKS).length > MAX_COMPRESSED_BYTES;
            boolean networkPayloadTooLarge = data.sizeInBytes() > MAX_COMPRESSED_BYTES;
            if (oldFormatTooLarge || compressedFormatTooLarge || networkPayloadTooLarge) {
                clearCapture(stack);
            }
        }
    }

    @Override
    public net.minecraft.world.InteractionResultHolder<ItemStack> use(net.minecraft.world.level.Level level,
            net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hasCapture(stack)) {
            player.startUsingItem(hand);
            if (!level.isClientSide()) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "item.itemstructures.projection_crystal.hold_to_clear"), true);
            }
            return net.minecraft.world.InteractionResultHolder.consume(stack);
        }
        return net.minecraft.world.InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public int getUseDuration(ItemStack stack, net.minecraft.world.entity.LivingEntity entity) {
        return CLEAR_HOLD_TICKS;
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, net.minecraft.world.level.Level level,
            net.minecraft.world.entity.LivingEntity entity) {
        if (!level.isClientSide() && hasCapture(stack)) {
            clearCapture(stack);
            if (entity instanceof net.minecraft.world.entity.player.Player player) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "item.itemstructures.projection_crystal.cleared"), true);
            }
        }
        return stack;
    }

    @Override
    public void releaseUsing(ItemStack stack, net.minecraft.world.level.Level level,
            net.minecraft.world.entity.LivingEntity entity, int timeLeft) {
        if (!level.isClientSide() && hasCapture(stack)
                && getUseDuration(stack, entity) - timeLeft >= CLEAR_HOLD_TICKS) {
            clearCapture(stack);
            if (entity instanceof net.minecraft.world.entity.player.Player player) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "item.itemstructures.projection_crystal.cleared"), true);
            }
        }
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        if (hasCapture(stack)) {
            context.getPlayer().startUsingItem(context.getHand());
            if (!context.getLevel().isClientSide()) {
                tell(context, "item.itemstructures.projection_crystal.hold_to_clear");
            }
            return InteractionResult.CONSUME;
        }
        if (context.getLevel().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        BlockPos clicked = context.getClickedPos();
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();

        if (!data.contains(CORNER_ONE)) {
            data.put(CORNER_ONE, NbtUtils.writeBlockPos(clicked));
            save(stack, data);
            tell(context, "item.itemstructures.projection_crystal.corner_one");
            return InteractionResult.SUCCESS;
        }
        if (!data.contains(CORNER_TWO)) {
            data.put(CORNER_TWO, NbtUtils.writeBlockPos(clicked));
            save(stack, data);
            tell(context, "item.itemstructures.projection_crystal.corner_two");
            return InteractionResult.SUCCESS;
        }
        if (!data.contains(CENTER)) {
            BlockPos first = NbtUtils.readBlockPos(data, CORNER_ONE).orElseThrow();
            BlockPos second = NbtUtils.readBlockPos(data, CORNER_TWO).orElseThrow();
            if (!isInside(clicked, first, second)) {
                context.getPlayer().displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "item.itemstructures.projection_crystal.center_inside"), true);
                return InteractionResult.FAIL;
            }
            long volume = (Math.abs((long) first.getX() - second.getX()) + 1L)
                    * (Math.abs((long) first.getY() - second.getY()) + 1L)
                    * (Math.abs((long) first.getZ() - second.getZ()) + 1L);
            if (volume > MAX_CAPTURE_BLOCKS) {
                context.getPlayer().displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "item.itemstructures.projection_crystal.too_large"), true);
                return InteractionResult.FAIL;
            }

            data.put(CENTER, NbtUtils.writeBlockPos(clicked));
            ListTag captured = capture(context, first, second, clicked);
            byte[] compressed;
            try {
                compressed = compress(captured);
            } catch (IOException exception) {
                context.getPlayer().displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "item.itemstructures.projection_crystal.too_large"), true);
                return InteractionResult.FAIL;
            }
            if (compressed.length > MAX_COMPRESSED_BYTES) {
                context.getPlayer().displayClientMessage(net.minecraft.network.chat.Component.translatable(
                        "item.itemstructures.projection_crystal.too_large"), true);
                return InteractionResult.FAIL;
            }
            data.putByteArray(COMPRESSED_BLOCKS, compressed);
            data.remove(BLOCKS);
            save(stack, data);
            tell(context, "item.itemstructures.projection_crystal.ready");
            return InteractionResult.SUCCESS;
        }

        context.getPlayer().displayClientMessage(net.minecraft.network.chat.Component.translatable(
                "item.itemstructures.projection_crystal.already_set"), true);
        return InteractionResult.SUCCESS;
    }

    private static ListTag capture(UseOnContext context, BlockPos first, BlockPos second, BlockPos center) {
        ListTag blocks = new ListTag();
        int minX = Math.min(first.getX(), second.getX());
        int minY = Math.min(first.getY(), second.getY());
        int minZ = Math.min(first.getZ(), second.getZ());
        int maxX = Math.max(first.getX(), second.getX());
        int maxY = Math.max(first.getY(), second.getY());
        int maxZ = Math.max(first.getZ(), second.getZ());

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = context.getLevel().getBlockState(pos);
                    CompoundTag entry = new CompoundTag();
                    entry.putInt("x", x - center.getX());
                    entry.putInt("y", y - center.getY());
                    entry.putInt("z", z - center.getZ());
                    entry.put("state", NbtUtils.writeBlockState(state));
                    BlockEntity blockEntity = context.getLevel().getBlockEntity(pos);
                    if (blockEntity != null) {
                        entry.put("block_entity", blockEntity.saveWithFullMetadata(context.getLevel().registryAccess()));
                    }
                    blocks.add(entry);
                }
            }
        }
        return blocks;
    }

    public static void project(ItemStack stack, net.minecraft.server.level.ServerPlayer player) {
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if ((!data.contains(BLOCKS) && !data.contains(COMPRESSED_BLOCKS)) || !data.contains(CENTER)) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "item.itemstructures.projection_crystal.not_ready"), true);
            return;
        }
        ListTag entries = readBlocks(data);
        if (entries == null) {
            clearCapture(stack);
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "item.itemstructures.projection_crystal.too_large"), true);
            return;
        }
        ProjectionManager.toggle(player, entries);
    }

    public static boolean hasCapture(ItemStack stack) {
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return data.contains(CENTER) && (data.contains(BLOCKS) || data.contains(COMPRESSED_BLOCKS));
    }

    public static void clearCapture(ItemStack stack) {
        stack.remove(DataComponents.CUSTOM_DATA);
    }

    private static byte[] compress(ListTag blocks) throws IOException {
        CompoundTag root = new CompoundTag();
        root.put(BLOCKS, blocks);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        NbtIo.writeCompressed(root, output);
        return output.toByteArray();
    }

    private static ListTag readBlocks(CompoundTag data) {
        if (data.contains(BLOCKS)) {
            return data.getList(BLOCKS, CompoundTag.TAG_COMPOUND);
        }
        byte[] compressed = data.getByteArray(COMPRESSED_BLOCKS);
        if (compressed.length == 0 || compressed.length > MAX_COMPRESSED_BYTES) {
            return null;
        }
        try {
            CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(compressed),
                    NbtAccounter.create(MAX_DECOMPRESSED_NBT_BYTES));
            ListTag blocks = root.getList(BLOCKS, CompoundTag.TAG_COMPOUND);
            return blocks.size() > MAX_CAPTURE_BLOCKS ? null : blocks;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    private static boolean isInside(BlockPos pos, BlockPos first, BlockPos second) {
        return pos.getX() >= Math.min(first.getX(), second.getX()) && pos.getX() <= Math.max(first.getX(), second.getX())
                && pos.getY() >= Math.min(first.getY(), second.getY()) && pos.getY() <= Math.max(first.getY(), second.getY())
                && pos.getZ() >= Math.min(first.getZ(), second.getZ()) && pos.getZ() <= Math.max(first.getZ(), second.getZ());
    }

    private static void save(ItemStack stack, CompoundTag data) {
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }

    private static void tell(UseOnContext context, String key) {
        context.getPlayer().displayClientMessage(net.minecraft.network.chat.Component.translatable(key), true);
    }
}
