package item.structures;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ProjectionCrystalLeftClickPayload() implements CustomPacketPayload {
    public static final Type<ProjectionCrystalLeftClickPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ItemStructures.MODID, "projection_crystal_left_click"));
    public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, ProjectionCrystalLeftClickPayload> STREAM_CODEC =
            StreamCodec.unit(new ProjectionCrystalLeftClickPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
