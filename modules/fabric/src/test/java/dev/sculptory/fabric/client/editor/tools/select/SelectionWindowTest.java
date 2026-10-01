package dev.sculptory.fabric.client.editor.tools.select;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.EditorBackend;
import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.mock.MockEditorSession;
import dev.sculptory.fabric.client.editor.mock.MockWorldReader;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.RegionWork;
import dev.sculptory.fabric.client.editor.tool.Selection;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tools.EditorToolSet;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.JobTracker;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The Selection window for boxes, shapes and cell sets (read side, buttons and typed corners). */
class SelectionWindowTest {
    private static final Box BOX = new Box(new BlockPos(0, 60, 0), new BlockPos(9, 69, 4));

    private final MockEditorSession session = new MockEditorSession();
    private final StateSpace states = new FakeStateSpace();
    private final WorldReader world = new MockWorldReader(states);
    private final EditorBackend backend = new EditorBackend() {
        @Override
        public Optional<EditorSession> session() {
            return Optional.of(session);
        }

        @Override
        public StateSpace states() {
            return states;
        }

        @Override
        public WorldReader world() {
            return world;
        }
    };
    private final EditorContext ctx = new EditorContext(() -> backend, notice -> { });
    private final SelectionActions actions = new SelectionActions(() -> ctx.contextFor(ToolId.SELECT), ctx::activeBlock,
            (message, onConfirm) -> onConfirm.run(), Translator.KEYS, () -> 1L);
    private SelectionWindow window;

    @BeforeEach
    void open() {
        EditorToolSet.register(ctx.tools(), new SelectTool(actions, new SelectToolTestServices()));
        window = new SelectionWindow(actions, new SelectionWindow.Host() {
            @Override
            public Optional<Selection> selectionState() {
                return ctx.selectionState();
            }

            @Override
            public void setSelectionRegion(Region region) {
                ctx.setSelectionRegion(region);
            }

            @Override
            public SettingsValues settings() {
                return ctx.settings(ToolId.SELECT);
            }

            @Override
            public void updateSettings(SettingsValues values) {
                ctx.updateSettings(ToolId.SELECT, values);
            }

            @Override
            public BlockDescriptor activeBlock() {
                return ctx.activeBlock();
            }

            @Override
            public Optional<BlockDescriptor> hoveredBlock() {
                return Optional.empty();
            }

            @Override
            public Optional<JobTracker.Job> job(UUID jobId) {
                return Optional.empty();
            }

            @Override
            public void pickBlock(Rect anchor, BlockDescriptor current, Consumer<BlockDescriptor> onPick) {}

            @Override
            public UiContext popups() {
                throw new UnsupportedOperationException();
            }
        }, Translator.KEYS, BlockCatalog.EMPTY);
    }

    private boolean cornersEditable() {
        boolean all = true;
        for (int axis = 0; axis < 3; axis++) {
            all &= window.corner(axis, true).isEnabled() && window.corner(axis, false).isEnabled();
        }
        return all;
    }

    @Test
    void aBoxShowsItsSizeAndEditableCorners() {
        ctx.setSelection(BOX);
        window.refresh();
        assertEquals("sculptory.selection.summary[sculptory.selection.kind.box,10 × 10 × 5,500]",
                window.summaryLabel().text());
        assertTrue(cornersEditable());
        assertEquals("9", window.corner(0, false).text());
        assertFalse(window.convertButton().isVisible(), "a box is a box already");
        assertTrue(window.opButtons().stream().allMatch(Button::isEnabled));
    }

    @Test
    void aShapeNamesItsKindAndCountsItsOwnBlocks() {
        Region.Shape sphere = new Region.Shape(BOX, ShapeKind.ELLIPSOID, Facing.UP);
        ctx.setSelectionRegion(sphere);
        window.refresh();
        assertEquals("sculptory.selection.summary[sculptory.selection.kind.sphere,10 × 10 × 5,"
                + SelectionModel.count(sphere.cellCount()) + "]", window.summaryLabel().text());
        assertTrue(cornersEditable());
        assertTrue(window.convertButton().isVisible());
        assertTrue(window.opButtons().stream().allMatch(Button::isEnabled), "Hollow and Walls too");
        window.convertButton().click();
        assertEquals(new Region.Cuboid(BOX), ctx.selectionRegion().orElseThrow());
    }

    @Test
    void aSetOfBlocksIsABlockSelectionWhateverMadeIt() {
        // Magic, brush and lasso select all make a cell set; the window can't tell them apart and names none of them.
        CellSet set = CellSets.of(new BlockPos(1, 60, 1));
        assertEquals("sculptory.selection.kind.cells", SelectionWindow.kindKey(new Region.Cells(set)));
        assertEquals("sculptory.selection.kind.box", SelectionWindow.kindKey(new Region.Cuboid(BOX)));
    }

    @Test
    void aMagicSelectionHasLockedCornersAndConvertToBox() {
        CellSet set = CellSets.of(new BlockPos(1, 60, 1), new BlockPos(3, 62, 1));
        ctx.setSelectionRegion(new Region.Cells(set));
        window.refresh();
        assertEquals("sculptory.selection.summary[sculptory.selection.kind.cells,3 × 3 × 1,2]",
                window.summaryLabel().text());
        assertFalse(window.corner(0, true).isEnabled());
        assertEquals("sculptory.selection.corners_locked", window.corner(1, false).tooltip());
        assertTrue(window.convertButton().isVisible());
        window.convertButton().click();
        window.refresh();
        assertEquals(new Region.Cuboid(new Box(new BlockPos(1, 60, 1), new BlockPos(3, 62, 1))),
                ctx.selectionRegion().orElseThrow());
        assertTrue(cornersEditable());
        assertEquals("sculptory.selection.min.tooltip[Y]", window.corner(1, true).tooltip());
    }

    @Test
    void aLargeShapesCountComesFromTheBackground() {
        List<Runnable> work = new ArrayList<>();
        List<Runnable> client = new ArrayList<>();
        ctx.setRegionWork(new RegionWork(work::add, client::add));
        Region.Shape sphere = new Region.Shape(new Box(BlockPos.ORIGIN, new BlockPos(199, 199, 199)),
                ShapeKind.ELLIPSOID, Facing.UP);
        ctx.setSelectionRegion(sphere);
        for (int frame = 0; frame < 3; frame++) window.refresh();
        assertEquals("sculptory.selection.summary_counting[sculptory.selection.kind.sphere,200 × 200 × 200]",
                window.summaryLabel().text(), "every frame without counting on the client thread");
        assertEquals(1, work.size(), "one count, not one per frame");
        work.remove(0).run();
        client.remove(0).run();
        window.refresh();
        assertEquals("sculptory.selection.summary[sculptory.selection.kind.sphere,200 × 200 × 200,"
                + SelectionModel.count(sphere.cellCount()) + "]", window.summaryLabel().text());

        Region.Shape huge = new Region.Shape(new Box(BlockPos.ORIGIN, new BlockPos(29_999, 29_999, 29_999)),
                ShapeKind.CONE, Facing.UP);
        ctx.setSelectionRegion(huge);
        window.refresh();
        assertTrue(window.summaryLabel().text().startsWith("sculptory.selection.summary_uncounted["));
        assertTrue(work.isEmpty(), "never counted");
    }

    @Test
    void aMovedCellSetShowsItsBoundsWithoutBeingRebuilt() {
        Region.Cells cells = new Region.Cells(CellSets.of(new BlockPos(1, 60, 1), new BlockPos(3, 62, 1)));
        ctx.setSelectionRegion(cells);
        ctx.moveSelection(10, 0, 0);
        window.refresh();
        assertEquals("11", window.corner(0, true).text());
        assertTrue(ctx.selectionState().orElseThrow().base() == cells);
    }

    @Test
    void nothingSelectedDisablesEverything() {
        window.refresh();
        assertEquals("sculptory.selection.none", window.summaryLabel().text());
        assertFalse(cornersEditable());
        assertFalse(window.convertButton().isVisible());
        assertTrue(window.opButtons().stream().noneMatch(Button::isEnabled));
    }

    @Test
    void typedCornersResizeABoxOrShapeAndLeaveACellSet() {
        Region.Shape cylinder = new Region.Shape(BOX, ShapeKind.CYLINDER, Facing.EAST);
        assertEquals(Optional.of(new Region.Shape(new Box(new BlockPos(0, 60, 0), new BlockPos(20, 69, 4)),
                ShapeKind.CYLINDER, Facing.EAST)), SelectionWindow.withCorner(cylinder, 0, false, 20));
        assertEquals(Optional.of(new Region.Cuboid(new Box(new BlockPos(0, 69, 0), new BlockPos(9, 80, 4)))),
                SelectionWindow.withCorner(new Region.Cuboid(BOX), 1, false, 80)
                        .flatMap(region -> SelectionWindow.withCorner(region, 1, true, 69)));
        assertEquals(Optional.empty(), SelectionWindow.withCorner(new Region.Cells(CellSets.of(BlockPos.ORIGIN)), 0,
                true, 5));
    }

    /** Select tool services without a camera; {@link #note} is the outline note the renderer would give. */
    private static class SelectToolTestServices implements SelectTool.Services {
        Optional<String> note = Optional.empty();

        @Override
        public Optional<String> outlineNote() {
            return note;
        }

        @Override
        public Optional<dev.sculptory.fabric.client.editor.world.Ray> cursorRay() {
            return Optional.empty();
        }

        @Override
        public Optional<dev.sculptory.fabric.client.editor.world.ScreenProjector> projector() {
            return Optional.empty();
        }

        @Override
        public Optional<double[]> eye() {
            return Optional.empty();
        }

        @Override
        public float cameraYaw() {
            return 0;
        }

        @Override
        public void setHoveredFace(dev.sculptory.fabric.client.editor.world.BoxFace face) {}

        @Override
        public String keyLabel(dev.sculptory.fabric.client.editor.input.KeyAction action) {
            return action.id();
        }
    }

    @Test
    void theHintLineNotesASimplifiedOutline() {
        SelectToolTestServices services = new SelectToolTestServices();
        SelectTool tool = new SelectTool(actions, services);
        services.note = Optional.of("sculptory.hint.select.too_detailed");
        assertFalse(tool.hints(ctx.contextFor(ToolId.SELECT)).contains(KeyHint.text("sculptory.hint.select.too_detailed")),
                "nothing selected, nothing to note");
        ctx.setSelectionRegion(new Region.Cells(CellSets.of(BlockPos.ORIGIN)));
        assertTrue(tool.hints(ctx.contextFor(ToolId.SELECT)).contains(KeyHint.text("sculptory.hint.select.too_detailed")));
    }
}
