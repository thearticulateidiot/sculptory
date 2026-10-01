package dev.sculptory.fabric.schem;

import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import dev.sculptory.core.nbt.NbtLimits;
import dev.sculptory.core.schem.DataFixHook;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.datafixer.Schemas;
import net.minecraft.datafixer.TypeReferences;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtString;

/**
 * {@link DataFixHook} over Minecraft's DataFixer ({@code Schemas.getFixer()}, checked with javap for yarn 1.21.1):
 * palette strings go through {@code TypeReferences.FLAT_BLOCK_STATE} as an {@code NbtString}, block entities
 * through {@code TypeReferences.BLOCK_ENTITY} as a compound, both as a {@code Dynamic} over {@code NbtOps.INSTANCE},
 * from the file's {@code DataVersion} to the running game's save version
 * ({@code SharedConstants.getGameVersion().getSaveVersion().getId()}, 3955 for 1.21.1).
 *
 * <p>Fixed palette strings are cached per (string, version), up to {@value #MAX_CACHED} entries (then the cache
 * starts over). Thread-safe: DataFixerUpper is, and imports run on I/O workers.
 */
public final class FabricDataFixHook implements DataFixHook {
    static final int MAX_CACHED = 16_384;
    private static volatile FabricDataFixHook shared;

    private final DataFixer fixer;
    private final int target;
    private final ConcurrentHashMap<Key, String> states = new ConcurrentHashMap<>();

    private record Key(String state, int fromVersion) {}

    public FabricDataFixHook(DataFixer fixer, int targetDataVersion) {
        this.fixer = Objects.requireNonNull(fixer);
        this.target = targetDataVersion;
    }

    /** The hook for the running game (created on first use, after the game has bootstrapped). */
    public static FabricDataFixHook get() {
        FabricDataFixHook hook = shared;
        if (hook == null) {
            synchronized (FabricDataFixHook.class) {
                hook = shared;
                if (hook == null) {
                    hook = new FabricDataFixHook(Schemas.getFixer(), currentDataVersion());
                    shared = hook;
                }
            }
        }
        return hook;
    }

    /** The running game's data version (3955 for 1.21.1). */
    public static int currentDataVersion() {
        return SharedConstants.getGameVersion().getSaveVersion().getId();
    }

    @Override
    public int targetDataVersion() {
        return target;
    }

    @Override
    public String fixBlockState(String state, int fromDataVersion) {
        Objects.requireNonNull(state);
        if (fromDataVersion >= target) return state;
        Key key = new Key(state, fromDataVersion);
        String cached = states.get(key);
        if (cached != null) return cached;
        Dynamic<NbtElement> fixed = fixer.update(TypeReferences.FLAT_BLOCK_STATE,
                new Dynamic<>(NbtOps.INSTANCE, NbtString.of(state)), fromDataVersion, target);
        String result = fixed.asString().result()
                .orElseThrow(() -> new IllegalStateException("the data fixer did not return a string for " + state));
        if (states.size() >= MAX_CACHED) states.clear();
        states.put(key, result);
        return result;
    }

    /**
     * Entities go through {@code TypeReferences.ENTITY_TREE} (an entity with its {@code Passengers}, each fixed as an
     * {@code ENTITY}), as vanilla fixes the entities of a chunk.
     */
    @Override
    public dev.sculptory.core.nbt.NbtCompound fixEntity(dev.sculptory.core.nbt.NbtCompound entity,
                                                          int fromDataVersion) {
        Objects.requireNonNull(entity);
        if (fromDataVersion >= target) return entity;
        try {
            NbtCompound input = NbtBridge.toMinecraft(entity, NbtLimits.BLOCK_ENTITY.maxBytes());
            Dynamic<NbtElement> fixed = fixer.update(TypeReferences.ENTITY_TREE, new Dynamic<>(NbtOps.INSTANCE, input),
                    fromDataVersion, target);
            if (!(fixed.getValue() instanceof NbtCompound compound)) {
                throw new IllegalStateException("the data fixer did not return a compound");
            }
            return NbtBridge.toCore(compound, NbtLimits.BLOCK_ENTITY);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public dev.sculptory.core.nbt.NbtCompound fixBlockEntity(dev.sculptory.core.nbt.NbtCompound blockEntity,
                                                               int fromDataVersion) {
        Objects.requireNonNull(blockEntity);
        if (fromDataVersion >= target) return blockEntity;
        try {
            NbtCompound input = NbtBridge.toMinecraft(blockEntity, NbtLimits.BLOCK_ENTITY.maxBytes());
            Dynamic<NbtElement> fixed = fixer.update(TypeReferences.BLOCK_ENTITY, new Dynamic<>(NbtOps.INSTANCE, input),
                    fromDataVersion, target);
            if (!(fixed.getValue() instanceof NbtCompound compound)) {
                throw new IllegalStateException("the data fixer did not return a compound");
            }
            return NbtBridge.toCore(compound, NbtLimits.BLOCK_ENTITY);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
