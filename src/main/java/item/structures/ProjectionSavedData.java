package item.structures;

import java.util.function.BiFunction;
import java.util.function.Supplier;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.saveddata.SavedData;

final class ProjectionSavedData extends SavedData {
    static final String NAME = "projection_crystal_projections";
    private ListTag projections = new ListTag();

    private ProjectionSavedData() {}

    private ProjectionSavedData(ListTag projections) {
        this.projections = projections;
    }

    static ProjectionSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        return new ProjectionSavedData(tag.getList("Projections", CompoundTag.TAG_COMPOUND).copy());
    }

    static SavedData.Factory<ProjectionSavedData> factory() {
        Supplier<ProjectionSavedData> constructor = ProjectionSavedData::new;
        BiFunction<CompoundTag, HolderLookup.Provider, ProjectionSavedData> deserializer = ProjectionSavedData::load;
        return new SavedData.Factory<>(constructor, deserializer, null);
    }

    ListTag projections() {
        return projections;
    }

    void replace(ListTag projections) {
        this.projections = projections.copy();
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("Projections", projections.copy());
        return tag;
    }
}
