package dev.sculptory.fabric.client.editor.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SelectionMeshesTest {
    private static final Box TEN = new Box(new BlockPos(0, 60, 0), new BlockPos(9, 69, 9));
    private static final Region.Shape SPHERE = new Region.Shape(TEN, ShapeKind.ELLIPSOID, Facing.UP);

    /** Vertex data stand-in: the mesh and the origin it was baked at. */
    private record Baked(CellMesh mesh, BlockPos origin) {}

    private final List<Runnable> queue = new ArrayList<>();
    private final List<Baked> discarded = new ArrayList<>();
    private final SelectionMeshes.Baker<Baked> baker = new SelectionMeshes.Baker<>() {
        @Override
        public Baked bake(CellMesh mesh, BlockPos origin) {
            bakes++;
            return new Baked(mesh, origin);
        }

        @Override
        public void discard(Baked built) {
            discarded.add(built);
        }
    };
    private int bakes;
    private SelectionMeshes<Baked> meshes = new SelectionMeshes<>(baker, queue::add, CellMesh.Caps.DEFAULT);

    private int baked() {
        return bakes;
    }

    private void runBuilds() {
        while (!queue.isEmpty()) queue.remove(0).run();
    }

    @Test
    void boxesAndNothingHaveNoMesh() {
        assertEquals(Optional.empty(), meshes.update(null));
        assertEquals(Optional.empty(), meshes.update(new Region.Cuboid(TEN)));
        assertEquals(SelectionMeshes.Status.NONE, meshes.status());
        assertFalse(meshes.hasMesh());
        assertTrue(queue.isEmpty(), "nothing is built for a box");
    }

    @Test
    void aShapeIsMeshedOffThreadAndInstalledOnce() {
        assertEquals(Optional.empty(), meshes.update(SPHERE));
        assertEquals(SelectionMeshes.Status.BUILDING, meshes.status());
        assertFalse(meshes.hasMesh());
        assertEquals(1, queue.size());
        assertEquals(Optional.empty(), meshes.update(SPHERE), "not done yet");
        runBuilds();
        Baked baked = meshes.update(SPHERE).orElseThrow();
        assertEquals(TEN.min(), baked.origin());
        assertTrue(baked.mesh().quadCount() > 0);
        assertEquals(SelectionMeshes.Status.READY, meshes.status());
        assertTrue(meshes.hasMesh());
        assertArrayEquals(new int[3], meshes.offset());
        assertEquals(Optional.empty(), meshes.update(SPHERE), "installed once");
        assertTrue(queue.isEmpty());
    }

    @Test
    void aMovedSelectionReusesItsMeshWithAnOffset() {
        meshes.update(SPHERE);
        runBuilds();
        meshes.update(SPHERE);
        assertEquals(Optional.empty(), meshes.update(SPHERE.translate(3, -1, 20)));
        assertTrue(queue.isEmpty(), "no rebuild for a move");
        assertArrayEquals(new int[] {3, -1, 20}, meshes.offset());
        assertEquals(SelectionMeshes.Status.READY, meshes.status());

        // A cell set is compared by identity only: the editor moves it as an offset and keeps its region, so the same
        // region object means "unchanged" and a new one is built (never compared cell by cell here).
        CellSet.Builder builder = CellSet.builder();
        for (int x = 0; x < 20; x += 2) builder.add(x, 64, x % 3);
        Region.Cells cells = new Region.Cells(builder.build());
        meshes.update(cells);
        runBuilds();
        meshes.update(cells).orElseThrow();
        assertEquals(Optional.empty(), meshes.update(cells));
        assertTrue(queue.isEmpty());
        meshes.update(new Region.Cells(builder.build()));
        assertEquals(1, queue.size(), "an equal but new cell set is built again, not compared cell by cell");
        assertNull(SelectionMeshes.translation(cells, cells.translate(1, 0, 0)));
    }

    @Test
    void aReshapedSelectionKeepsTheOldMeshUntilTheNewOneIsReady() {
        meshes.update(SPHERE);
        runBuilds();
        meshes.update(SPHERE);
        Region.Shape cone = new Region.Shape(TEN, ShapeKind.CONE, Facing.UP);
        meshes.update(cone);
        assertEquals(SelectionMeshes.Status.BUILDING, meshes.status());
        assertTrue(meshes.hasMesh(), "the sphere stays drawn meanwhile");
        runBuilds();
        Baked baked = meshes.update(cone).orElseThrow();
        assertTrue(baked.mesh().quadCount() > 0);
        assertEquals(SelectionMeshes.Status.READY, meshes.status());
    }

    @Test
    void aBuildOvertakenByAnotherIsCancelledAndItsVerticesFreed() {
        Region.Shape cone = new Region.Shape(TEN, ShapeKind.CONE, Facing.UP);
        // Overtaken before it ran: it stops at once (before listing sections) and makes nothing.
        meshes.update(SPHERE);
        meshes.update(cone);
        assertEquals(1, queue.size(), "one build at a time: the cone waits for the sphere's to stop");
        runBuilds();
        assertEquals(Optional.empty(), meshes.update(cone), "the cancelled sphere made nothing");
        assertEquals(1, queue.size(), "the cone's build starts once the sphere's has stopped");
        runBuilds();
        Baked baked = meshes.update(cone).orElseThrow();
        assertEquals(TEN.min(), baked.origin());
        assertEquals(1, baked(), "only the cone was meshed and baked");
        assertTrue(discarded.isEmpty());
        // Overtaken after it finished, before it was taken: its vertices are freed, never installed.
        meshes.update(SPHERE);
        runBuilds();
        Region.Shape pyramid = new Region.Shape(TEN, ShapeKind.PYRAMID, Facing.UP);
        assertEquals(Optional.empty(), meshes.update(pyramid));
        assertEquals(1, discarded.size());
        runBuilds();
        assertTrue(meshes.update(pyramid).isPresent());
        assertEquals(1, discarded.size());
    }

    @Test
    void regionsAskedForDuringABuildCoalesceToTheLatest() {
        meshes.update(SPHERE);
        Region latest = null;
        for (int size = 11; size < 60; size++) {
            latest = new Region.Shape(new Box(TEN.min(), TEN.min().offset(size, size, size)), ShapeKind.ELLIPSOID,
                    Facing.UP);
            meshes.update(latest); // a resize drag: a new shape every frame
            assertEquals(1, queue.size(), "never more than one build queued");
        }
        runBuilds(); // the sphere's build, cancelled
        meshes.update(latest);
        runBuilds(); // the latest shape's
        Baked baked = meshes.update(latest).orElseThrow();
        assertEquals(1, baked(), "only the latest shape was meshed");
        assertEquals(SelectionMeshes.Status.READY, meshes.status());
        assertEquals(TEN.min(), baked.origin());
    }

    @Test
    void aHugeShapeIsTooDetailedWithoutListingItsSections() {
        // 20,000 × 384 × 20,000: listing its sections alone would take seconds.
        Region.Shape huge = new Region.Shape(new Box(new BlockPos(0, -64, 0), new BlockPos(19_999, 319, 19_999)),
                ShapeKind.ELLIPSOID, Facing.UP);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            meshes.update(huge);
            runBuilds();
            assertEquals(Optional.empty(), meshes.update(huge));
        });
        assertEquals(SelectionMeshes.Status.TOO_DETAILED, meshes.status());
        assertFalse(meshes.hasMesh());
        assertEquals(0, baked());
        assertTrue(SelectionMeshes.tooLarge(new Region.Shape(new Box(BlockPos.ORIGIN, new BlockPos(599, 599, 599)),
                ShapeKind.ELLIPSOID, Facing.UP)), "360,000 rows along its longest side");
        assertFalse(SelectionMeshes.tooLarge(SPHERE));
    }

    /**
     * A thin shape is measured along its longest side, as the server counts it: a 10 × 600 × 600 disc is 6,000 rows
     * (it was 360,000 counted along x only, and drawn as its bounds), and is outlined.
     */
    @Test
    void aThinShapeIsMeasuredAlongItsLongestSide() {
        Region.Shape disc = new Region.Shape(new Box(BlockPos.ORIGIN, new BlockPos(9, 599, 599)), ShapeKind.CYLINDER,
                Facing.EAST);
        assertFalse(SelectionMeshes.tooLarge(disc));
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            meshes.update(disc);
            runBuilds();
            assertTrue(meshes.update(disc).isPresent());
        });
        assertEquals(SelectionMeshes.Status.READY, meshes.status());
    }

    @Test
    void aMoveDuringTheBuildIsAppliedWhenItLands() {
        meshes.update(SPHERE);
        Region moved = SPHERE.translate(0, 5, 0);
        meshes.update(moved);
        assertEquals(1, queue.size(), "the build for the unmoved shape goes on");
        runBuilds();
        assertTrue(meshes.update(moved).isPresent());
        assertArrayEquals(new int[] {0, 5, 0}, meshes.offset());
    }

    @Test
    void tooManyFacesDrawTheBoundsAndAMoveDoesNotRebuild() {
        meshes = new SelectionMeshes<>(baker, queue::add, new CellMesh.Caps(10, 1_000_000));
        meshes.update(SPHERE);
        runBuilds();
        assertEquals(Optional.empty(), meshes.update(SPHERE));
        assertEquals(SelectionMeshes.Status.TOO_DETAILED, meshes.status());
        assertFalse(meshes.hasMesh());
        meshes.update(SPHERE.translate(1, 0, 0));
        assertTrue(queue.isEmpty());
        assertEquals(SelectionMeshes.Status.TOO_DETAILED, meshes.status());
    }

    @Test
    void tooManyEdgesDrawTheFacesOnly() {
        meshes = new SelectionMeshes<>(baker, queue::add, new CellMesh.Caps(1_000_000, 10));
        meshes.update(SPHERE);
        runBuilds();
        Baked baked = meshes.update(SPHERE).orElseThrow();
        assertEquals(0, baked.mesh().edgeCount());
        assertEquals(SelectionMeshes.Status.FACES_ONLY, meshes.status());
        assertTrue(meshes.hasMesh());
    }

    /**
     * Cell sets are told apart by identity (value equality costs a pass over the cells): the same region instance asked
     * every frame keeps its build, and a new instance of the same cells starts over. A tool showing a cell set must
     * therefore keep one instance while the cells are the same (the Fluid tool does).
     */
    @Test
    void aCellSetAskedAgainByTheSameInstanceKeepsItsBuild() {
        CellSet cells = CellSet.builder().add(1, 60, 1).add(2, 60, 1).add(2, 61, 1).build();
        Region.Cells region = new Region.Cells(cells);
        meshes.update(region);
        assertEquals(1, queue.size());
        meshes.update(region);
        meshes.update(region);
        assertEquals(1, queue.size(), "no new build for the same instance");
        runBuilds();
        assertTrue(meshes.update(region).isPresent(), "the build is installed");
        assertEquals(SelectionMeshes.Status.READY, meshes.status());
        assertEquals(1, baked());

        Region.Cells again = new Region.Cells(cells);
        meshes.update(again);
        assertEquals(1, queue.size(), "a new instance is built again");
        assertTrue(meshes.hasMesh(), "the old mesh stays drawn meanwhile");
        meshes.update(new Region.Cells(cells));
        runBuilds();
        assertTrue(meshes.update(new Region.Cells(cells)).isEmpty(), "a build overtaken every frame never lands");
    }

    @Test
    void goingBackToABoxOrNothingDropsTheMesh() {
        meshes.update(SPHERE);
        runBuilds();
        meshes.update(SPHERE);
        meshes.update(new Region.Cuboid(TEN));
        assertFalse(meshes.hasMesh());
        assertEquals(SelectionMeshes.Status.NONE, meshes.status());
        meshes.update(SPHERE);
        assertEquals(1, queue.size(), "coming back builds again");
    }

    @Test
    void rowsOfEachRegionKindMatchItsCells() {
        CellSet.Builder builder = CellSet.builder();
        builder.add(3, 64, 3).add(4, 64, 3).add(20, 70, -5);
        Region.Cells cells = new Region.Cells(builder.build());
        Region.Shape pyramid = new Region.Shape(new Box(new BlockPos(-5, 60, -5), new BlockPos(20, 70, 3)),
                ShapeKind.PYRAMID, Facing.EAST);
        for (Region region : List.of(cells, pyramid, new Region.Cuboid(TEN))) {
            CellMesh.Rows rows = SelectionMeshes.rows(region);
            Box bounds = region.bounds();
            for (int y = bounds.min().y() - 1; y <= bounds.max().y() + 1; y++) {
                for (int z = bounds.min().z() - 1; z <= bounds.max().z() + 1; z++) {
                    for (int x0 = bounds.min().x() - 16; x0 <= bounds.max().x() + 16; x0 += 16) {
                        int row = rows.row(x0, y, z);
                        for (int i = 0; i < 18; i++) {
                            assertEquals(region.contains(x0 - 1 + i, y, z), (row >>> i & 1) != 0, region + " " + y + " " + z);
                        }
                    }
                }
            }
        }
    }

    @Test
    void translationIsFoundOnlyForTheSameCellsMoved() {
        assertArrayEquals(new int[] {1, 2, 3}, SelectionMeshes.translation(SPHERE, SPHERE.translate(1, 2, 3)));
        assertNull(SelectionMeshes.translation(SPHERE, new Region.Shape(TEN, ShapeKind.CONE, Facing.UP)));
        assertNull(SelectionMeshes.translation(SPHERE, new Region.Cuboid(TEN)));
        CellSet a = CellSet.builder().add(0, 0, 0).add(1, 0, 0).build();
        CellSet b = CellSet.builder().add(0, 0, 0).add(0, 0, 1).build();
        assertNull(SelectionMeshes.translation(new Region.Cells(a), new Region.Cells(b)));
    }
}
