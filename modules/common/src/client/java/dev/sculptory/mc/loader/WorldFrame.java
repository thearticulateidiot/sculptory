package dev.sculptory.mc.loader;

import java.util.Objects;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;

/**
 * What a world-render hook gets for the frame being drawn: the camera, the matrices vanilla used for the world (the
 * model-view matrix with the camera's rotation and no translation, and the projection), the frustum (null when the
 * loader has none for this frame) and the world.
 */
public record WorldFrame(Camera camera, Matrix4f positionMatrix, Matrix4f projectionMatrix, Frustum frustum,
                         ClientLevel world) {
    public WorldFrame {
        Objects.requireNonNull(camera);
        Objects.requireNonNull(positionMatrix);
        Objects.requireNonNull(projectionMatrix);
        Objects.requireNonNull(world);
    }
}
