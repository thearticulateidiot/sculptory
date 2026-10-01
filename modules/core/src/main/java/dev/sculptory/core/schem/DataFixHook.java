package dev.sculptory.core.schem;

import dev.sculptory.core.nbt.NbtCompound;

/**
 * Upgrades schematic content saved by an older game version. {@link SchematicCodec} calls it only when the
 * file's {@code DataVersion} is below {@link #targetDataVersion()}; a file with a newer version is read as is
 * and flagged in the report.
 *
 * <p>The Fabric implementation runs Minecraft's DataFixer ({@code Schemas.getFixer()}) with
 * {@code TypeReferences.FLAT_BLOCK_STATE} for palette strings and {@code TypeReferences.BLOCK_ENTITY} for
 * block-entity NBT, from the file's version to {@code SharedConstants.getGameVersion().getSaveVersion()}.
 * A hook may throw a {@link RuntimeException}: the codec then treats the state as unknown (air, reported) or
 * skips the block entity (reported).
 */
public interface DataFixHook {
    /** The data version content is upgraded to (the running game's, 3955 for 1.21.1). */
    int targetDataVersion();

    /** Upgrades a palette entry ({@code namespace:block[prop=value,...]}). */
    String fixBlockState(String state, int fromDataVersion);

    /** Upgrades block-entity NBT: {@code id} set, no {@code x}/{@code y}/{@code z}. The result's {@code id} wins. */
    NbtCompound fixBlockEntity(NbtCompound blockEntity, int fromDataVersion);

    /**
     * Upgrades entity NBT (the whole entity, {@code id} set; on Fabric {@code TypeReferences.ENTITY}). The result's
     * {@code id} wins. The default changes nothing.
     */
    default NbtCompound fixEntity(NbtCompound entity, int fromDataVersion) {
        return entity;
    }

    /** A hook that changes nothing, for content already at {@code targetDataVersion} (and for tests). */
    static DataFixHook identity(int targetDataVersion) {
        return new DataFixHook() {
            @Override
            public int targetDataVersion() {
                return targetDataVersion;
            }

            @Override
            public String fixBlockState(String state, int fromDataVersion) {
                return state;
            }

            @Override
            public NbtCompound fixBlockEntity(NbtCompound blockEntity, int fromDataVersion) {
                return blockEntity;
            }
        };
    }
}
