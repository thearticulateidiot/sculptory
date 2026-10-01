package dev.sculptory.fabric.client.editor.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

class CursorRayTest {
    private static final double EPSILON = 1e-4;
    private static final float NEAR = 0.05f;

    /** Projection as GameRenderer.getBasicProjectionMatrix builds it. */
    private static Matrix4f projection(double fovDegrees, int width, int height) {
        return new Matrix4f().perspective((float) Math.toRadians(fovDegrees), (float) width / height, NEAR, 1024f);
    }

    /** View matrix as Minecraft builds it: Camera.setRotation, then GameRenderer conjugates it. */
    private static Matrix4f minecraftView(float yawDegrees, float pitchDegrees) {
        Quaternionf rotation = new Quaternionf().rotationYXZ(
                (float) Math.PI - yawDegrees * 0.017453292f, -pitchDegrees * 0.017453292f, 0f);
        return new Matrix4f().rotation(rotation.conjugate(new Quaternionf()));
    }

    /** Entity.getRotationVector: where a player with this yaw and pitch looks. */
    private static double[] lookVector(float yawDegrees, float pitchDegrees) {
        double pitch = Math.toRadians(pitchDegrees);
        double yaw = Math.toRadians(-yawDegrees);
        return new double[] {Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)};
    }

    private static void assertDirection(double x, double y, double z, Ray ray) {
        double length = Math.sqrt(x * x + y * y + z * z);
        assertEquals(x / length, ray.dirX(), EPSILON, "dir x");
        assertEquals(y / length, ray.dirY(), EPSILON, "dir y");
        assertEquals(z / length, ray.dirZ(), EPSILON, "dir z");
    }

    @Test
    void screenCentreIsCameraForward() {
        float[][] yawPitch = {{0, 0}, {90, 0}, {-135, 30}, {45, -60}, {180, 89}, {10, -89}};
        double cameraX = 1_000_000.5;
        double cameraY = 70.25;
        double cameraZ = -2_000_000.75;
        for (float[] angles : yawPitch) {
            CameraSnapshot camera = new CameraSnapshot(
                    projection(70, 1920, 1080), minecraftView(angles[0], angles[1]), cameraX, cameraY, cameraZ, 1920, 1080, 1);
            Ray ray = CursorRay.center(camera).orElseThrow();
            double[] forward = lookVector(angles[0], angles[1]);
            assertDirection(forward[0], forward[1], forward[2], ray);
            // The ray starts on the near plane straight ahead of the camera.
            assertEquals(cameraX + forward[0] * NEAR, ray.originX(), 1e-3);
            assertEquals(cameraY + forward[1] * NEAR, ray.originY(), 1e-3);
            assertEquals(cameraZ + forward[2] * NEAR, ray.originZ(), 1e-3);
        }
    }

    @Test
    void cornersOfA90DegreeViewFollowTheFrustumAndFlipY() {
        // Identity view looks down -Z with +Y up; a 90° square frustum has corners at 45°.
        CameraSnapshot camera = new CameraSnapshot(projection(90, 200, 200), new Matrix4f(), 0, 0, 0, 200, 200, 1);

        assertDirection(-1, 1, -1, CursorRay.fromFramebuffer(camera, 0, 0).orElseThrow());
        assertDirection(1, 1, -1, CursorRay.fromFramebuffer(camera, 200, 0).orElseThrow());
        assertDirection(-1, -1, -1, CursorRay.fromFramebuffer(camera, 0, 200).orElseThrow());
        assertDirection(1, -1, -1, CursorRay.fromFramebuffer(camera, 200, 200).orElseThrow());
        assertDirection(0, 0, -1, CursorRay.fromFramebuffer(camera, 100, 100).orElseThrow());
        // Top of the window (small y) is up in the world.
        assertTrue(CursorRay.fromFramebuffer(camera, 100, 20).orElseThrow().dirY() > 0);
    }

    @Test
    void guiScaledAndWindowCoordinatesMatchFramebufferPixels() {
        CameraSnapshot camera = new CameraSnapshot(
                projection(70, 1920, 1080), minecraftView(30, 20), 5, 64, 5, 1920, 1080, 1);
        Ray framebuffer = CursorRay.fromFramebuffer(camera, 300, 150).orElseThrow();
        // GUI scale 3: a 640x360 scaled screen.
        Ray scaled = CursorRay.fromScaled(camera, 100, 50, 640, 360).orElseThrow();
        // HiDPI: a 960x540 window backed by the 1920x1080 framebuffer.
        Ray window = CursorRay.fromWindow(camera, 150, 75, 960, 540).orElseThrow();
        for (Ray other : new Ray[] {scaled, window}) {
            assertEquals(framebuffer.dirX(), other.dirX(), 1e-6);
            assertEquals(framebuffer.dirY(), other.dirY(), 1e-6);
            assertEquals(framebuffer.dirZ(), other.dirZ(), 1e-6);
            assertEquals(framebuffer.originX(), other.originX(), 1e-6);
        }
    }

    @Test
    void projectingAPointOnACursorRayReturnsTheCursor() {
        CameraSnapshot camera = new CameraSnapshot(
                projection(70, 1600, 900), minecraftView(-60, 15), 100, 80, -40, 1600, 900, 1);
        Ray ray = CursorRay.fromFramebuffer(camera, 400, 700).orElseThrow();
        double[] screen = new double[2];
        assertTrue(camera.projectNormalized(ray.pointX(25), ray.pointY(25), ray.pointZ(25), screen));
        assertEquals(400.0 / 1600, screen[0], 1e-4);
        assertEquals(700.0 / 900, screen[1], 1e-4);
        // A point behind the camera does not project.
        assertFalse(camera.projectNormalized(ray.pointX(-25), ray.pointY(-25), ray.pointZ(-25), screen));
    }

    @Test
    void degenerateInputGivesNoRay() {
        Matrix4f projection = projection(70, 800, 600);
        CameraSnapshot zeroProjection = new CameraSnapshot(new Matrix4f().zero(), new Matrix4f(), 0, 0, 0, 800, 600, 1);
        CameraSnapshot zeroViewport = new CameraSnapshot(projection, new Matrix4f(), 0, 0, 0, 0, 600, 1);
        CameraSnapshot nanMatrix = new CameraSnapshot(projection, new Matrix4f().m00(Float.NaN), 0, 0, 0, 800, 600, 1);
        CameraSnapshot nanCamera = new CameraSnapshot(projection, new Matrix4f(), Double.NaN, 0, 0, 800, 600, 1);
        for (CameraSnapshot camera : new CameraSnapshot[] {zeroProjection, zeroViewport, nanMatrix, nanCamera}) {
            assertTrue(camera.isDegenerate());
            assertTrue(CursorRay.center(camera).isEmpty());
        }

        CameraSnapshot good = new CameraSnapshot(projection, new Matrix4f(), 0, 0, 0, 800, 600, 1);
        assertFalse(good.isDegenerate());
        assertTrue(CursorRay.fromFramebuffer(good, Double.NaN, 10).isEmpty());
        assertTrue(CursorRay.fromScaled(good, 10, 10, 0, 600).isEmpty());
    }
}
