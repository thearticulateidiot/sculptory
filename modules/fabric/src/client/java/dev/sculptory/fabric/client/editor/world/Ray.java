package dev.sculptory.fabric.client.editor.world;

/**
 * A world-space ray with a unit-length direction. Pure maths: no Minecraft types, so picking code
 * built on it can be unit-tested without a game.
 *
 * <p>The canonical constructor normalizes the direction and rejects non-finite values or a zero
 * direction.
 */
public record Ray(double originX, double originY, double originZ, double dirX, double dirY, double dirZ) {
    public Ray {
        if (!Double.isFinite(originX) || !Double.isFinite(originY) || !Double.isFinite(originZ)) {
            throw new IllegalArgumentException("ray origin must be finite");
        }
        double length = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (!Double.isFinite(length) || length < 1e-12) {
            throw new IllegalArgumentException("ray direction must be finite and non-zero");
        }
        dirX /= length;
        dirY /= length;
        dirZ /= length;
    }

    public double pointX(double t) {
        return originX + dirX * t;
    }

    public double pointY(double t) {
        return originY + dirY * t;
    }

    public double pointZ(double t) {
        return originZ + dirZ * t;
    }

    /**
     * Distance along the ray to the horizontal plane {@code y = planeY}, or {@code NaN} when the ray
     * runs parallel to the plane or the plane lies behind the origin.
     */
    public double distanceToHorizontalPlane(double planeY) {
        if (Math.abs(dirY) < 1e-9) {
            return Double.NaN;
        }
        double t = (planeY - originY) / dirY;
        return t >= 0 ? t : Double.NaN;
    }
}
