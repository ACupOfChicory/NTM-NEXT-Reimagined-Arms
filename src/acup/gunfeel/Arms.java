package acup.gunfeel;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.hbm.render.anim.AnimationEnums.GunAnimation;
import com.hbm.render.anim.HbmAnimations;
import com.hbm.render.loader.HFRWavefrontObject;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

// Player arms holding the first-person NTM gun with both hands.
// The first model NTM submits for a gun is its body; the parts it draws give the gun's shape
// (GunGeometry). Hands sit on that shape (Grips), follow the body through NTM's animations, and the
// arms are the player's skinned arms reaching back from the hands along directions fixed to the camera. Arm
// orientation is built against the camera's up axis so arms don't twist when the view turns.
public final class Arms {
    private static final Map<Item, GunGeometry> GEOMETRY = new IdentityHashMap<>();
    private static final Map<Item, float[][]> GRIP_CACHE = new HashMap<>();
    // gun body orientation in the camera frame while no animation plays, per gun
    private static final Map<Item, Quaternionf> REST = new IdentityHashMap<>();
    // each arm at rest, per gun: during animations the arm keeps this length and thickness
    private static final Map<Item, ArmRest[]> ARM_REST = new IdentityHashMap<>();

    private static final class ArmRest {
        boolean valid;
        float length, s;
        final Vector3f hand = new Vector3f(), shoulder = new Vector3f(), axis = new Vector3f(), center = new Vector3f();
        final Matrix4f local = new Matrix4f();
    }
    private static final Matrix4f CAMERA = new Matrix4f();
    private static final Matrix4f KICKED = new Matrix4f();
    private static final Matrix4f BODY = new Matrix4f();
    // body frame relative to the gun frame (after the kick), from the previous frame
    private static final Matrix4f REL = new Matrix4f();
    private static boolean haveRel;
    private static Item relItem;
    private static int gripVersion = -1;

    private static Item item;
    private static GunGeometry geometry;
    private static boolean capturing, haveCamera, haveBody;

    private Arms() {
    }

    public static GunGeometry geometry(Item item) {
        GunGeometry g = GEOMETRY.get(item);
        return g != null && g.seal() ? g : null;
    }

    public static void begin(ItemStack stack) {
        item = stack.getItem();
        geometry = GEOMETRY.get(item);
        haveCamera = haveBody = false;
        capturing = true;
    }

    // frame before NTM's setupFirstPerson: camera-fixed, +X left, +Y up, +Z forward
    public static void cameraFrame(PoseStack pose) {
        CAMERA.set(pose.last().pose());
        haveCamera = true;
    }

    // gun frame after the kick, right before NTM draws the model
    public static void gunFrame(PoseStack pose) {
        KICKED.set(pose.last().pose());
    }

    public static void part(PoseStack pose, HFRWavefrontObject model, int part) {
        if (!capturing) return;
        if (geometry == null) {
            geometry = new GunGeometry(model);
            GEOMETRY.put(item, geometry);
        }
        if (model != geometry.model) return;
        geometry.addPart(part);
        if (!haveBody) {
            BODY.set(pose.last().pose());
            haveBody = true;
        }
    }

    // pivot (right-hand grip) and gun length in the gun frame, for the model kick
    public static float kickFrame(Item held, Vector3f pivot) {
        if (!haveRel || relItem != held || geometry(held) == null) return 0F;
        float[] right = grips(held)[0];
        REL.transformPosition(right[0], right[1], right[2], pivot);
        GunGeometry g = geometry(held);
        Vector3f a = REL.transformPosition(0F, 0F, g.minZ, new Vector3f());
        Vector3f b = REL.transformPosition(0F, 0F, g.maxZ, new Vector3f());
        return a.distance(b);
    }

    public static void end(ItemStack stack, SubmitNodeCollector collector, int light, boolean drawArms) {
        capturing = false;
        if (!haveBody || geometry == null || !geometry.seal()) {
            haveRel = false;
            return;
        }
        REL.set(KICKED).invert().mul(BODY);
        haveRel = true;
        relItem = item;
        if (drawArms && haveCamera && Config.arms) draw(collector, light);
    }

    private static float[][] grips(Item held) {
        if (gripVersion != Grips.version()) {
            GRIP_CACHE.clear();
            PROFILE_CACHE.clear();
            gripVersion = Grips.version();
        }
        return GRIP_CACHE.computeIfAbsent(held, it -> {
            Grips.Grip g = Grips.get(BuiltInRegistries.ITEM.getKey(it));
            GunGeometry geo = geometry(it);
            return new float[][] {geo.grip(g.right()), geo.grip(g.left()), geo.holdCenter(g.right()), geo.holdCenter(g.left())};
        });
    }

    private static final int PROFILE_STEPS = 64;
    private static final Map<Item, float[][][]> PROFILE_CACHE = new HashMap<>();

    // the path each hand slides along: the gun's surface from stock to muzzle at that hand's x/y fractions
    private static float[][][] profiles(Item held) {
        grips(held); // keeps the caches in step with the grip file
        return PROFILE_CACHE.computeIfAbsent(held, it -> {
            Grips.Grip g = Grips.get(BuiltInRegistries.ITEM.getKey(it));
            GunGeometry geo = geometry(it);
            return new float[][][] {geo.profile(g.right()[0], g.right()[1], PROFILE_STEPS),
                    geo.profile(g.left()[0], g.left()[1], PROFILE_STEPS)};
        });
    }

    private static void draw(SubmitNodeCollector collector, int light) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.isInvisible()) return;
        float[][] grip = grips(item);
        AvatarRenderer<?> renderer = mc.getEntityRenderDispatcher().getPlayerRenderer(player);
        PlayerModel model = (PlayerModel) renderer.getModel();
        Identifier skin = player.getSkin().body().texturePath();
        boolean slim = player.getSkin().model() == PlayerModelType.SLIM;
        Matrix4f bodyInCamera = new Matrix4f(CAMERA).invert().mul(BODY);
        Quaternionf now = rotation(bodyInCamera);
        boolean atRest = HbmAnimations.getRelevantAnim(0) == null && ModelRecoil.idle();
        if (atRest) REST.put(item, new Quaternionf(now));
        Quaternionf rest = REST.get(item);
        ArmRest[] rests = ARM_REST.computeIfAbsent(item, k -> new ArmRest[] {new ArmRest(), new ArmRest()});
        // When an animation turns the gun, the arms follow only its roll around the barrel; the rest of
        // the motion (lifting, tilting) is taken up by the hands sliding along the gun.
        Quaternionf twist = new Quaternionf();
        if (!atRest && rest != null) {
            Quaternionf t = new Quaternionf(now).mul(new Quaternionf(rest).conjugate()).normalize();
            // NTM rolls the gun around its barrel axis as it is at rest (the roll comes before lifting)
            Vector3f axis = rest.transform(new Vector3f(0F, 0F, 1F)).normalize();
            float d = t.x * axis.x + t.y * axis.y + t.z * axis.z;
            twist.set(axis.x * d, axis.y * d, axis.z * d, t.w);
            if (twist.lengthSquared() < 1e-8F) twist.identity();
            else twist.normalize();
            if (!Float.isFinite(twist.x + twist.y + twist.z + twist.w)) twist.identity();
        }
        // How much each arm rolls with the gun. While firing/cycling (e.g. canting a lever gun to throw
        // the shell) the support hand holds the forend loosely and the gun turns inside it; the trigger
        // hand turns with the gun. In inspect animations both hands turn the gun together.
        boolean inspect = !atRest && HbmAnimations.getGunAnimation(0) == GunAnimation.INSPECT;
        float followRight = Math.clamp(Config.armFollow, 0F, 1F);
        float followLeft = inspect ? followRight : Math.clamp(Config.armFollowSupport, 0F, 1F);
        Vector3f axisPoint = bodyInCamera.transformPosition(new Vector3f());
        Vector3f down = bodyInCamera.transformDirection(new Vector3f(0F, -1F, 0F)).normalize();
        for (int i = 0; i < 2; i++) {
            boolean right = i == 0;
            Vector3f g = bodyInCamera.transformPosition(grip[i][0], grip[i][1], grip[i][2], new Vector3f());
            float[][] path = profiles(item)[i];
            Vector3f center = bodyInCamera.transformPosition(grip[i + 2][0], grip[i + 2][1], grip[i + 2][2], new Vector3f());
            // while firing/cycling the gun turns inside the loosely holding support hand
            boolean spin = !right && !atRest && !inspect;
            float gripF = (right ? Grips.get(BuiltInRegistries.ITEM.getKey(item)).right() : Grips.get(BuiltInRegistries.ITEM.getKey(item)).left())[2];
            ArmRest r = rests[i];
            // How much of the gun's roll this arm turns with. The hand itself always stays on the gun's
            // surface; a hand that follows less of the roll shows the gun turning inside it.
            Quaternionf own = new Quaternionf().slerp(twist, right ? followRight : followLeft).normalize();
            if (!Float.isFinite(own.x + own.y + own.z + own.w)) own.identity();
            arm(collector, light, right ? model.rightArm : model.leftArm, skin, slim, right, g, path, gripF, bodyInCamera, down, center, spin, own, inspect && !right ? Config.armInspectDrop : 0F, axisPoint, r, atRest,
                    right ? Config.armDirRight : Config.armDirLeft, right ? Config.armReachRight : Config.armReachLeft,
                    player.isModelPartShown(right ? PlayerModelPart.RIGHT_SLEEVE : PlayerModelPart.LEFT_SLEEVE),
                    right ? model.rightSleeve : model.leftSleeve);
        }
    }

    // point at fraction f (stock 0 - muzzle 1) of a hand's path, in camera space, hand offset added
    private static Vector3f onPath(float[][] path, float f, Matrix4f bodyInCamera, Vector3f off) {
        float x = Math.clamp(f, 0F, 1F) * (path.length - 1);
        int k = Math.min((int) x, path.length - 2);
        float u = x - k;
        float[] a = path[k], b = path[k + 1];
        return bodyInCamera.transformPosition(a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u,
                new Vector3f()).add(off);
    }

    // Pure rotation of a matrix that may carry scale and a mirror (NTM flips models with a negative scale).
    private static Quaternionf rotation(Matrix4f m) {
        org.joml.Matrix3f r = new org.joml.Matrix3f();
        m.get3x3(r);
        Vector3f c0 = r.getColumn(0, new Vector3f()).normalize();
        Vector3f c1 = r.getColumn(1, new Vector3f()).normalize();
        Vector3f c2 = r.getColumn(2, new Vector3f()).normalize();
        if (c0.dot(new Vector3f(c1).cross(c2)) < 0F) c0.negate();
        r.setColumn(0, c0).setColumn(1, c1).setColumn(2, c2);
        return r.getNormalizedRotation(new Quaternionf()).normalize();
    }

    // In camera space. grip = point on the gun's underside; the arm's end face touches the gun there.
    // stock = the same point near the stock, where the hand may slide to during animations.
    // The arm cube runs from the shoulder end (y = 0) to the hand end (y = 12/16) along +Y.
    private static void arm(SubmitNodeCollector collector, int light, ModelPart arm, Identifier skin, boolean slim,
            boolean right, Vector3f grip, float[][] path, float gripF, Matrix4f bodyInCamera, Vector3f down, Vector3f center, boolean spin, Quaternionf twist, float drop, Vector3f axisPoint, ArmRest rest, boolean atRest,
            float[] dirCfg, float reach, boolean sleeveShown, ModelPart sleeve) {
        Vector3f dir = new Vector3f(dirCfg[0], dirCfg[1], dirCfg[2]);
        if (dir.lengthSquared() < 1e-6F) return;
        dir.normalize();
        float length, s;
        Vector3f hand, shoulder;
        Quaternionf turn = new Quaternionf();
        if (spin && rest.valid) {
            // The rest arm, carried along with the held part and turned (by its share of the roll) around
            // that part's centre: a round forend turning inside the hand never leaves the palm.
            Matrix4f moved = new Matrix4f().translate(center).rotate(twist).translate(new Vector3f(rest.center).negate()).mul(rest.local);
            submit(collector, light, arm, skin, new Matrix4f(CAMERA).mul(moved), sleeveShown, sleeve);
            return;
        }
        if (atRest || !rest.valid) {
            // run back from the hand until depth `reach`; at least one vanilla arm length
            length = dir.z < -1e-3F ? (grip.z - reach) / -dir.z : 18F / 16F;
            length = Math.max(length, 18F / 16F);
            s = Math.min(length / (18F / 16F), Config.armMaxScale) * Config.armScale;
            // hand hangs under the grip by half its thickness so the arm does not cut into the gun
            hand = new Vector3f(down).mul(2F / 16F * s).add(grip);
            shoulder = new Vector3f(dir).mul(length).add(hand);
            if (atRest) {
                rest.valid = true;
                rest.length = length;
                rest.s = s;
                rest.hand.set(hand);
                rest.shoulder.set(shoulder);
                rest.axis.set(axisPoint);
                rest.center.set(center);
            }
        } else {
            length = rest.length;
            s = rest.s;
            Vector3f off = new Vector3f(down).mul(2F / 16F * s);
            if (drop != 0F) {
                // inspect: lower the hand on screen (in arm pixels) as the gun rolls onto its side,
                // so less of the arm shows above the turned gun
                float angle = 2F * (float) Math.acos(Math.min(1F, Math.abs(twist.w)));
                off.y -= drop / 16F * s * (float) Math.abs(Math.sin(angle));
            }
            Vector3f restArm = new Vector3f(rest.shoulder).sub(rest.hand);
            // The shoulder stays where it was at rest, except that the gun's roll swings the arm around
            // the barrel axis as it was at rest. Other motion (lifting) is taken up by the hand sliding.
            Vector3f handAtGrip = new Vector3f(grip).add(off);
            Vector3f rolledHand = twist.transform(new Vector3f(rest.hand).sub(rest.axis)).add(rest.axis);
            shoulder = twist.transform(new Vector3f(restArm)).add(rolledHand);
            // The arm may stretch a little; beyond that the hand slides along the gun's surface toward
            // the stock until the arm reaches it.
            float limit = length * (1F + Math.max(0F, Config.armStretch));
            hand = handAtGrip;
            if (Config.armSlide && handAtGrip.distance(shoulder) > limit) {
                float lo = Math.min(Config.armSlideTo, gripF), hi = gripF;
                for (int k = 0; k < 16; k++) {
                    float mid = (lo + hi) * 0.5F;
                    if (onPath(path, mid, bodyInCamera, off).distance(shoulder) > limit) hi = mid;
                    else lo = mid;
                }
                hand = onPath(path, lo, bodyInCamera, off);
            }
            // keep the rest length: the shoulder end moves along the arm instead of the arm stretching
            Vector3f back = new Vector3f(shoulder).sub(hand);
            if (back.lengthSquared() < 1e-8F) back.set(restArm);
            float reachNow = Math.min(back.length(), limit);
            shoulder = back.normalize().mul(Math.max(reachNow, length)).add(hand);
            length = Math.max(reachNow, length);
            // arm orientation: the rest orientation rolled with the gun, then aimed at the new direction
            Vector3f restY = new Vector3f(restArm).negate().normalize();
            Vector3f rolledY = twist.transform(new Vector3f(restY));
            Vector3f newY = new Vector3f(hand).sub(shoulder).normalize();
            turn = new Quaternionf().rotationTo(rolledY, newY).mul(twist).normalize();
            if (!Float.isFinite(turn.x + turn.y + turn.z + turn.w)) turn.identity();
            dir = restY.negate(); // basis built from the rest direction, then turned
        }

        Vector3f y = new Vector3f(dir).negate().normalize();
        Vector3f x = new Vector3f(0F, 1F, 0F).cross(y);
        if (x.lengthSquared() < 1e-6F) x.set(1F, 0F, 0F);
        x.normalize();
        Vector3f z = new Vector3f(x).cross(y).normalize();
        Matrix4f basis = new Matrix4f(
                x.x, x.y, x.z, 0F,
                y.x, y.y, y.z, 0F,
                z.x, z.y, z.z, 0F,
                0F, 0F, 0F, 1F);

        float cx = (right ? -1F : 1F) * (slim ? 5.5F : 6F) / 16F;
        float end = 12F / 16F;
        Matrix4f local = new Matrix4f()
                .translate(hand)
                .rotate(turn)
                .mul(basis)
                .rotateY((float) Math.toRadians(right ? Config.armRollRight : Config.armRollLeft))
                .scale(s, length / end, s)
                .translate(-cx, -end, 0F);
        if (atRest) rest.local.set(local);
        submit(collector, light, arm, skin, new Matrix4f(CAMERA).mul(local), sleeveShown, sleeve);
    }

    private static void submit(SubmitNodeCollector collector, int light, ModelPart arm, Identifier skin, Matrix4f m,
            boolean sleeveShown, ModelPart sleeve) {
        PoseStack pose = new PoseStack();
        pose.mulPose(m);
        arm.resetPose();
        arm.visible = true;
        sleeve.visible = sleeveShown;
        collector.submitModelPart(arm, pose, RenderTypes.entityTranslucent(skin), light, OverlayTexture.NO_OVERLAY, null);
    }
}
