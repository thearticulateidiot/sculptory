package dev.sculptory.protocol.v2;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.mask.EditMask;
import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.scatter.ScatterSettings;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.core.tinker.EntityEdit;
import dev.sculptory.core.tinker.EntityEdits;
import dev.sculptory.core.tinker.SignText;
import dev.sculptory.core.schem.SchematicFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Client-to-server messages. */
public sealed interface C2S extends Message permits C2S.Hello, C2S.StrokeBegin, C2S.Dabs, C2S.StrokeEnd, C2S.Resync,
        C2S.RunOp, C2S.CancelJob, C2S.Undo, C2S.Redo, C2S.Copy, C2S.PreviewRequest, C2S.LibraryList,
        C2S.LibraryLoad, C2S.SaveAsset, C2S.ExportClipboard, C2S.UploadBegin, C2S.ScatterPreview,
        C2S.LibraryMove, C2S.LibraryDelete, C2S.LibraryCreateFolder, C2S.HistoryOverwrite,
        C2S.PaletteSave, C2S.PaletteLoad, C2S.SelectionUpload, C2S.LibraryAccessGet, C2S.LibraryAccessSet,
        C2S.GeneratedUpload, C2S.TinkerBlock, C2S.TinkerEntity,
        C2S.BuilderPowers, C2S.BuilderPlace, C2S.BuilderBreak, C2S.BuilderDragEnd, C2S.SetEditMask, C2S.Navigate,
        StreamOpen, StreamChunk, StreamEnd, StreamAbort, StreamCredit {

    /** Sent on join. {@code features} is at most 64 names. */
    record Hello(int minProtocol, int maxProtocol, String modVersion, Features features) implements C2S {
        public Hello {
            Objects.requireNonNull(modVersion);
            Objects.requireNonNull(features);
            if (minProtocol < 1 || minProtocol > maxProtocol) throw new IllegalArgumentException("Protocol range");
        }
    }

    record StrokeBegin(int strokeId, BrushSpec spec) implements C2S {
        public StrokeBegin {
            Objects.requireNonNull(spec);
        }
    }

    /** One client tick of dabs, predicted under prediction sequence {@code seq}. */
    record Dabs(int strokeId, int seq, List<Dab> dabs) implements C2S {
        public static final int MAX_DABS = 16;

        public Dabs {
            dabs = List.copyOf(dabs);
            if (dabs.isEmpty() || dabs.size() > MAX_DABS) throw new IllegalArgumentException("1-" + MAX_DABS + " dabs");
        }
    }

    record StrokeEnd(int strokeId) implements C2S {}

    /** Asks the server to resend the chunks under {@code box} after a lost acknowledgement. */
    record Resync(Box box) implements C2S {
        public Resync {
            Objects.requireNonNull(box);
        }
    }

    /**
     * Runs a region op as a job. {@code label} names the job and its history entry after the tool that sent it
     * ({@link OpLabel#NONE}: after the op); it must fit the op ({@link OpLabel#fits}).
     */
    record RunOp(int reqId, OpSpec op, boolean physics, ConflictPolicy conflictPolicy, OpLabel label) implements C2S {
        public RunOp {
            Objects.requireNonNull(op);
            Objects.requireNonNull(conflictPolicy);
            Objects.requireNonNull(label);
        }

        public RunOp(int reqId, OpSpec op, boolean physics, ConflictPolicy conflictPolicy) {
            this(reqId, op, physics, conflictPolicy, OpLabel.NONE);
        }
    }

    record CancelJob(UUID jobId) implements C2S {
        public CancelJob {
            Objects.requireNonNull(jobId);
        }
    }

    record Undo(int reqId, ConflictPolicy policy) implements C2S {
        public Undo {
            Objects.requireNonNull(policy);
        }
    }

    record Redo(int reqId, ConflictPolicy policy) implements C2S {
        public Redo {
            Objects.requireNonNull(policy);
        }
    }

    /**
     * M2. Copies (or cuts) the masked cells of {@code region}, and the entities {@code entities} selects;
     * {@code origin} becomes the clipboard anchor. A {@code Region.Cells} cannot be encoded: cell sets travel as
     * {@link SelectionUpload}s and are named by {@code Region.Uploaded} (a set the server does not hold is refused
     * {@code SELECTION_NOT_LOADED}).
     */
    record Copy(int reqId, Region region, BlockPos origin, boolean cut, CellMask mask, EntityFilter entities)
            implements C2S {
        public Copy {
            Objects.requireNonNull(region);
            Objects.requireNonNull(origin);
            Objects.requireNonNull(mask);
            Objects.requireNonNull(entities);
        }

        /** A box copied without entities. */
        public Copy(int reqId, Box box, BlockPos origin, boolean cut, CellMask mask) {
            this(reqId, new Region.Cuboid(box), origin, cut, mask, EntityFilter.NONE);
        }

        /** The region's bounds. */
        public Box box() {
            return region.bounds();
        }
    }

    /** M2. Asks for a {@code CLIPBOARD_PREVIEW} or {@code ASSET_PREVIEW} stream. */
    record PreviewRequest(SourceRef source) implements C2S {
        public PreviewRequest {
            Objects.requireNonNull(source);
        }
    }

    /** M2. Lists one library folder; {@code ""} is the root. */
    record LibraryList(int reqId, String folder) implements C2S {
        public LibraryList {
            Objects.requireNonNull(folder);
        }
    }

    /** M2. Loads a library asset into the player's clipboard. */
    record LibraryLoad(int reqId, String path) implements C2S {
        public LibraryLoad {
            Objects.requireNonNull(path);
        }
    }

    /** M2. Saves a clipboard into the library. */
    record SaveAsset(int reqId, UUID clipboardId, String path) implements C2S {
        public SaveAsset {
            Objects.requireNonNull(clipboardId);
            Objects.requireNonNull(path);
        }
    }

    /**
     * M4 library management. Renames or moves a file ({@code folder} false: {@code from} and {@code to} are
     * {@code .schem} paths, and {@code to} may be in another folder), or renames a folder in place ({@code folder}
     * true: the same parent). Never replaces an existing entry. Answered with {@code LibraryChanged}, or refused with
     * {@code JobRejected} and a notice.
     */
    record LibraryMove(int reqId, boolean folder, String from, String to) implements C2S {
        public LibraryMove {
            Objects.requireNonNull(from);
            Objects.requireNonNull(to);
        }
    }

    /**
     * M4 library management. Deletes a file ({@code folder} false; it goes to the server's trash) or an empty folder.
     * Answered with {@code LibraryChanged}, or refused with {@code JobRejected} and a notice.
     */
    record LibraryDelete(int reqId, boolean folder, String path) implements C2S {
        public LibraryDelete {
            Objects.requireNonNull(path);
        }
    }

    /** M4 library management. Creates a folder. Answered with {@code LibraryChanged}, or refused as above. */
    record LibraryCreateFolder(int reqId, String path) implements C2S {
        public LibraryCreateFolder {
            Objects.requireNonNull(path);
        }
    }

    /**
     * Undo anyway ({@code redo} false) or Redo anyway: re-applies, with {@code ConflictPolicy.OVERWRITE}, the run of
     * {@code steps} consecutive undo (or redo) steps this player made since their last other history change, in the
     * order they were made. The history position does not move. Answered like {@code Undo}: {@code JobAccepted} and
     * the job's events, or a {@code history_overwrite_refused} notice ({@code [reason, detail, kind]}) followed by
     * {@code JobRejected} when the server's run does not match, skipped nothing, or is gone.
     */
    record HistoryOverwrite(int reqId, boolean redo, int steps) implements C2S {
        /** Most steps one request names (well above the history's entry cap). */
        public static final int MAX_STEPS = 1 << 16;

        public HistoryOverwrite {
            if (steps < 1 || steps > MAX_STEPS) throw new IllegalArgumentException("steps " + steps);
        }
    }

    /**
     * Palettes. Saves a block palette as the library file {@code path} (ending in {@code .palette.json}; an existing
     * palette of that name is replaced). The server resolves every state and refuses the whole save ({@code INVALID},
     * with the state in the notice) when it doesn't know one. Players without {@code library.write} save into their
     * own {@code _players/<uuid>/} folder, as for {@code SaveAsset}. Answered with
     * {@code LibraryChanged(reqId, false, "", path written)}, which other players are sent too (request id
     * {@code PUSH}), or refused with {@code JobRejected} and a notice.
     */
    record PaletteSave(int reqId, String path, BlockPalette palette) implements C2S {
        public PaletteSave {
            Objects.requireNonNull(path);
            Objects.requireNonNull(palette);
        }
    }

    /** Palettes. Loads a library palette; answered with {@code PaletteData}, or refused with {@code JobRejected}. */
    record PaletteLoad(int reqId, String path) implements C2S {
        public PaletteLoad {
            Objects.requireNonNull(path);
        }
    }

    /**
     * Per-asset access. Asks who may load the library file {@code path} (asset or palette); answered with
     * {@code LibraryAccess}, or refused with {@code JobRejected} (only who may change the entry's access may ask).
     */
    record LibraryAccessGet(int reqId, String path) implements C2S {
        public LibraryAccessGet {
            Objects.requireNonNull(path);
        }
    }

    /**
     * Per-asset access. Sets who may load the library file {@code path}. A grantee without a UUID is a name for the
     * server to resolve (an online player, else its user cache); one it cannot resolve refuses the whole change.
     * Answered with {@code LibraryChanged(reqId, false, path, path)}, or refused with {@code JobRejected}.
     */
    record LibraryAccessSet(int reqId, String path, AssetAccess access) implements C2S {
        public LibraryAccessSet {
            Objects.requireNonNull(path);
            Objects.requireNonNull(access);
        }
    }

    /**
     * M2. Asks for a {@code SCHEM_FILE} stream of a clipboard, in {@code format} (protocol 5: the format's ordinal as a
     * varint after the id).
     */
    record ExportClipboard(int reqId, UUID clipboardId, SchematicFormat format) implements C2S {
        public ExportClipboard {
            Objects.requireNonNull(clipboardId);
            Objects.requireNonNull(format);
        }

        /** A Sponge {@code .schem} export. */
        public ExportClipboard(int reqId, UUID clipboardId) {
            this(reqId, clipboardId, SchematicFormat.SPONGE);
        }
    }

    /** M2. Announces a {@code .schem} upload; the server answers with {@code UploadGrant}. */
    record UploadBegin(int reqId, String fileName, long totalBytes) implements C2S {
        public UploadBegin {
            Objects.requireNonNull(fileName);
            if (totalBytes < 0) throw new IllegalArgumentException("Negative upload size");
        }
    }

    /**
     * Announces the upload of a cell set (magic select), like {@link UploadBegin}: the server answers
     * {@code UploadGrant(reqId, streamId, credit)}, the client streams {@code totalBytes} of {@code CellSet.encode()} on
     * a {@link StreamKind#SELECTION_UPLOAD} stream, and the server, once the set decodes with this {@code hash}
     * ({@code CellSet.hash()}), {@code bounds} and {@code cells}, holds it for the player and answers
     * {@code SelectionReady(reqId, hash)}. Any refusal is {@code JobRejected(reqId, reason)}. Ops and copies then name
     * the set as {@code Region.Uploaded(hash, bounds, cells)}.
     */
    record SelectionUpload(int reqId, Sha256 hash, Box bounds, long cells, long totalBytes) implements C2S {
        public SelectionUpload {
            Objects.requireNonNull(hash);
            Objects.requireNonNull(bounds);
            if (cells < 1 || cells > bounds.volume()) {
                throw new IllegalArgumentException("A selection of " + cells + " cells in " + bounds);
            }
            if (totalBytes < 1) throw new IllegalArgumentException("Upload size must be positive");
        }
    }

    /**
     * Generators. Announces the upload of a sparse clipboard made on the client (a generated road or roof), like {@link UploadBegin}: the server answers
     * {@code UploadGrant(reqId, streamId, credit)}, the client streams {@code totalBytes} of the {@code BSGU} payload
     * ({@code core.generate.SparseUpload}) on a {@link StreamKind#GENERATED_UPLOAD} stream, and the server, once the
     * payload decodes with exactly {@code bounds} and {@code cells} present cells, makes it the player's clipboard and
     * answers {@code ClipboardReady(reqId, ...)} and {@code UploadResult(reqId, clipboardId)}, or
     * {@code UploadResult(reqId, error)}. A refusal before the grant is {@code JobRejected(reqId, reason)}.
     */
    record GeneratedUpload(int reqId, Box bounds, long cells, long totalBytes) implements C2S {
        public GeneratedUpload {
            Objects.requireNonNull(bounds);
            if (cells < 1 || cells > bounds.volume()) {
                throw new IllegalArgumentException("A generated clipboard of " + cells + " cells in " + bounds);
            }
            if (totalBytes < 1) throw new IllegalArgumentException("Upload size must be positive");
        }
    }

    /**
     * Tinker (protocol 5): changes the block at {@code pos}, which the client saw as
     * {@code expected}, to {@code target} (the same block with other property values, or {@code expected} itself), and,
     * when {@code sign} is not null, the text of its sign. One history step, written without block updates. Answered
     * {@code S2C.TinkerResult(reqId, ...)}; a block that is no longer {@code expected} is refused and nothing written.
     */
    record TinkerBlock(int reqId, BlockPos pos, int expected, int target, SignText sign) implements C2S {
        public TinkerBlock {
            Objects.requireNonNull(pos);
            if (expected < 0 || target < 0) throw new IllegalArgumentException("Negative state handle");
            if (expected == target && sign == null) throw new IllegalArgumentException("A Tinker change of nothing");
        }
    }

    /**
     * Tinker (protocol 5): applies {@code edits} (at most {@value EntityEdits#MAX_EDITS}) to the entity with UUID
     * {@code entity} as one history step; with no edits, only asks for what the panel shows of it. Answered
     * {@code S2C.TinkerResult(reqId, ...)} carrying the entity's {@code EntityView} after the edits.
     */
    record TinkerEntity(int reqId, UUID entity, List<EntityEdit> edits) implements C2S {
        public TinkerEntity {
            Objects.requireNonNull(entity);
            edits = List.copyOf(edits);
            if (edits.size() > EntityEdits.MAX_EDITS) {
                throw new IllegalArgumentException("More than " + EntityEdits.MAX_EDITS + " entity edits");
            }
        }
    }

    /**
     * Builder mode: the powers the player switched on, as {@link BuilderPower}
     * bits; sent whenever they change and after the handshake. The server keeps them for the connection and applies
     * those it carries out itself (Long reach raises the player's block interaction range while they may use it). No
     * answer.
     */
    record BuilderPowers(int powers) implements C2S {
        public BuilderPowers {
            if (!BuilderPower.valid(powers)) throw new IllegalArgumentException("Unknown builder powers " + powers);
        }
    }

    /**
     * Builder mode: places the block held in the main hand (or {@code offHand}) as a right-click on {@code side} of cell
     * {@code pos} would, at the point ({@code hitX}, {@code hitY}, {@code hitZ}) within that cell (0-1 on each axis), with
     * the placing powers in {@code powers} ({@link BuilderPower} bits: {@code PLACE_IN_AIR} when {@code pos} is the empty
     * cell aimed at, {@code REPLACE}, {@code FORCE_PLACE}, {@code KEEP_SHAPE}, {@code MIRROR}). With {@code MIRROR} the
     * placement is repeated under {@code symmetry}. The client predicted it under vanilla's prediction sequence
     * {@code seq}, which the server acknowledges once it is done or refused; a refusal also gets a notice. One click is one
     * history step.
     */
    record BuilderPlace(int seq, boolean offHand, BlockPos pos, Facing side, float hitX, float hitY, float hitZ, int powers,
                        Symmetry symmetry) implements C2S {
        /** How far a hit may lie outside its cell (float rounding of a point on a face). */
        public static final float HIT_SLACK = 1e-3f;

        public BuilderPlace {
            Objects.requireNonNull(pos);
            Objects.requireNonNull(side);
            Objects.requireNonNull(symmetry);
            if (!inCell(hitX) || !inCell(hitY) || !inCell(hitZ)) {
                throw new IllegalArgumentException("Hit outside its cell: " + hitX + ", " + hitY + ", " + hitZ);
            }
            if (!BuilderPower.valid(powers)) throw new IllegalArgumentException("Unknown builder powers " + powers);
        }

        private static boolean inCell(float value) {
            return value >= -HIT_SLACK && value <= 1 + HIT_SLACK;
        }
    }

    /**
     * Builder mode: breaks {@code cells} (1-{@value #MAX_CELLS}), each with its copies under {@code symmetry} when
     * {@code powers} holds {@code MIRROR}, with {@code KEEP_SHAPE} as a physics-off write. The cells of one drag share
     * {@code dragId} and make one history step, ended by {@code last} or a {@link BuilderDragEnd}; a click is a drag of one
     * message with {@code last} set. With {@code sameKind} only blocks of the drag's first block's kind are broken.
     * Predicted and acknowledged under {@code seq} like {@link BuilderPlace}.
     */
    record BuilderBreak(int seq, int dragId, List<BlockPos> cells, int powers, Symmetry symmetry, boolean sameKind,
                        boolean last) implements C2S {
        public static final int MAX_CELLS = 16;

        public BuilderBreak {
            cells = List.copyOf(cells);
            Objects.requireNonNull(symmetry);
            if (cells.isEmpty() || cells.size() > MAX_CELLS) throw new IllegalArgumentException("1-" + MAX_CELLS + " cells");
            if (!BuilderPower.valid(powers)) throw new IllegalArgumentException("Unknown builder powers " + powers);
        }
    }

    /** Builder mode: the drag {@code dragId} ended (the button was released): its breaks become one history step. */
    record BuilderDragEnd(int dragId) implements C2S {}

    /**
     * The global mask (protocol 5): from now on every edit of this player is limited
     * to the cells {@code mask} accepts ({@code EditMask.NONE}: switched off). The server keeps it for the connection
     * and answers {@code S2C.EditMaskState(reqId, ...)}; after a refusal it refuses edits until a mask it accepts
     * arrives. The client sends it after each {@code Welcome}, when the player changes it, and when the selection an
     * Inside rule names changes.
     */
    record SetEditMask(int reqId, EditMask mask) implements C2S {
        public SetEditMask {
            Objects.requireNonNull(mask);
        }
    }

    /**
     * Jump or Through (protocol 5): moves the player onto the top of the
     * block {@code hit} they look at, on its {@code side}, or through the wall there along the look direction
     * ({@code dirX}, {@code dirY}, {@code dirZ}: finite, not all zero). Answered {@code S2C.NavigateResult(reqId, ...)}.
     */
    record Navigate(int reqId, NavigateMode mode, BlockPos hit, Facing side, float dirX, float dirY, float dirZ)
            implements C2S {
        public Navigate {
            Objects.requireNonNull(mode);
            Objects.requireNonNull(hit);
            Objects.requireNonNull(side);
            if (!Float.isFinite(dirX) || !Float.isFinite(dirY) || !Float.isFinite(dirZ)
                    || (dirX == 0 && dirY == 0 && dirZ == 0)) {
                throw new IllegalArgumentException("Invalid look direction: " + dirX + ", " + dirY + ", " + dirZ);
            }
        }
    }

    /**
     * M3. Plans a scatter of {@code variants} over {@code area}: painted disc stamps (erase stamps included), or
     * every column of a box. The server answers {@code ScatterPlan} and then streams the placements
     * ({@code SCATTER_PLACEMENTS}, see {@link ScatterPlacements}); the player's new preview replaces the previous
     * one. On the wire: at most {@value Codec#MAX_SCATTER_STAMPS} stamps and {@value ScatterSettings#MAX_VARIANTS}
     * variants.
     *
     * @param transforms the rotations and mirroring placements may use; the server narrows them per variant to an
     *     asset's own {@code AssetInfo.rotations}
     */
    record ScatterPreview(int reqId, ScatterArea area, Settings settings, List<Variant> variants,
                          ScatterSettings.Transforms transforms) implements C2S {
        public ScatterPreview {
            Objects.requireNonNull(area);
            Objects.requireNonNull(settings);
            Objects.requireNonNull(transforms);
            variants = List.copyOf(variants);
            if (variants.isEmpty() || variants.size() > ScatterSettings.MAX_VARIANTS) {
                throw new IllegalArgumentException("A scatter needs 1-" + ScatterSettings.MAX_VARIANTS + " variants");
            }
        }

        /**
         * @param spacing the minimum distance between two anchors, 0-{@value ScatterSettings#MAX_SPACING}
         * @param density a share of the eligible columns, or a target placement count
         * @param surface which surfaces may host a placement: elevation, slope and surface-block conjuncts become the
         *     elevation, slope and substrate filters ({@code ScatterSettings.Filters.of})
         * @param fit fluids and support ({@code ScatterSettings.Fit.DEFAULT}: no fluids, half the base supported)
         * @param columnHeight how tall column plants (sugar cane, cactus, bamboo, kelp) grow
         */
        public record Settings(long seed, int spacing, ScatterSettings.Density density, SurfaceMask surface,
                               ScatterSettings.Fit fit, ScatterSettings.ColumnHeight columnHeight) {
            public Settings {
                Objects.requireNonNull(density);
                Objects.requireNonNull(surface);
                Objects.requireNonNull(fit);
                Objects.requireNonNull(columnHeight);
                if (spacing < 0 || spacing > ScatterSettings.MAX_SPACING) {
                    throw new IllegalArgumentException("Spacing must be 0-" + ScatterSettings.MAX_SPACING);
                }
            }

            /** Settings whose column plants are one block tall. */
            public Settings(long seed, int spacing, ScatterSettings.Density density, SurfaceMask surface,
                            ScatterSettings.Fit fit) {
                this(seed, spacing, density, surface, fit, ScatterSettings.ColumnHeight.ONE);
            }
        }

        /**
         * One entry of the mix: the player's clipboard, a library asset already loaded or previewed on the server, or a
         * block given by its state ({@link ScatterSource.Block}; the server resolves and validates it).
         *
         * @param weight 1-{@value ScatterSettings#MAX_WEIGHT}
         */
        public record Variant(ScatterSource source, int weight) {
            public Variant {
                Objects.requireNonNull(source);
                if (weight < 1 || weight > ScatterSettings.MAX_WEIGHT) {
                    throw new IllegalArgumentException("Variant weight must be 1-" + ScatterSettings.MAX_WEIGHT);
                }
            }

            /** A clipboard or library asset variant. */
            public Variant(SourceRef source, int weight) {
                this(new ScatterSource.Held(source), weight);
            }
        }
    }
}
