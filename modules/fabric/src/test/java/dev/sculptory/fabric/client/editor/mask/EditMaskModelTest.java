package dev.sculptory.fabric.client.editor.mask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.mask.BlockSet;
import dev.sculptory.core.mask.BoundMask;
import dev.sculptory.core.mask.EditMask;
import dev.sculptory.core.mask.MaskEntry;
import dev.sculptory.core.mask.MaskRule;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.fabric.client.editor.ConfigFile;
import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.blocks.BlockPicker;
import dev.sculptory.fabric.client.editor.render.ghost.GhostMasking;
import dev.sculptory.fabric.client.util.AtomicFileStore;
import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The client's global mask: on/off, the effective mask, the saved rules, ghosts and the set picker's search. */
class EditMaskModelTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final MaskEntry STONE = MaskEntry.of(new MaskRule.Is(BlockSet.parse("minecraft:stone")));

    @Test
    void theMaskAppliesOnlyWhileOnWithRules() {
        EditMaskModel model = new EditMaskModel();
        int[] changes = {0};
        model.onChange(() -> changes[0]++);
        assertFalse(model.on());
        assertSame(EditMask.NONE, model.effective());
        model.setOn(true);
        assertFalse(model.active(), "on without rules lets everything through");
        assertSame(BoundMask.ALL, model.bound(STATES));
        model.setRules(List.of(STONE));
        assertTrue(model.active());
        assertEquals(new EditMask(List.of(STONE), false), model.effective());
        assertSame(model.bound(STATES), model.bound(STATES), "bound once");
        assertFalse(model.toggle());
        assertSame(EditMask.NONE, model.effective());
        assertEquals(3, changes[0]);
        List<MaskEntry> seventeen = Collections.nCopies(17, STONE);
        assertThrows(IllegalArgumentException.class, () -> model.setRules(seventeen));
    }

    @Test
    void anInsideRuleNamesTheSelectionAndMatchesNothingWithout() {
        EditMaskModel model = new EditMaskModel();
        Optional<Region>[] selection = new Optional[] {Optional.empty()};
        model.setSelectionSource(() -> selection[0]);
        // Whatever region an inside rule is given, it stands for the selection.
        model.setRules(List.of(new MaskEntry(new MaskRule.Inside(new Region.Cuboid(Box.of(new BlockPos(5, 5, 5)))), true)));
        assertEquals(EditMaskModel.SELECTION, ((MaskRule.Inside) model.rules().get(0).rule()).region());
        model.setOn(true);
        FakeWorld world = new FakeWorld(STATES);
        assertTrue(model.insideWithoutSelection());
        assertTrue(model.bound(STATES).test(3, 3, 3, STATES.air(), world), "Not inside nothing: every block");
        Region box = new Region.Cuboid(Box.of(new BlockPos(0, 0, 0), new BlockPos(4, 4, 4)));
        selection[0] = Optional.of(box);
        long before = model.revision();
        model.selectionChanged();
        assertTrue(model.revision() > before);
        assertEquals(new EditMask(List.of(new MaskEntry(new MaskRule.Inside(box), true)), false), model.effective());
        assertFalse(model.bound(STATES).test(3, 3, 3, STATES.air(), world));
        assertTrue(model.bound(STATES).test(6, 3, 3, STATES.air(), world));
    }

    @Test
    void theRulesAreSavedAndEveryGameStartsWithTheMaskOff(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(EditMaskStore.FILE_NAME);
        List<String> problems = new ArrayList<>();
        EditMaskModel model = new EditMaskModel();
        new EditMaskStore(new ConfigFile(file, new AtomicFileStore(), problems::add), model).load();
        List<MaskEntry> rules = List.of(STONE, MaskEntry.of(EditMaskModel.insideSelection()),
                new MaskEntry(new MaskRule.Chance(40, 77L), true));
        model.setRules(rules);
        model.setOn(true);
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).startsWith("v1 | is minecraft:stone"));

        EditMaskModel next = new EditMaskModel();
        new EditMaskStore(new ConfigFile(file, new AtomicFileStore(), problems::add), next).load();
        assertEquals(rules, next.rules());
        assertFalse(next.on(), "the mask starts off");
        assertEquals(List.of(), problems);

        Files.writeString(file, "v1 | nonsense");
        EditMaskModel broken = new EditMaskModel();
        new EditMaskStore(new ConfigFile(file, new AtomicFileStore(), problems::add), broken).load();
        assertEquals(List.of(), broken.rules());
        assertEquals(1, problems.size(), "a malformed file is set aside: " + problems);
    }

    @Test
    void ghostsShowOnlyWhatTheMaskLetsThrough() {
        FakeWorld world = new FakeWorld(STATES);
        world.set(0, 64, 0, STATES.state("minecraft:stone"));
        GeneratedSource source = GeneratedSource.builder(10).set(0, 64, 0, 1).set(1, 64, 0, 1).build();
        EditMask stone = new EditMask(List.of(STONE), false);
        GeneratedSource shown = GhostMasking.filter(source, world, stone.bind(STATES));
        assertEquals(1, shown.cells());
        assertTrue(shown.contains(0, 64, 0));
        assertSame(source, GhostMasking.filter(source, world, BoundMask.ALL));
        world.setLoaded(0, 0, false);
        assertSame(source, GhostMasking.filter(source, world, stone.bind(STATES)), "a chunk not loaded here shows");
    }

    @Test
    void theSetPickerFindsTagsAndBlocksAndTypedStates() {
        List<BlockCatalog.Tag> tags = List.of(new BlockCatalog.Tag(new NamespacedId("minecraft:logs"), 20),
                new BlockCatalog.Tag(new NamespacedId("minecraft:leaves"), 9));
        List<BlockCatalog.Entry> blocks = List.of(
                new BlockCatalog.Entry(BlockDescriptor.of(new NamespacedId("minecraft:oak_log")), "Oak Log"),
                new BlockCatalog.Entry(BlockDescriptor.of(new NamespacedId("minecraft:stone")), "Stone"));
        assertEquals(2, BlockPicker.filterSet(tags, blocks, "").size(), "no query: the blocks");
        assertEquals(2, BlockPicker.filterSet(tags, blocks, "#").size(), "#: every tag");
        List<BlockPicker.SetItem> logs = BlockPicker.filterSet(tags, blocks, "log");
        assertEquals(List.of(new BlockPicker.TagItem(tags.get(0)), new BlockPicker.BlockItem(blocks.get(0))), logs);
        assertEquals(new BlockSet.Tag(new NamespacedId("minecraft:logs")), BlockPicker.entryOf(logs.get(0)));
        assertEquals(Optional.of(new BlockSet.State(BlockDescriptor.parse("minecraft:oak_log[axis=x]"))),
                BlockPicker.typedState("oak_log[axis=x]"));
        assertEquals(Optional.empty(), BlockPicker.typedState("oak_log"));
    }
}
