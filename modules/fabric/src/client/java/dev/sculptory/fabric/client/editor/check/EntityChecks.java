package dev.sculptory.fabric.client.editor.check;

import dev.sculptory.core.Box;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor.Face;
import dev.sculptory.fabric.client.editor.tools.place.PlaceTool;
import dev.sculptory.fabric.client.editor.tools.place.Placement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.glfw.GLFW;

/**
 * Entities through the real client: a room with an item frame holding a diamond, a painting and an
 * armor stand in a helmet is copied and pasted (the entities come along, where the blocks go), the paste undone, then
 * the room cut and the cut undone (everything back, entities included).
 */
final class EntityChecks {
    static final String AREA = "entities";

    static final ScenarioGroup GROUP = ScenarioGroup.solo(
            List.of(new Fixtures.Fixture(AREA, EntityChecks::buildRoom)),
            List.of(Scenario.visual("entities", AREA,
                    "Copy and paste a room with an item frame, a painting and an armor stand; undo; cut and undo",
                    EntityChecks::copyPasteCut)));

    private EntityChecks() {}

    /** A plank room, open on top, with its three entities. */
    private static void buildRoom(Fixtures.Build b) {
        b.fill(10, 0, 10, 16, 3, 16, "minecraft:oak_planks");
        b.fill(11, 0, 11, 15, 3, 15, "minecraft:air");
        ServerLevel world = b.world();
        ItemFrame frame = new ItemFrame(world, b.pos(13, 2, 11), Direction.SOUTH);
        frame.setItem(new ItemStack(Items.DIAMOND));
        world.addFreshEntity(frame);
        Painting.create(world, b.pos(11, 2, 13), Direction.EAST).ifPresent(world::addFreshEntity);
        ArmorStand stand = new ArmorStand(world, b.pos(13, 0, 13).getX() + 0.5, b.pos(13, 0, 13).getY(),
                b.pos(13, 0, 13).getZ() + 0.5);
        stand.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        world.addFreshEntity(stand);
    }

    private static void copyPasteCut(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box room = a.box(10, -1, 10, 16, 3, 16);
        Box area = a.box(0, -1, 0, 39, 8, 39);
        Cells before = run.server(area);
        List<String> roomEntities = entities(d, room, 0, 0, 0);
        run.require("the room holds its three entities", roomEntities.size() == 3, String.valueOf(roomEntities));

        // Select the room: its walls' top faces, then Shift+click its floor to take the box down to it.
        d.selectTool(ToolId.SELECT);
        PlaceChecks.selectTopFaces(run, a.box(10, 3, 10, 16, 3, 16));
        d.overhead(a.x(12), a.y(-1), a.z(12));
        d.aim(a.x(12), a.y(-1), a.z(12), Face.UP);
        d.click(GLFW.GLFW_MOD_SHIFT);
        Optional<Box> selected = d.onClient(() -> d.ctx().selection());
        run.require("Shift+click on the floor grows the box down to it", selected.equals(Optional.of(room)),
                selected.map(Box::toString).orElse("nothing"));
        PlaceChecks.copy(run);

        // Paste it 17 blocks east.
        d.action(KeyAction.PASTE);
        PlaceTool place = d.onClient(() -> d.controller().placeTool().orElseThrow());
        run.require("Ctrl+V starts the placement", d.until(20_000, () -> place.placement().isPresent()), d.toastNote());
        d.overlook(a.x(30), a.y(-1), a.z(13));
        d.aim(a.x(30), a.y(-1), a.z(13), Face.UP);
        d.frames(20);
        run.picture("paste-ghost", "The room's ghost at the pointer, with yellow outlines where its item frame,"
                + " painting and armor stand would go");
        d.click(0);
        Box target = d.onClient(() -> place.placement().map(Placement::targetBox).orElse(null));
        run.require("the ghost dropped", target != null, "no placement");
        run.edit("paste the room", () -> d.action(KeyAction.COMMIT));
        int dx = target.min().x() - room.min().x();
        int dy = target.min().y() - room.min().y();
        int dz = target.min().z() - room.min().z();
        Cells.Editor expected = before.edit();
        for (int y = room.min().y(); y <= room.max().y(); y++) {
            for (int z = room.min().z(); z <= room.max().z(); z++) {
                for (int x = room.min().x(); x <= room.max().x(); x++) {
                    BlockState state = before.at(x, y, z);
                    if (!state.isAir()) {
                        expected.set(x + dx, y + dy, z + dz, state);
                    }
                }
            }
        }
        run.exact("the paste places the room's blocks where the ghost was", expected.done());
        List<String> pasted = entities(d, target, dx, dy, dz);
        run.check("the paste brings the item frame (with its diamond), the painting and the armor stand (with its"
                + " helmet) along, each where it stood in the room", pasted.equals(roomEntities),
                "room " + roomEntities + ", paste " + pasted);
        run.picture("pasted", "The room pasted beside the first, its item frame with the diamond, the painting and the"
                + " helmeted armor stand in the same places");
        run.undo("undo the paste");
        run.exact("undo takes the pasted blocks back", before);
        run.check("undo takes the pasted entities back", entities(d, target, dx, dy, dz).isEmpty(),
                String.valueOf(entities(d, target, dx, dy, dz)));

        // Cut the room (still selected), then undo.
        d.selectTool(ToolId.SELECT);
        d.onClient(() -> d.ctx().setSelection(room));
        run.edit("cut the room", () -> d.action(KeyAction.CUT));
        Cells cut = run.server(room);
        run.check("the cut empties the room's cells", cut.count(state -> !state.isAir()) == 0,
                cut.count(state -> !state.isAir()) + " blocks left");
        run.check("the cut takes the room's entities", entities(d, room, 0, 0, 0).isEmpty(),
                String.valueOf(entities(d, room, 0, 0, 0)));
        run.undo("undo the cut");
        run.exact("undo puts the room's blocks back", before);
        List<String> back = entities(d, room, 0, 0, 0);
        run.check("undo puts the item frame, painting and armor stand back as they were", back.equals(roomEntities),
                "before " + roomEntities + ", after " + back);
    }

    /**
     * The entities (not players) in a box on the server, each as its type, what it holds or wears, and its position
     * less {@code (dx, dy, dz)}, sorted: equal lists mean the same entities in the same places relative to the box.
     */
    private static List<String> entities(CheckDriver d, Box box, int dx, int dy, int dz) {
        return d.onServer(server -> {
            ServerLevel world = server.overworld();
            net.minecraft.world.phys.AABB bounds = new net.minecraft.world.phys.AABB(box.min().x(), box.min().y(),
                    box.min().z(), box.max().x() + 1, box.max().y() + 1, box.max().z() + 1);
            List<String> found = new ArrayList<>();
            for (Entity entity : world.getEntities((Entity) null, bounds, entity -> !entity.isAlwaysTicking())) {
                String holds = switch (entity) {
                    case ItemFrame frame -> BuiltInRegistries.ITEM.getKey(frame.getItem().getItem()).toString();
                    case ArmorStand stand -> BuiltInRegistries.ITEM.getKey(stand.getItemBySlot(EquipmentSlot.HEAD)
                            .getItem()).toString();
                    default -> "";
                };
                found.add(String.format(Locale.ROOT, "%s %s at %.2f %.2f %.2f",
                        BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()), holds, entity.getX() - dx, entity.getY() - dy,
                        entity.getZ() - dz));
            }
            found.sort(null);
            return found;
        });
    }
}
