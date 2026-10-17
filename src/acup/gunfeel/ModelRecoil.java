package acup.gunfeel;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.util.Mth;

// Gun model kick per shot along Curve.PITCH ... POS_Z, scaled by
// the gun's NTM recoil strength. Yaw and roll get a random direction and size per shot. Light guns
// play the curve faster. Overlapping shots add up. Rotation pivots on the right-hand grip.
public final class ModelRecoil {
    private record Shot(long start, float strength, float speed, float pitch, float yaw, float roll,
            Curve cPitch, Curve cYaw, Curve cRoll, Curve cY, Curve cZ) {
        float length() {
            return Math.max(Math.max(cPitch.length(), cYaw.length()), Math.max(cRoll.length(), Math.max(cY.length(), cZ.length())));
        }
    }

    private static final Random RANDOM = new Random();
    private static final List<Shot> SHOTS = new ArrayList<>();

    private ModelRecoil() {
    }

    public static boolean idle() {
        return SHOTS.isEmpty();
    }

    public static void shot(float strength, boolean aiming) {
        float s = strength * Config.modelKick;
        if (s <= 0F) return;
        float loose = aiming ? 0.5F : 1F;
        float speed = Config.modelSpeed / (0.45F + 0.55F * Mth.clamp(strength, 0F, 1F));
        if (SHOTS.size() >= 12) SHOTS.remove(0);
        SHOTS.add(new Shot(System.nanoTime(), s, speed,
                0.85F + 0.3F * RANDOM.nextFloat(),
                loose * (RANDOM.nextBoolean() ? 1F : -1F) * (0.5F + 0.7F * RANDOM.nextFloat()),
                loose * (RANDOM.nextBoolean() ? 1F : -1F) * (0.6F + 0.6F * RANDOM.nextFloat()),
                Curve.of(Config.kickPitch, Curve.PITCH), Curve.of(Config.kickYaw, Curve.YAW), Curve.of(Config.kickRoll, Curve.ROLL),
                Curve.of(Config.kickPosY, Curve.POS_Y), Curve.of(Config.kickPosZ, Curve.POS_Z)));
    }

    // Pose is in NTM's gun frame before the model scale: +Z forward, +Y up. size = gun length there.
    public static void apply(PoseStack pose, float size, Vector3f pivot) {
        if (SHOTS.isEmpty()) return;
        long now = System.nanoTime();
        float pitch = 0F, yaw = 0F, roll = 0F, y = 0F, z = 0F;
        for (int i = SHOTS.size() - 1; i >= 0; i--) {
            Shot s = SHOTS.get(i);
            float t = (now - s.start) / 1e9F * s.speed;
            if (t >= s.length()) {
                SHOTS.remove(i);
                continue;
            }
            pitch += s.cPitch.at(t) * s.strength * s.pitch;
            yaw += s.cYaw.at(t) * s.strength * s.yaw;
            roll += s.cRoll.at(t) * s.strength * s.roll;
            y += s.cY.at(t) * s.strength;
            z += s.cZ.at(t) * s.strength;
        }
        float px = size * 0.016F; // one kick unit (1/16 of the gun length scale) in this frame
        pose.translate(0F, y * px, -z * px);
        pose.translate(pivot.x, pivot.y, pivot.z);
        pose.mulPose(Axis.XP.rotationDegrees(-pitch * Config.modelRotate));
        pose.mulPose(Axis.YP.rotationDegrees(yaw * Config.modelRotate));
        pose.mulPose(Axis.ZP.rotationDegrees(roll * Config.modelRotate));
        pose.translate(-pivot.x, -pivot.y, -pivot.z);
    }

    public static float strength(float vertical, float horizontal) {
        float mag = Math.abs(vertical) + Math.abs(horizontal);
        return Mth.clamp(0.2F + 0.8F * (float) Math.sqrt(mag / 10F), 0.2F, 1.4F);
    }
}
