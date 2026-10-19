package acup.gunfeel;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
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
    // every variant of a gun seen so far (a weapon mod can change which parts NTM draws), and the one in hand
    private static final Map<Item, List<GunGeometry>> VARIANTS = new IdentityHashMap<>();
    private static final Map<Item, GunGeometry> CURRENT = new IdentityHashMap<>();
    private static final Map<GunGeometry, float[][]> GRIP_CACHE = new IdentityHashMap<>();
    // the gun body and the parts each hand holds relative to the aimed gun frame while no animation plays, per gun variant
    private static final Map<GunGeometry, GunRest> GUN_REST = new IdentityHashMap<>();

    private static final class GunRest {
        boolean valid;
        final Matrix4f body = new Matrix4f();
        final Matrix4f[] hold = {new Matrix4f(), new Matrix4f()};
    }
    // each arm at rest, per gun variant: during animations the arm keeps this length and thickness
    private static final Map<GunGeometry, ArmRest[]> ARM_REST = new IdentityHashMap<>();

    private static final class ArmRest {
        boolean valid;
        float length, s;
        final Vector3f hand = new Vector3f(), shoulder = new Vector3f(), axis = new Vector3f(), center = new Vector3f();
        final Matrix4f local = new Matrix4f();
        // pixels of the arm drawn from the hand (12 = the whole arm), see local()
        int crop = 12;
    }
    private static final Matrix4f CAMERA = new Matrix4f();
    private static final Matrix4f KICKED = new Matrix4f();
    // gun frame after aiming, before the model kick
    private static final Matrix4f AIMED = new Matrix4f();
    private static final Matrix4f BODY = new Matrix4f();
    // body frame relative to the gun frame (after the kick), from the previous frame
    private static final Matrix4f REL = new Matrix4f();
    private static boolean haveRel;
    private static Item relItem;
    private static int gripVersion = -1;

    // pose of the part each hand moves with (armPartRight / armPartLeft), this frame
    // [2], [3]: the part the right / left hand carries things within (armRightCarry / armLeftCarry)
    private static final Matrix4f[] FOLLOW = {new Matrix4f(), new Matrix4f(), new Matrix4f(), new Matrix4f()};
    private static final boolean[] HAVE_FOLLOW = new boolean[4];
    private static final int[] FOLLOW_ID = {-1, -1, -1, -1};

    // the parts NTM drew this frame (of the first model it drew) and where
    private static final int MAX_PARTS = 128;
    private static final Matrix4f FROM_ALIGNED = new Matrix4f();
    private static final int[] FRAME_PART = new int[MAX_PARTS];
    private static final Matrix4f[] FRAME_POSE = new Matrix4f[MAX_PARTS];
    private static int frameParts;
    private static HFRWavefrontObject frameModel;

    private static Item item;
    private static GunGeometry geometry;
    private static boolean capturing, haveCamera;

    private Arms() {
    }

    // A gun's rest pose is read while no animation moves it. Taken in hand for the first time it plays its draw
    // animation, so the arms would wait for it to end: that frame the gun is first run through its renderer with
    // the animations held still and drawn nowhere (measuring), only to read the pose.
    public static boolean measuring;
    private static final Map<Item, Integer> MEASURES = new IdentityHashMap<>();
    public static final net.minecraft.client.renderer.SubmitNodeCollector NOWHERE = (net.minecraft.client.renderer.SubmitNodeCollector)
            java.lang.reflect.Proxy.newProxyInstance(Arms.class.getClassLoader(), new Class<?>[] {net.minecraft.client.renderer.SubmitNodeCollector.class},
                    (proxy, m, args) -> {
                        Class<?> t = m.getReturnType();
                        if (t.isInstance(proxy)) return proxy;
                        if (m.getDeclaringClass() == Object.class) {
                            return switch (m.getName()) {
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == args[0];
                                default -> "Arms.NOWHERE";
                            };
                        }
                        if (t == boolean.class) return false;
                        if (t == int.class) return 0;
                        if (t == long.class) return 0L;
                        if (t == float.class) return 0F;
                        if (t == double.class) return 0.0;
                        return null;
                    });

    // its rest pose not read yet (a few tries per gun: one whose pose cannot be read is not run twice every frame)
    public static boolean needsMeasure(Item held) {
        GunGeometry g = CURRENT.get(held);
        if (g != null && g.aligned()) {
            GunRest gr = GUN_REST.get(g);
            if (gr != null && gr.valid) return false;
        }
        return MEASURES.merge(held, 1, Integer::sum) <= 5;
    }

    // the variant of the gun last drawn in hand
    public static GunGeometry geometry(Item item) {
        GunGeometry g = CURRENT.get(item);
        return g != null && g.aligned() ? g : null;
    }

    public static void begin(ItemStack stack) {
        item = stack.getItem();
        geometry = null;
        haveCamera = false;
        frameModel = null;
        frameParts = 0;
        java.util.Arrays.fill(HAVE_FOLLOW, false);
        java.util.Arrays.fill(FOLLOW_ID, -1);
        capturing = true;
    }

    // Smoke from the muzzle (smokeUpright): NTM draws it in the gun's frame, x = trailing back, y = rising,
    // z = sideways. Keep where it starts and its size, but lay those axes along the camera, as on an upright
    // gun: back toward the viewer, up the screen, to the left.
    private static final java.util.Set<Item> SMOKE_LOGGED = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    public static void uprightSmoke(PoseStack pose) {
        if (!haveCamera || item == null) return;
        Matrix4f m = pose.last().pose();
        Matrix4f cameraInv = new Matrix4f(CAMERA).invert();
        Matrix4f inCamera = new Matrix4f(cameraInv).mul(m);
        if (SMOKE_LOGGED.add(item)) {
            Vector3f x = inCamera.transformDirection(new Vector3f(1F, 0F, 0F)).normalize();
            Vector3f y = inCamera.transformDirection(new Vector3f(0F, 1F, 0F)).normalize();
            GunFeel.LOGGER.info("smoke frame of {} (camera: +x left, +y up, +z forward): trail {} rise {}",
                    BuiltInRegistries.ITEM.getKey(item), x, y);
        }
        float scale = inCamera.getScale(new Vector3f()).x;
        Vector3f origin = inCamera.transformPosition(new Vector3f());
        Matrix4f upright = new Matrix4f(
                0F, 0F, -scale, 0F,
                0F, scale, 0F, 0F,
                scale, 0F, 0F, 0F,
                origin.x, origin.y, origin.z, 1F);
        m.set(CAMERA).mul(upright);
        pose.last().normal().set(new org.joml.Matrix3f(m).normal());
    }

    // frame before NTM's setupFirstPerson: camera-fixed, +X left, +Y up, +Z forward
    public static void cameraFrame(PoseStack pose) {
        CAMERA.set(pose.last().pose());
        haveCamera = true;
    }

    // gun frame after aiming, before the model kick
    public static void aimFrame(PoseStack pose) {
        AIMED.set(pose.last().pose());
    }

    // gun frame after the kick, right before NTM draws the model
    public static void gunFrame(PoseStack pose) {
        KICKED.set(pose.last().pose());
    }

    public static void part(PoseStack pose, HFRWavefrontObject model, int part) {
        if (!capturing) return;
        if (frameModel == null) {
            frameModel = model;
            FOLLOW_ID[0] = partByName(model, Config.armPartRight);
            FOLLOW_ID[1] = partByName(model, Config.armPartLeft);
            FOLLOW_ID[2] = partByName(model, carry(0).part);
            FOLLOW_ID[3] = partByName(model, carry(1).part);
        }
        if (model != frameModel) return;
        if (frameParts < MAX_PARTS) {
            if (FRAME_POSE[frameParts] == null) FRAME_POSE[frameParts] = new Matrix4f();
            FRAME_POSE[frameParts].set(pose.last().pose());
            FRAME_PART[frameParts++] = part;
        }
        for (int i = 0; i < 4; i++) {
            if (!HAVE_FOLLOW[i] && FOLLOW_ID[i] >= 0 && part == FOLLOW_ID[i]) {
                FOLLOW[i].set(pose.last().pose());
                HAVE_FOLLOW[i] = true;
            }
        }
    }

    private static int partByName(HFRWavefrontObject model, String name) {
        if (name == null || name.isEmpty()) return -1;
        for (int i = 0; i < model.groups.length; i++) if (model.groups[i].name.equalsIgnoreCase(name)) return i;
        return -1;
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
        geometry = variant();
        int body = -1;
        if (geometry != null) {
            // the body: the gun's biggest part (smaller parts may be drawn first, moved by an animation)
            for (int k = 0; k < frameParts && body < 0; k++) if (FRAME_PART[k] < 0 || FRAME_PART[k] == geometry.bodyPart) body = k;
        }
        if (body < 0) {
            haveRel = false;
            return;
        }
        BODY.set(FRAME_POSE[body]);
        if (!geometry.aligned()) {
            // how the renderer turns the model and places its parts, read while no animation moves them
            if (HbmAnimations.getRelevantAnim(0) == null) {
                Matrix4f rel = new Matrix4f(KICKED).invert().mul(BODY);
                org.joml.Matrix3f r = new org.joml.Matrix3f();
                rel.get3x3(r);
                Matrix4f toBody = new Matrix4f(BODY).invert();
                for (int k = 0; k < frameParts; k++) {
                    int p = FRAME_PART[k];
                    if (p >= 0 && geometry.partRest[p] == null) geometry.partRest[p] = new Matrix4f(toBody).mul(FRAME_POSE[k]);
                }
                geometry.align(r);
            }
            if (!geometry.aligned()) {
                haveRel = false;
                return;
            }
        }
        // model space -> the gun's aligned frame (a whole-quarter-turn rotation: its inverse is its transpose)
        Matrix4f fromAligned = new Matrix4f().set(new org.joml.Matrix3f(geometry.toAligned).transpose());
        FROM_ALIGNED.set(fromAligned);
        BODY.mul(fromAligned);
        for (int i = 0; i < 4; i++) if (HAVE_FOLLOW[i]) FOLLOW[i].mul(fromAligned);
        REL.set(KICKED).invert().mul(BODY);
        haveRel = true;
        relItem = item;
        if (measuring) {
            if (!haveCamera) return;
            GunRest gr = GUN_REST.computeIfAbsent(geometry, k -> new GunRest());
            Matrix4f aimedInv = new Matrix4f(AIMED).invert();
            gr.body.set(aimedInv).mul(BODY);
            for (int i = 0; i < 2; i++) gr.hold[i].set(aimedInv).mul(HAVE_FOLLOW[i] ? FOLLOW[i] : BODY);
            gr.valid = true;
            return;
        }
        if (drawArms && haveCamera && Config.arms) draw(collector, light);
    }

    // The variant matching the parts drawn this frame. A new variant is only taken while no animation
    // plays (animations may hide parts for a moment); during one the gun keeps its current variant.
    private static GunGeometry variant() {
        if (frameModel == null || frameParts == 0) return null;
        BitSet drawn = new BitSet();
        for (int k = 0; k < frameParts; k++) {
            if (FRAME_PART[k] < 0) drawn.set(0, frameModel.groups.length);
            else if (FRAME_PART[k] < frameModel.groups.length) drawn.set(FRAME_PART[k]);
        }
        List<GunGeometry> list = VARIANTS.computeIfAbsent(item, k -> new ArrayList<>());
        GunGeometry g = null;
        for (GunGeometry v : list) if (v.model == frameModel && v.parts.equals(drawn)) g = v;
        if (g == null && HbmAnimations.getRelevantAnim(0) == null && !drawn.isEmpty()) {
            g = new GunGeometry(frameModel, drawn, list.size());
            list.add(g);
        }
        if (g == null) g = CURRENT.get(item);
        if (g != null) CURRENT.put(item, g);
        return g;
    }

    private static float[][] grips(Item held) {
        if (gripVersion != Grips.version()) {
            GRIP_CACHE.clear();
            PROFILE_CACHE.clear();
            gripVersion = Grips.version();
        }
        return GRIP_CACHE.computeIfAbsent(geometry(held), geo -> {
            Grips.Grip g = Grips.get(BuiltInRegistries.ITEM.getKey(held), geo.missing);
            float[] r = g.rightAt() != null ? g.rightAt() : geo.grip(g.right());
            float[] l = g.leftAt() != null ? g.leftAt() : geo.grip(g.left());
            return new float[][] {r, l, geo.holdCenter(r), geo.holdCenter(l)};
        });
    }

    private static final int PROFILE_STEPS = 64;
    private static final Map<GunGeometry, float[][][]> PROFILE_CACHE = new IdentityHashMap<>();

    // the path each hand slides along: the gun's surface from stock to muzzle at that hand's x/y fractions
    private static float[][][] profiles(Item held) {
        grips(held); // keeps the caches in step with the grip file
        return PROFILE_CACHE.computeIfAbsent(geometry(held), geo -> {
            Grips.Grip g = Grips.get(BuiltInRegistries.ITEM.getKey(held), geo.missing);
            float[] r = g.rightAt() != null ? geo.fractions(g.rightAt()) : g.right();
            float[] l = g.leftAt() != null ? geo.fractions(g.leftAt()) : g.left();
            return new float[][][] {geo.profile(r[0], r[1], PROFILE_STEPS), geo.profile(l[0], l[1], PROFILE_STEPS)};
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
        Matrix4f cameraInv = new Matrix4f(CAMERA).invert();
        Matrix4f bodyInCamera = new Matrix4f(cameraInv).mul(BODY);
        boolean atRest = HbmAnimations.getRelevantAnim(0) == null && ModelRecoil.idle();
        GunRest gr = GUN_REST.computeIfAbsent(geometry, k -> new GunRest());
        // Where the gun and the held parts sit relative to the aimed gun frame while nothing moves them.
        // The arms' rest pose is rebuilt from it every frame, so it follows aiming in and out even while
        // an animation plays (the gun keeps its place relative to the aimed frame, only the animation moves it).
        Matrix4f aimedInv = new Matrix4f(AIMED).invert();
        if (atRest) {
            gr.body.set(aimedInv).mul(BODY);
            for (int i = 0; i < 2; i++) gr.hold[i].set(aimedInv).mul(HAVE_FOLLOW[i] ? FOLLOW[i] : BODY);
            gr.valid = true;
        }
        if (!gr.valid) return;
        Matrix4f aimedInCamera = new Matrix4f(cameraInv).mul(AIMED);
        Matrix4f restBody = new Matrix4f(aimedInCamera).mul(gr.body);
        Quaternionf now = rotation(bodyInCamera);
        Quaternionf rest = rotation(restBody);
        ArmRest[] rests = ARM_REST.computeIfAbsent(geometry, k -> new ArmRest[] {new ArmRest(), new ArmRest()});
        // When an animation turns the gun, the arms follow only its roll around the barrel; the rest of
        // the motion (lifting, tilting) is taken up by the hands sliding along the gun.
        Quaternionf twist = new Quaternionf();
        if (!atRest) {
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
        Vector3f restAxis = restBody.transformPosition(new Vector3f());
        Vector3f down = bodyInCamera.transformDirection(new Vector3f(0F, -1F, 0F)).normalize();
        Vector3f restDown = restBody.transformDirection(new Vector3f(0F, -1F, 0F)).normalize();
        for (int i = 0; i < 2; i++) {
            boolean right = i == 0;
            ModelPart arm = right ? model.rightArm : model.leftArm;
            ModelPart sleeve = right ? model.rightSleeve : model.leftSleeve;
            boolean sleeveShown = player.isModelPartShown(right ? PlayerModelPart.RIGHT_SLEEVE : PlayerModelPart.LEFT_SLEEVE);
            ArmRest r = rests[i];
            // the arm as it would be at rest with the gun where it is now (aimed or not)
            Matrix4f restHolder = new Matrix4f(aimedInCamera).mul(gr.hold[i]);
            Vector3f restGrip = restHolder.transformPosition(grip[i][0], grip[i][1], grip[i][2], new Vector3f());
            Vector3f restCenter = restHolder.transformPosition(grip[i + 2][0], grip[i + 2][1], grip[i + 2][2], new Vector3f());
            restArm(r, right, slim, restGrip, restDown, restCenter, restAxis,
                    right ? Config.armDirRight : Config.armDirLeft, right ? Config.armReachRight : Config.armReachLeft);
            if (!r.valid) continue;
            if (atRest) {
                branch = "rest";
                submit(collector, light, arm, skin, new Matrix4f(CAMERA).mul(r.local), r.crop, right, slim, sleeveShown, sleeve);
                continue;
            }
            // armLeftHide / armRightHide (per gun): the hand is not drawn in these stretches of an animation
            if (hidden(i)) {
                if (debugArms) GunFeel.LOGGER.info("[armdebug] {} {} t={} hidden", right ? "R" : "L", HbmAnimations.getGunAnimation(0), fmt(animMillis()));
                continue;
            }
            // A hand goes from part to part during an animation (armLeftPath / armRightPath).
            float[] onPath = pathState(i, cameraInv);
            if (onPath != null) {
                Matrix4f home = HAVE_FOLLOW[i] ? new Matrix4f(cameraInv).mul(FOLLOW[i]) : bodyInCamera;
                Vector3f g = home.transformPosition(grip[i][0], grip[i][1], grip[i][2], new Vector3f());
                Vector3f at = new Vector3f(onPath[1], onPath[2], onPath[3]);
                Vector3f side = new Vector3f(onPath[4], onPath[5], onPath[6]);
                float w = onPath[0];
                Vector3f p = new Vector3f(g).lerp(at, w);
                Vector3f off = new Vector3f(down).lerp(side, w);
                if (off.lengthSquared() < 1e-6F) off.set(side);
                ArmRest there = new ArmRest();
                restArm(there, right, slim, p, off, p, axisPoint,
                        right ? Config.armDirRight : Config.armDirLeft, right ? Config.armReachRight : Config.armReachLeft,
                        right ? Config.armLoadScaleRight : Config.armLoadScaleLeft);
                ArmRest mix = r;
                if (there.valid) {
                    mix = new ArmRest();
                    mix.valid = true;
                    mix.length = r.length + (there.length - r.length) * w;
                    mix.s = r.s + (there.s - r.s) * w;
                    mix.hand.set(r.hand);
                    mix.shoulder.set(r.shoulder).add(onPath[7], onPath[8], onPath[9]);
                    mix.axis.set(r.axis);
                    mix.center.set(r.center);
                    mix.local.set(r.local);
                    mix.crop = r.crop;
                }
                // a hand near the eye: show at least armPathMinLength pixels of the arm, not the last one or two
                if (Config.armPathMinLength > 0F) {
                    if (mix == r) {
                        mix = new ArmRest();
                        mix.valid = true;
                        mix.length = r.length;
                        mix.s = r.s;
                        mix.hand.set(r.hand);
                        mix.shoulder.set(r.shoulder).add(onPath[7], onPath[8], onPath[9]);
                        mix.axis.set(r.axis);
                        mix.center.set(r.center);
                        mix.local.set(r.local);
                        mix.crop = r.crop;
                    }
                    mix.length = Math.max(mix.length, Config.armPathMinLength / 16F * mix.s);
                }
                float holdRoll = right ? Config.armRollRight : Config.armRollLeft;
                float loadRoll = right ? Config.armLoadRollRight : Config.armLoadRollLeft;
                rollNow = Float.isNaN(loadRoll) ? Float.NaN : holdRoll + (loadRoll - holdRoll) * w;
                Matrix4f m = pose(right, slim, p, null, geometry.fraction(grip[i][2]), bodyInCamera, off, p, false,
                        new Quaternionf(), 0F, mix);
                rollNow = Float.NaN;
                int crop = lastCrop;
                // armPathBlend (per gun): near the grip the path arm turns into the usual hold (size, turn, cut),
                // blended around the hand, so leaving and taking the grip does not jump
                if (Config.armPathBlend && w < 1F) {
                    Vector3f hc = home.transformPosition(grip[i + 2][0], grip[i + 2][1], grip[i + 2][2], new Vector3f());
                    Quaternionf hown = new Quaternionf().slerp(twist, right ? followRight : followLeft).normalize();
                    if (!Float.isFinite(hown.x + hown.y + hown.z + hown.w)) hown.identity();
                    Matrix4f usual = pose(right, slim, g, HAVE_FOLLOW[i] ? null : profiles(item)[i], geometry.fraction(grip[i][2]), bodyInCamera,
                            down, hc, !right && !inspect, hown, inspect && !right ? Config.armInspectDrop : 0F, r);
                    float e = w * w * (3F - 2F * w);
                    m = blendAtHand(usual, lastCrop, m, crop, e, right, slim);
                    crop = blendCrop;
                }
                branch = "path w=" + fmt(onPath[0]) + (Config.armPathBlend ? " blend" : "");
                submit(collector, light, arm, skin, new Matrix4f(CAMERA).mul(m), crop, right, slim, sleeveShown, sleeve);
                continue;
            }
            if (debugArms) branchNote = "carry " + (carryState(i) == null ? "none" : "w=" + fmt(carryState(i)[0])) + " follow=" + HAVE_FOLLOW[2 + i];
            // A hand leaves the gun to carry something NTM animates (shells into the chambers or the tube) and comes back.
            float[] carried = carryState(i);
            if (carried != null && HAVE_FOLLOW[2 + i]) {
                Matrix4f home = HAVE_FOLLOW[i] ? new Matrix4f(cameraInv).mul(FOLLOW[i]) : bodyInCamera;
                Vector3f g = home.transformPosition(grip[i][0], grip[i][1], grip[i][2], new Vector3f());
                Matrix4f within = new Matrix4f(cameraInv).mul(FOLLOW[2 + i]);
                Carry c = carry(i);
                Vector3f at = within.transformPosition(carried[1], carried[2], carried[3], new Vector3f());
                // the side's length is the hand's distance from the point, in half arm widths (1 = the hand touches it)
                Vector3f side = within.transformDirection(new Vector3f(c.dir)).normalize().mul(new Vector3f(c.dir).length());
                Vector3f p = new Vector3f(g).lerp(at, carried[0]);
                Vector3f off = new Vector3f(down).lerp(side, carried[0]);
                if (off.lengthSquared() < 1e-6F) off.set(side);
                // the arm sized and aimed as if it rested at the carrying spot (a hand near the camera is thinner
                // than one reaching far forward), blended with the arm on the gun by the carry weight
                ArmRest there = new ArmRest();
                restArm(there, right, slim, p, off, p, axisPoint,
                        right ? Config.armDirRight : Config.armDirLeft, right ? Config.armReachRight : Config.armReachLeft,
                        right ? Config.armLoadScaleRight : Config.armLoadScaleLeft);
                ArmRest mix = r;
                if (there.valid) {
                    float w = carried[0];
                    mix = new ArmRest();
                    mix.valid = true;
                    mix.length = r.length + (there.length - r.length) * w;
                    mix.s = r.s + (there.s - r.s) * w;
                    // the shoulder stays where it is (reaching back past the camera would sweep the arm across it)
                    mix.hand.set(r.hand);
                    mix.shoulder.set(r.shoulder);
                    mix.axis.set(r.axis);
                    mix.center.set(r.center);
                    mix.local.set(r.local);
                    mix.crop = r.crop;
                }
                // a hand loading shells may turn differently around its own axis (armLoadRollRight/Left)
                float holdRoll = right ? Config.armRollRight : Config.armRollLeft;
                float loadRoll = right ? Config.armLoadRollRight : Config.armLoadRollLeft;
                rollNow = Float.isNaN(loadRoll) ? Float.NaN : holdRoll + (loadRoll - holdRoll) * carried[0];
                Matrix4f m = pose(right, slim, p, null, geometry.fraction(grip[i][2]), bodyInCamera, off, p, false,
                        new Quaternionf(), 0F, mix);
                rollNow = Float.NaN;
                // armCarryTurnRight/Left (per gun): the arm turned around the carried point about the shell's long
                // axis (parallel to its flat end), so it does not sink into the gun; + = the shoulder end to the right
                float turn = right ? Config.armCarryTurnRight : Config.armCarryTurnLeft;
                if (turn != 0F) {
                    Vector3f axis = within.transformDirection(new Vector3f(0F, 0F, 1F)).normalize();
                    float ang = (float) Math.toRadians(-turn * carried[0]);
                    m = new Matrix4f().translate(at).rotate(ang, axis).translate(new Vector3f(at).negate()).mul(m);
                }
                int crop = lastCrop;
                if (Config.armCarryKeepAngle) {
                    // armCarryKeepAngle (per gun): the arm keeps its hold on the gun (angle, length) and only moves,
                    // the hand from the grip to beside the carried thing (Sexy's bottle)
                    Matrix4f hold = HAVE_FOLLOW[i] ? new Matrix4f(cameraInv).mul(FOLLOW[i]) : bodyInCamera;
                    Vector3f hg = hold.transformPosition(grip[i][0], grip[i][1], grip[i][2], new Vector3f());
                    Vector3f hc = hold.transformPosition(grip[i + 2][0], grip[i + 2][1], grip[i + 2][2], new Vector3f());
                    Quaternionf hown = new Quaternionf().slerp(twist, right ? followRight : followLeft).normalize();
                    if (!Float.isFinite(hown.x + hown.y + hown.z + hown.w)) hown.identity();
                    Matrix4f usual = pose(right, slim, hg, HAVE_FOLLOW[i] ? null : profiles(item)[i], geometry.fraction(grip[i][2]), bodyInCamera,
                            down, hc, !right && !inspect, hown, 0F, r);
                    float k = 2F / 16F * r.s;
                    Vector3f from = new Vector3f(down).mul(k).add(hg);
                    Vector3f to = new Vector3f(side).mul(k).add(at);
                    float e = carried[0] * carried[0] * (3F - 2F * carried[0]);
                    Vector3f move = to.sub(from).mul(e);
                    m = new Matrix4f().translation(move).mul(usual);
                    crop = lastCrop;
                } else if (Config.armCarryBlend && carried[0] < 1F) {
                    // armCarryBlend (per gun): from the hand's usual hold (sliding along a lifted gun) into carrying, so
                    // the first frame of carrying does not jump to the bare grip point

                    Matrix4f hold = HAVE_FOLLOW[i] ? new Matrix4f(cameraInv).mul(FOLLOW[i]) : bodyInCamera;
                    Vector3f hg = hold.transformPosition(grip[i][0], grip[i][1], grip[i][2], new Vector3f());
                    Vector3f hc = hold.transformPosition(grip[i + 2][0], grip[i + 2][1], grip[i + 2][2], new Vector3f());
                    Quaternionf hown = new Quaternionf().slerp(twist, right ? followRight : followLeft).normalize();
                    if (!Float.isFinite(hown.x + hown.y + hown.z + hown.w)) hown.identity();
                    Matrix4f usual = pose(right, slim, hg, HAVE_FOLLOW[i] ? null : profiles(item)[i], geometry.fraction(grip[i][2]), bodyInCamera,
                            down, hc, !right && !inspect, hown, 0F, r);
                    float w = carried[0];
                    m = blend(usual, m, w);
                    if (w < 0.5F) crop = lastCrop;
                }
                branch = "carry w=" + fmt(carried[0]) + " at=" + fmt(at) + (Config.armCarryKeepAngle ? " keep" : Config.armCarryBlend ? " blend" : "");
                submit(collector, light, arm, skin, new Matrix4f(CAMERA).mul(m), crop, right, slim, sleeveShown, sleeve);
                continue;
            }
            // A hand lets go while the gun spins (sawn-off Maresleg cocking, Henry inspect): it keeps its rest pose,
            // moves aside and back, and takes the gun again when the turn is complete.
            float release = release(right ? Config.armReleaseRight : Config.armReleaseLeft);
            if (release > 0F) {
                float[] m = right ? Config.armReleaseMoveRight : Config.armReleaseMove;
                float k = (0.5F - 0.5F * (float) Math.cos(2.0 * Math.PI * release)) / 16F * r.s;
                Matrix4f moved = new Matrix4f().translate(m[0] * k, m[1] * k, m[2] * k).mul(r.local);
                branch = "release " + fmt(release);
                submit(collector, light, arm, skin, new Matrix4f(CAMERA).mul(moved), r.crop, right, slim, sleeveShown, sleeve);
                continue;
            }
            // a hand on a moving part (a pump) goes along with that part
            Matrix4f holder = HAVE_FOLLOW[i] ? new Matrix4f(cameraInv).mul(FOLLOW[i]) : bodyInCamera;
            Vector3f g0 = holder.transformPosition(grip[i][0], grip[i][1], grip[i][2], new Vector3f());
            // a hand on a moving part (a pump) stays on it: no sliding along the body, the arm reaches instead
            float[][] path = HAVE_FOLLOW[i] ? null : profiles(item)[i];
            Vector3f center = holder.transformPosition(grip[i + 2][0], grip[i + 2][1], grip[i + 2][2], new Vector3f());
            // while firing/cycling the gun turns inside the loosely holding support hand
            boolean spin = !right && !inspect;
            float gripF = geometry.fraction(grip[i][2]);
            // How much of the gun's roll this arm turns with. The hand itself always stays on the gun's
            // surface; a hand that follows less of the roll shows the gun turning inside it.
            Quaternionf own = new Quaternionf().slerp(twist, right ? followRight : followLeft).normalize();
            if (!Float.isFinite(own.x + own.y + own.z + own.w)) own.identity();
            float drop = inspect && !right ? Config.armInspectDrop : 0F;
            // scripted: the left hand goes around the part it holds (over a break-action's barrels to open them)
            float around = right ? Float.NaN : leftAngle();
            if (!Float.isNaN(around)) {
                float[] sec = geometry.sectionAt(grip[i][2]);
                if (sec != null) {
                    float a = (float) Math.toRadians(around), c = (float) Math.cos(a), sn = (float) Math.sin(a);
                    float cx = (sec[0] + sec[1]) * 0.5F, cy = (sec[2] + sec[3]) * 0.5F;
                    // armAroundSmooth (per gun, Liberator): under the gun (-90) the hand is at its own grip point,
                    // which need not be the bottom of the section (inside a vertical foregrip); going round to the
                    // side (0) it moves from there onto the section. Off: the hand goes round the section only.
                    float home = Math.clamp(-around / 90F, 0F, 1F);
                    float pull = Config.armAroundSmooth ? home : 0F;
                    float ox = (grip[i][0] - cx) * pull, oy = (grip[i][1] - sec[2]) * pull;
                    Vector3f g = holder.transformPosition(cx + (sec[1] - sec[0]) * 0.5F * c + ox, cy + (sec[3] - sec[2]) * 0.5F * sn + oy, grip[i][2], new Vector3f());
                    Vector3f out = holder.transformDirection(new Vector3f(c, sn, 0F)).normalize();
                    // armAroundShift (per gun): going round, the hand sits this many arm pixels to the left (+x), none under the part
                    // reloads may have their own values (armAroundShiftReload / armAroundTopDropReload)
                    GunAnimation playing = HbmAnimations.getGunAnimation(0);
                    boolean reloading = playing == GunAnimation.RELOAD || playing == GunAnimation.RELOAD_CYCLE || playing == GunAnimation.RELOAD_END;
                    float shift = reloading && !Float.isNaN(Config.armAroundShiftReload) ? Config.armAroundShiftReload : Config.armAroundShift;
                    float topDrop = reloading && !Float.isNaN(Config.armAroundTopDropReload) ? Config.armAroundTopDropReload : Config.armAroundTopDrop;
                    if (shift != 0F) {
                        float side = Math.clamp((around + 90F) / 90F, 0F, 1F);
                        g.add(holder.transformDirection(new Vector3f(1F, 0F, 0F)).normalize().mul(shift / 16F * r.s * side));
                    }
                    // armAroundTopDrop (per gun): over the part the hand sits this many arm pixels lower, fading out toward the side
                    if (topDrop != 0F && sn > 0F) {
                        g.add(holder.transformDirection(new Vector3f(0F, -1F, 0F)).normalize().mul(topDrop / 16F * r.s * sn));
                    }
                    if (!Config.armAroundSmooth) {
                        // back under the gun (e.g. as it is flicked up to close) the hand slides along it like at rest
                        boolean under = Math.abs(around + 90F) < 0.5F;
                        branch = "around " + fmt(around);
                        arm(collector, light, arm, skin, slim, right, g, under ? profiles(item)[i] : null, gripF, holder, out, g, false,
                                new Quaternionf(), 0F, axisPoint, r, sleeveShown, sleeve);
                        continue;
                    }
                    // armAroundSmooth: going round, the arm keeps its rest length (stretching it showed its shoulder end)
                    keepLength = true;
                    Matrix4f m = pose(right, slim, g, null, gripF, holder, out, g, false, new Quaternionf(), 0F, r);
                    keepLength = false;
                    int crop = lastCrop;
                    // Between the side (-45) and under the gun (-90) the arm turns smoothly into its usual hold,
                    // so taking the gun again at the end of the animation does not jump.
                    float w = Math.clamp((home - 0.5F) / 0.5F, 0F, 1F);
                    w = w * w * (3F - 2F * w);
                    if (w > 0F) {
                        Matrix4f usual = pose(right, slim, g0, path, gripF, bodyInCamera, down, center, spin, own, drop, r);
                        m = blend(m, usual, w);
                        if (w > 0.5F) crop = lastCrop;
                    }
                    branch = "around smooth " + fmt(around);
                    submit(collector, light, arm, skin, new Matrix4f(CAMERA).mul(m), crop, right, slim, sleeveShown, sleeve);
                    continue;
                }
            }
            branch = "hold g=" + fmt(g0) + " spin=" + spin;
            arm(collector, light, arm, skin, slim, right, g0, path, gripF, bodyInCamera, down, center, spin, own,
                    drop, axisPoint, r, sleeveShown, sleeve);
        }
    }

    private static final String[] hideSource = new String[2];
    @SuppressWarnings("unchecked")
    private static final Map<String, float[]>[] hideKeys = new Map[] {Map.of(), Map.of()};

    // "RELOAD 0-650; RELOAD_END 0-700" (ms from the animation's start, several ranges allowed): the hand hidden then
    private static boolean hidden(int hand) {
        String src = hand == 0 ? Config.armRightHide : Config.armLeftHide;
        if (src.isEmpty()) return false;
        if (!src.equals(hideSource[hand])) {
            hideSource[hand] = src;
            Map<String, float[]> out = new HashMap<>();
            for (String anim : src.split(";")) {
                String[] w = anim.trim().split("\\s+");
                if (w.length < 2) continue;
                List<Float> v = new ArrayList<>();
                for (int j = 1; j < w.length; j++) {
                    String[] ab = w[j].split("-");
                    if (ab.length != 2) continue;
                    try {
                        float a = Float.parseFloat(ab[0]), b = Float.parseFloat(ab[1]);
                        v.add(a);
                        v.add(b);
                    } catch (NumberFormatException e) {
                        // skip
                    }
                }
                float[] a = new float[v.size()];
                for (int j = 0; j < a.length; j++) a[j] = v.get(j);
                out.put(w[0].toUpperCase(java.util.Locale.ROOT), a);
            }
            hideKeys[hand] = out;
        }
        float[] r = forAnimation(hideKeys[hand]);
        if (r == null) return false;
        float t = animMillis();
        for (int j = 0; j + 1 < r.length; j += 2) if (t >= r[j] && t < r[j + 1]) return true;
        return false;
    }

    // /gunfeel armdebug: every frame, how each arm was placed, to the log ("[armdebug]")
    public static boolean debugArms;
    private static String branch = "", branchNote = "";

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    private static String fmt(Vector3f v) {
        return String.format(java.util.Locale.ROOT, "(%.3f %.3f %.3f)", v.x, v.y, v.z);
    }

    private static final String[] keysSource = new String[2];
    @SuppressWarnings("unchecked")
    private static final Map<String, float[]>[] keys = new Map[] {Map.of(), Map.of()};

    // angle of the left hand around the part it holds at this moment of the animation (armLeftKeys), NaN = none
    private static float leftAngle() {
        return keyValue(Config.armLeftKeys, 0);
    }

    private static float animMillis() {
        HbmAnimations.Animation anim = HbmAnimations.getRelevantAnim(0);
        return anim == null ? 0F : com.hbm.util.GameTime.now() - anim.startMillis;
    }

    // value of time keys ("RELOAD 0:-90 240:90; ...") at this moment of the current animation, NaN = none
    private static float keyValue(String src, int slot) {
        if (src.isEmpty()) return Float.NaN;
        HbmAnimations.Animation anim = HbmAnimations.getRelevantAnim(0);
        GunAnimation type = HbmAnimations.getGunAnimation(0);
        if (anim == null || type == null) return Float.NaN;
        if (!src.equals(keysSource[slot])) {
            keysSource[slot] = src;
            keys[slot] = parseKeys(src);
        }
        float[] k = keys[slot].get(type.name());
        if (k == null) return Float.NaN;
        float t = animMillis();
        if (t <= k[0]) return k[1];
        for (int j = 2; j + 1 < k.length; j += 2) {
            if (t < k[j]) {
                float u = (t - k[j - 2]) / Math.max(k[j] - k[j - 2], 1F);
                u = u * u * (3F - 2F * u);
                return k[j - 1] + (k[j + 1] - k[j - 1]) * u;
            }
        }
        return k[k.length - 1];
    }

    // "RELOAD 0:-90 240:90; INSPECT 0:-90" -> {RELOAD: [0,-90, 240,90], ...}; bad entries are skipped
    private static Map<String, float[]> parseKeys(String src) {
        Map<String, float[]> out = new HashMap<>();
        for (String anim : src.split(";")) {
            String[] w = anim.trim().split("\\s+");
            if (w.length < 2) continue;
            List<Float> v = new ArrayList<>();
            for (int j = 1; j < w.length; j++) {
                String[] tv = w[j].split(":");
                if (tv.length != 2) continue;
                try {
                    float time = Float.parseFloat(tv[0]), angle = Float.parseFloat(tv[1]);
                    if (!v.isEmpty() && time <= v.get(v.size() - 2)) continue;
                    v.add(time);
                    v.add(angle);
                } catch (NumberFormatException e) {
                    // skip
                }
            }
            if (v.isEmpty()) continue;
            float[] a = new float[v.size()];
            for (int j = 0; j < a.length; j++) a[j] = v.get(j);
            out.put(w[0].toUpperCase(java.util.Locale.ROOT), a);
        }
        return out;
    }

    // what a hand carries: a point moved by an NTM bus within a part, the hand on its `dir` side.
    // Several buses with a point each ("SHELL1|SHELL2", "x,y,z|x,y,z"): the hand takes the one whose bus
    // moves in the animation playing now (Liberator loads one of four shells), and keeps it while none moves.
    private record Carry(String part, String[] buses, int grab, float[][] ats, float[] dir, int[] active) {
        String bus() {
            return buses[active[0]];
        }

        float[] at() {
            return ats[Math.min(active[0], ats.length - 1)];
        }

        void pick() {
            if (buses.length < 2) return;
            HbmAnimations.Animation anim = HbmAnimations.getRelevantAnim(0);
            if (anim == null) return;
            for (int j = 0; j < buses.length; j++) {
                com.hbm.render.anim.BusAnimationSequence seq = anim.animation.getBus(buses[j]);
                if (seq != null && seq.getTotalTime() > 0) {
                    active[0] = j;
                    return;
                }
            }
        }
    }

    private static final Carry NO_CARRY = new Carry("", new String[] {""}, 0, new float[][] {new float[3]}, new float[] {0F, 0F, -1F}, new int[1]);
    private static final String[] carrySource = new String[2];
    private static final Carry[] carry = {NO_CARRY, NO_CARRY};

    // armRightCarry / armLeftCarry: "<part> <bus> <grab ms> x,y,z [dx,dy,dz]"
    private static Carry carry(int hand) {
        String src = hand == 0 ? Config.armRightCarry : Config.armLeftCarry;
        // a reload may carry differently (armRightCarryReload / armLeftCarryReload)
        String reload = hand == 0 ? Config.armRightCarryReload : Config.armLeftCarryReload;
        if (!reload.isEmpty()) {
            GunAnimation playing = HbmAnimations.getGunAnimation(0);
            if (playing == GunAnimation.RELOAD || playing == GunAnimation.RELOAD_CYCLE || playing == GunAnimation.RELOAD_END) src = reload;
        }
        if (!src.equals(carrySource[hand])) {
            carrySource[hand] = src;
            carry[hand] = NO_CARRY;
            String[] w = src.split("\\s+");
            if (w.length == 4 || w.length == 5) {
                try {
                    String[] pts = w[3].split("\\|");
                    float[][] at = new float[pts.length][];
                    for (int j = 0; j < pts.length; j++) at[j] = xyz(pts[j]);
                    float[] dir = w.length == 5 ? xyz(w[4]) : NO_CARRY.dir;
                    carry[hand] = new Carry(w[0], w[1].split("\\|"), Integer.parseInt(w[2]), at, dir, new int[1]);
                } catch (RuntimeException e) {
                    // malformed: no carrying
                }
            }
        }
        return carry[hand];
    }

    private static float[] xyz(String v) {
        String[] t = v.split(",");
        return new float[] {Float.parseFloat(t[0]), Float.parseFloat(t[1]), Float.parseFloat(t[2])};
    }

    private static final String[] carryKeysSource = new String[2];
    @SuppressWarnings("unchecked")
    private static final Map<String, List<float[]>>[] carryKeys = new Map[] {Map.of(), Map.of()};

    // Carry keys, per animation: "RELOAD 0:0 120:1 300:1/300 520:0 ; RELOAD_CYCLE ..." = ms:weight (0 on the gun,
    // 1 on the carried thing), optionally /b = where along the bus the thing is taken from (ms; walking b back
    // retraces its path), or @x,y,z = a fixed point instead of the bus. An animation name may be followed by
    // /<bus> to apply only to animations that have that bus (SPAS-12: RELOAD/PumpGrip = the empty reload).
    // Each entry: {t, w, kind (0 live, 1 bus time, 2 fixed point), b, x, y, z}
    // armLeftPath / armRightPath: one key, ms -> grip (part null) or a point of a part as drawn
    private record PathKey(float ms, String part, int nth, float[] at, float[] side, float[] shoulder) {
    }

    private static final String[] pathSource = new String[2];
    @SuppressWarnings("unchecked")
    private static final Map<String, List<PathKey>>[] pathKeys = new Map[] {Map.of(), Map.of()};

    // a key's time: milliseconds, or a name from armPathTimes with an optional +ms / -ms ("release+300")
    private static float pathMs(String e, Map<String, Float> times) {
        Float whole = times.get(e.toLowerCase(java.util.Locale.ROOT));
        if (whole != null) return whole;
        int op = Math.max(e.lastIndexOf('+'), e.lastIndexOf('-'));
        if (op <= 0 || Character.isDigit(e.charAt(0))) return Float.parseFloat(e);
        Float base = times.get(e.substring(0, op).trim().toLowerCase(java.util.Locale.ROOT));
        if (base == null) throw new IllegalArgumentException(e);
        return base + Float.parseFloat(e.substring(op));
    }

    private static Map<String, List<PathKey>> parsePath(String src, String timeSrc) {
        Map<String, Float> times = new HashMap<>();
        for (String t : timeSrc.split(",")) {
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            try {
                times.put(t.substring(0, eq).trim().toLowerCase(java.util.Locale.ROOT), Float.parseFloat(t.substring(eq + 1).trim()));
            } catch (NumberFormatException e) {
                // skip
            }
        }
        Map<String, List<PathKey>> out = new HashMap<>();
        for (String anim : src.split(";")) {
            String[] w = anim.trim().split("\\s+");
            if (w.length < 2) continue;
            List<PathKey> list = new ArrayList<>();
            for (int j = 1; j < w.length; j++) {
                try {
                    String e = w[j];
                    int colon = e.indexOf(':');
                    float ms = pathMs(e.substring(0, colon), times);
                    String t = e.substring(colon + 1);
                    PathKey k;
                    if (t.equalsIgnoreCase("grip")) {
                        k = new PathKey(ms, null, 0, null, null, null);
                    } else {
                        int at = t.indexOf('@');
                        String part = t.substring(0, at), pt = t.substring(at + 1);
                        int nth = 0;
                        int hash = part.indexOf('#');
                        if (hash >= 0) {
                            String n = part.substring(hash + 1);
                            nth = n.equalsIgnoreCase("last") ? -1 : Integer.parseInt(n);
                            part = part.substring(0, hash);
                        }
                        int gt = pt.indexOf('>');
                        float[] shoulder = gt < 0 ? new float[3] : xyz(pt.substring(gt + 1));
                        if (gt >= 0) pt = pt.substring(0, gt);
                        int sl = pt.indexOf('/');
                        float[] side = sl < 0 ? NO_CARRY.dir : xyz(pt.substring(sl + 1));
                        k = new PathKey(ms, part, nth, xyz(sl < 0 ? pt : pt.substring(0, sl)), side, shoulder);
                    }
                    list.add(k);
                } catch (RuntimeException e) {
                    // skip a malformed entry
                }
            }
            if (list.isEmpty()) continue;
            // in time order (a named time may put a key anywhere); of two keys at one time the first stays
            list.sort(java.util.Comparator.comparingDouble(PathKey::ms));
            for (int j = list.size() - 1; j > 0; j--) if (list.get(j).ms() <= list.get(j - 1).ms()) list.remove(j);
            int slash = w[0].indexOf('/');
            String name = slash < 0 ? w[0].toUpperCase(java.util.Locale.ROOT)
                    : w[0].substring(0, slash).toUpperCase(java.util.Locale.ROOT) + w[0].substring(slash);
            out.put(name, list);
        }
        return out;
    }

    // the part's pose this frame (in the gun's aligned frame), or null when it was not drawn
    private static Matrix4f partPose(String name, int nth) {
        int id = partByName(frameModel, name);
        if (id < 0) return null;
        int found = -1, n = 0;
        for (int k = 0; k < frameParts; k++) {
            if (FRAME_PART[k] != id) continue;
            if (nth < 0 || n == nth) found = k;
            if (n++ == nth) break;
        }
        return found < 0 ? null : new Matrix4f(FRAME_POSE[found]).mul(FROM_ALIGNED);
    }

    // {weight on the path (0 = at the grip), point x,y,z, side x,y,z, shoulder shift x,y,z} in camera space, or null off the path
    private static float[] pathState(int hand, Matrix4f cameraInv) {
        String src = hand == 0 ? Config.armRightPath : Config.armLeftPath;
        if (src.isEmpty()) return null;
        String srcKey = src + "\n" + Config.armPathTimes;
        if (!srcKey.equals(pathSource[hand])) {
            pathSource[hand] = srcKey;
            pathKeys[hand] = parsePath(src, Config.armPathTimes);
        }
        List<PathKey> k = forAnimation(pathKeys[hand]);
        if (k == null) return null;
        float t = animMillis();
        PathKey a = k.get(0), b = a;
        float u = 0F;
        if (t >= k.get(k.size() - 1).ms()) a = b = k.get(k.size() - 1);
        else if (t > a.ms()) {
            for (int j = 1; j < k.size(); j++) {
                if (t < k.get(j).ms()) {
                    a = k.get(j - 1);
                    b = k.get(j);
                    u = (t - a.ms()) / Math.max(b.ms() - a.ms(), 1F);
                    break;
                }
            }
        }
        float s = u * u * (3F - 2F * u);
        float[] pa = pathPoint(a, cameraInv), pb = pathPoint(b, cameraInv);
        if (pa == null && pb == null) return null;
        // the grip end has no shoulder shift: it fades in and out with the weight
        if (pa == null) return new float[] {s, pb[0], pb[1], pb[2], pb[3], pb[4], pb[5], pb[6] * s, pb[7] * s, pb[8] * s};
        if (pb == null) return new float[] {1F - s, pa[0], pa[1], pa[2], pa[3], pa[4], pa[5], pa[6] * (1F - s), pa[7] * (1F - s), pa[8] * (1F - s)};
        float[] out = new float[10];
        out[0] = 1F;
        for (int j = 0; j < 9; j++) out[j + 1] = pa[j] + (pb[j] - pa[j]) * s;
        return out;
    }

    // a key's point and side in camera space; null for grip (or a part not drawn)
    private static float[] pathPoint(PathKey key, Matrix4f cameraInv) {
        if (key.part() == null) return null;
        Matrix4f pose = partPose(key.part(), key.nth());
        if (pose == null) return null;
        Matrix4f within = new Matrix4f(cameraInv).mul(pose);
        Vector3f at = within.transformPosition(key.at()[0], key.at()[1], key.at()[2], new Vector3f());
        Vector3f d = new Vector3f(key.side());
        Vector3f side = within.transformDirection(new Vector3f(d)).normalize().mul(d.length());
        return new float[] {at.x, at.y, at.z, side.x, side.y, side.z, key.shoulder()[0], key.shoulder()[1], key.shoulder()[2]};
    }

    private static Map<String, List<float[]>> parseCarryKeys(String src) {
        Map<String, List<float[]>> out = new HashMap<>();
        for (String anim : src.split(";")) {
            String[] w = anim.trim().split("\\s+");
            if (w.length < 2) continue;
            List<float[]> list = new ArrayList<>();
            for (int j = 1; j < w.length; j++) {
                try {
                    String e = w[j];
                    float[] k = new float[7];
                    int colon = e.indexOf(':');
                    k[0] = Float.parseFloat(e.substring(0, colon));
                    String rest = e.substring(colon + 1);
                    int at = rest.indexOf('@'), sl = rest.indexOf('/');
                    if (at >= 0) {
                        float[] pt = xyz(rest.substring(at + 1));
                        k[2] = 2F;
                        k[4] = pt[0];
                        k[5] = pt[1];
                        k[6] = pt[2];
                        rest = rest.substring(0, at);
                    } else if (sl >= 0) {
                        k[2] = 1F;
                        k[3] = Float.parseFloat(rest.substring(sl + 1));
                        rest = rest.substring(0, sl);
                    }
                    k[1] = Float.parseFloat(rest);
                    if (!list.isEmpty() && k[0] <= list.get(list.size() - 1)[0]) continue;
                    list.add(k);
                } catch (RuntimeException e) {
                    // skip a malformed entry
                }
            }
            if (list.isEmpty()) continue;
            int slash = w[0].indexOf('/');
            String name = slash < 0 ? w[0].toUpperCase(java.util.Locale.ROOT)
                    : w[0].substring(0, slash).toUpperCase(java.util.Locale.ROOT) + w[0].substring(slash);
            out.put(name, list);
        }
        return out;
    }

    // keys for the animation playing now: "<TYPE>/<bus>" when the animation has that bus, else "<TYPE>"
    private static <T> T forAnimation(Map<String, T> byName) {
        HbmAnimations.Animation anim = HbmAnimations.getRelevantAnim(0);
        GunAnimation type = HbmAnimations.getGunAnimation(0);
        if (anim == null || type == null) return null;
        String prefix = type.name() + "/";
        for (Map.Entry<String, T> e : byName.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            String bus = e.getKey().substring(prefix.length());
            if (anim.animation.getBus(bus) != null) return e.getValue();
        }
        return byName.get(type.name());
    }

    // {weight, x, y, z}: how much the hand is on the carried thing now and where that is (aligned frame of the
    // carry part); null = the hand stays on the gun
    private static float[] carryState(int hand) {
        String src = hand == 0 ? Config.armRightCarryKeys : Config.armLeftCarryKeys;
        if (src.isEmpty() || carry(hand) == NO_CARRY) return null;
        carry(hand).pick();
        if (!src.equals(carryKeysSource[hand])) {
            carryKeysSource[hand] = src;
            carryKeys[hand] = parseCarryKeys(src);
        }
        List<float[]> k = forAnimation(carryKeys[hand]);
        if (k == null) return null;
        float t = animMillis();
        float[] a = k.get(0), b = a;
        float u = 0F;
        if (t >= k.get(k.size() - 1)[0]) a = b = k.get(k.size() - 1);
        else if (t > a[0]) {
            for (int j = 1; j < k.size(); j++) {
                if (t < k.get(j)[0]) {
                    a = k.get(j - 1);
                    b = k.get(j);
                    u = (t - a[0]) / Math.max(b[0] - a[0], 1F);
                    break;
                }
            }
        }
        float s = u * u * (3F - 2F * u);
        float w = a[1] + (b[1] - a[1]) * s;
        if (w <= 0F) return null;
        Carry c = carry(hand);
        Vector3f p;
        if (a[2] != 2F && b[2] != 2F) {
            float live = Math.max(t, c.grab);
            float ba = a[2] == 1F ? a[3] : live, bb = b[2] == 1F ? b[3] : live;
            p = busPoint(c, ba + (bb - ba) * u);
        } else {
            Vector3f pa = a[2] == 2F ? fixedPoint(c, a) : busPoint(c, a[2] == 1F ? a[3] : Math.max(t, c.grab));
            Vector3f pb = b[2] == 2F ? fixedPoint(c, b) : busPoint(c, b[2] == 1F ? b[3] : Math.max(t, c.grab));
            p = pa.lerp(pb, s);
        }
        return new float[] {Math.min(w, 1F), p.x, p.y, p.z};
    }

    // the carried point with the bus at time `ms`: the bus's location (model frame) plus the point
    private static Vector3f busPoint(Carry c, float ms) {
        float[] a = c.at();
        Vector3f p = new Vector3f(a[0], a[1], a[2]);
        HbmAnimations.Animation anim = HbmAnimations.getRelevantAnim(0);
        com.hbm.render.anim.BusAnimationSequence seq = anim == null ? null : anim.animation.getBus(c.bus());
        double[] t = seq == null ? null : seq.getTransformation((int) ms);
        if (t == null) return p;
        return p.add(geometry.toAligned.transform(new Vector3f((float) t[0], (float) t[1], (float) t[2])));
    }

    private static Vector3f fixedPoint(Carry c, float[] key) {
        float[] a = c.at();
        return new Vector3f(a[0], a[1], a[2]).add(geometry.toAligned.transform(new Vector3f(key[4], key[5], key[6])));
    }

    // progress (0..1) of the turn on a release bus (armReleaseLeft / armReleaseRight) while the hand is off the gun,
    // else 0; the turn is on whichever axis the bus rotates (Maresleg FLIP: x, Henry ROLL: z)
    private static float release(String bus) {
        if (bus.isEmpty() || HbmAnimations.getRelevantAnim(0) == null) return 0F;
        double[] t = HbmAnimations.getRelevantTransformation(bus);
        double angle = Math.max(Math.abs(t[0]), Math.max(Math.abs(t[1]), Math.abs(t[2])));
        double turns = angle / 360.0;
        float p = (float) (turns - Math.floor(turns));
        return p > 1e-3F && p < 1F - 1e-3F ? p : 0F;
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

    // The arm at rest, in camera space: from the hand under the grip point back along the configured
    // direction until depth `reach`. grip = point on the gun's underside; the arm's end face touches it there.
    private static void restArm(ArmRest rest, boolean right, boolean slim, Vector3f grip, Vector3f down, Vector3f center,
            Vector3f axisPoint, float[] dirCfg, float reach) {
        restArm(rest, right, slim, grip, down, center, axisPoint, dirCfg, reach, right ? Config.armScaleRight : Config.armScaleLeft);
    }

    // holdScale: armScaleRight/Left, the thickness of an arm holding the gun (1 for a hand carrying something)
    private static void restArm(ArmRest rest, boolean right, boolean slim, Vector3f grip, Vector3f down, Vector3f center,
            Vector3f axisPoint, float[] dirCfg, float reach, float holdScale) {
        Vector3f dir = new Vector3f(dirCfg[0], dirCfg[1], dirCfg[2]);
        if (dir.lengthSquared() < 1e-6F) {
            rest.valid = false;
            return;
        }
        dir.normalize();
        // run back from the hand until depth `reach`; at least one vanilla arm length
        float length = dir.z < -1e-3F ? (grip.z - reach) / -dir.z : 18F / 16F;
        length = Math.max(length, 18F / 16F);
        // thickness: fixed (armBaseRight/Left, the same on every gun) or grown with the arm's length
        float base = right ? Config.armBaseRight : Config.armBaseLeft;
        float s = (base > 0F ? base : Math.min(length / (18F / 16F), Config.armMaxScale)) * Config.armScale * holdScale;
        // Like TaCZ: an arm of fixed thickness keeps the vanilla proportions on every gun. It runs back to
        // the plane in front of the eye (armReach); a short one is the hand end of the arm, cut there
        // (local() draws fewer pixels instead of squashing it), a long one is not stretched past
        // armLength x the vanilla arm and ends before that plane. Animations may still stretch it (armStretch).
        if (base > 0F && Config.armLength > 0F) {
            float need = dir.z < -1e-3F ? (grip.z - reach) / -dir.z : 12F / 16F * s;
            length = Math.min(Math.max(need, 2F / 16F * s), 12F / 16F * s * Config.armLength);
        }
        // hand hangs under the grip by half its thickness so the arm does not cut into the gun
        Vector3f hand = new Vector3f(down).mul(2F / 16F * s).add(grip);
        rest.valid = true;
        rest.length = length;
        rest.s = s;
        rest.hand.set(hand);
        rest.shoulder.set(dir).mul(length).add(hand);
        rest.axis.set(axisPoint);
        rest.center.set(center);
        rest.local.set(local(hand, new Quaternionf(), dir, right, slim, s, length));
        rest.crop = lastCrop;
    }

    // pixels of the arm the last local() call drew from the hand (12 = all of it)
    private static int lastCrop = 12;

    // roll of the arm being drawn instead of armRollRight/Left, NaN = those (set while a hand carries shells)
    private static float rollNow = Float.NaN;

    // arm cube placed with its hand end at `hand`, built along `dir` (hand -> shoulder), then turned by `turn`
    private static Matrix4f local(Vector3f hand, Quaternionf turn, Vector3f dir, boolean right, boolean slim, float s, float length) {
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
        // shorter than the vanilla proportions: draw only the hand end, whole pixels of it, at the arm's own scale
        float px = length * 16F / Math.max(s, 1e-4F);
        lastCrop = 12;
        float sy = length / end;
        if (Config.armLength > 0F && px < 11.5F) {
            lastCrop = Math.max(1, Math.round(px));
            sy = length / (lastCrop / 16F);
        }
        return new Matrix4f()
                .translate(hand)
                .rotate(turn)
                .mul(basis)
                .rotateY((float) Math.toRadians(!Float.isNaN(rollNow) ? rollNow : right ? Config.armRollRight : Config.armRollLeft))
                .scale(s, sy, s)
                .translate(-cx, -end, 0F);
    }

    // An arm during an animation, in camera space. The arm cube runs from the shoulder end (y = 0) to the
    // hand end (y = 12/16) along +Y.
    private static void arm(SubmitNodeCollector collector, int light, ModelPart arm, Identifier skin, boolean slim,
            boolean right, Vector3f grip, float[][] path, float gripF, Matrix4f bodyInCamera, Vector3f down, Vector3f center, boolean spin, Quaternionf twist, float drop, Vector3f axisPoint, ArmRest rest,
            boolean sleeveShown, ModelPart sleeve) {
        Matrix4f m = pose(right, slim, grip, path, gripF, bodyInCamera, down, center, spin, twist, drop, rest);
        submit(collector, light, arm, skin, new Matrix4f(CAMERA).mul(m), lastCrop, right, slim, sleeveShown, sleeve);
    }

    // set while the left hand goes round a part: the arm keeps its rest length instead of stretching
    private static boolean keepLength;

    // between two arm poses: position, turn and size blended separately (w = 0 gives a, 1 gives b)
    private static Matrix4f blend(Matrix4f a, Matrix4f b, float w) {
        Vector3f t = a.getTranslation(new Vector3f()).lerp(b.getTranslation(new Vector3f()), w);
        Vector3f sc = a.getScale(new Vector3f()).lerp(b.getScale(new Vector3f()), w);
        Quaternionf q = a.getNormalizedRotation(new Quaternionf()).slerp(b.getNormalizedRotation(new Quaternionf()), w);
        return new Matrix4f().translation(t).rotate(q).scale(sc);
    }

    // armSwitchMs state per hand (0 = right, 1 = left): the animation drawn last, the arm drawn last, and the
    // pose a new animation starts from
    private static final String[] switchKey = new String[2];
    private static final Matrix4f[] switchLast = new Matrix4f[2], switchFrom = new Matrix4f[2];
    private static final int[] switchLastCrop = new int[2], switchFromCrop = new int[2];
    private static final long[] switchAt = new long[2];

    // /gunfeel armdebug: where the drawn arm lands on the screen, projected the way the hands are drawn
    // (70 deg hud fov as NTM adjusts it, the window's aspect). hand / cut = middle of the hand end / of the
    // cut (shoulder) end, screen fractions from the top left corner, "out" = outside the window or behind
    // the eye; vis = share of points over the arm's surface inside the window. Not hidden by the gun.
    private static String onScreen(Matrix4f m, int crop, boolean right, boolean slim) {
        var mc = Minecraft.getInstance();
        float fov = com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase.adjustHudFov(70F);
        float aspect = (float) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        Matrix4f clip = new Matrix4f().perspective((float) Math.toRadians(fov), aspect, 0.05F, 100F)
                .mul(new Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getModelViewStack())).mul(m);
        float cx = (right ? -1F : 1F) * (slim ? 5.5F : 6F) / 16F, half = (slim ? 1.5F : 2F) / 16F;
        float top = 12F / 16F, cut = (12F - crop) / 16F;
        int in = 0, all = 0;
        for (int k = 0; k <= 8; k++) {
            float y = cut + (top - cut) * k / 8F;
            for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue;
                all++;
                if (screenPoint(clip, cx + a * half, y, b * 2F / 16F) != null) in++;
            }
        }
        return "screen hand=" + screenText(clip, cx, top) + " cut=" + screenText(clip, cx, cut) + " vis=" + Math.round(100F * in / all) + "%";
    }

    // Sexy's bottle as NTM drew it last (armdebug only), and when
    private static final Matrix4f bottle = new Matrix4f();
    private static long bottleAt = -1000L;

    public static void bottleDrawn(Matrix4f pose) {
        bottle.set(pose);
        bottleAt = System.currentTimeMillis();
    }

    // the bottle's share on screen (whiskey model: x, z -0.5..0.5, y 0..3) and its middle, or "" when not drawn now
    private static String bottleOnScreen() {
        if (System.currentTimeMillis() - bottleAt > 100L) return "";
        var mc = Minecraft.getInstance();
        float fov = com.hbm.render.item.weapon.sedna.ItemRenderWeaponBase.adjustHudFov(70F);
        float aspect = (float) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        Matrix4f clip = new Matrix4f().perspective((float) Math.toRadians(fov), aspect, 0.05F, 100F)
                .mul(new Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getModelViewStack())).mul(bottle);
        int in = 0, all = 0;
        for (int k = 0; k <= 8; k++) {
            float y = 3F * k / 8F;
            for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
                if (a == 0 && b == 0) continue;
                all++;
                if (screenPoint(clip, a * 0.5F, y, b * 0.5F) != null) in++;
            }
        }
        return " bottle=" + screenText(clip, 0F, 1.5F) + " vis=" + Math.round(100F * in / all) + "%";
    }

    private static float[] screenPoint(Matrix4f clip, float x, float y, float z) {
        org.joml.Vector4f v = clip.transform(new org.joml.Vector4f(x, y, z, 1F));
        if (v.w <= 1e-4F) return null;
        float sx = (v.x / v.w + 1F) * 0.5F, sy = (1F - v.y / v.w) * 0.5F;
        return sx < 0F || sx > 1F || sy < 0F || sy > 1F ? null : new float[] {sx, sy};
    }

    private static String screenText(Matrix4f clip, float x, float y) {
        org.joml.Vector4f v = clip.transform(new org.joml.Vector4f(x, y, 0F, 1F));
        if (v.w <= 1e-4F) return "behind";
        float sx = (v.x / v.w + 1F) * 0.5F, sy = (1F - v.y / v.w) * 0.5F;
        boolean out = sx < 0F || sx > 1F || sy < 0F || sy > 1F;
        return String.format(java.util.Locale.ROOT, "(%.2f %.2f)%s", sx, sy, out ? "out" : "");
    }

    // pixels of arm shown by the last blendAtHand
    private static int blendCrop = 12;

    // Between two arm poses, turned and sized around the hand (the point the arm holds stays on the line
    // between the two hands); the shown length is blended too, the cut goes pixel by pixel.
    private static Matrix4f blendAtHand(Matrix4f a, int cropA, Matrix4f b, int cropB, float w, boolean right, boolean slim) {
        Vector3f pivot = new Vector3f((right ? -1F : 1F) * (slim ? 5.5F : 6F) / 16F, 12F / 16F, 0F);
        Vector3f h = a.transformPosition(pivot, new Vector3f()).lerp(b.transformPosition(pivot, new Vector3f()), w);
        Vector3f sa = a.getScale(new Vector3f()), sb = b.getScale(new Vector3f());
        float shown = sa.y * cropA + (sb.y * cropB - sa.y * cropA) * w;
        int crop = Math.clamp(Math.round(cropA + (cropB - cropA) * w), 1, 12);
        blendCrop = crop;
        Vector3f sc = new Vector3f(sa).lerp(sb, w);
        sc.y = shown / crop;
        // rotation(): JOML getNormalizedRotation is wrong for an arm scaled unevenly (length vs thickness)
        Quaternionf q = rotation(a).slerp(rotation(b), w);
        return new Matrix4f().translation(h).rotate(q).scale(sc).translate(new Vector3f(pivot).negate());
    }

    // The arm's pose in camera space (before CAMERA); lastCrop is set to the pixels of arm it shows.
    private static Matrix4f pose(boolean right, boolean slim, Vector3f grip, float[][] path, float gripF, Matrix4f bodyInCamera,
            Vector3f down, Vector3f center, boolean spin, Quaternionf twist, float drop, ArmRest rest) {
        if (spin) {
            // The rest arm, carried along with the held part and turned (by its share of the roll) around
            // that part's centre: a round forend turning inside the hand never leaves the palm.
            lastCrop = rest.crop;
            return new Matrix4f().translate(center).rotate(twist).translate(new Vector3f(rest.center).negate()).mul(rest.local);
        }
        float length = rest.length, s = rest.s;
        Vector3f off = new Vector3f(down).mul(2F / 16F * s);
        if (drop != 0F) {
            // inspect: lower the hand on screen (in arm pixels) as the gun rolls onto its side,
            // so less of the arm shows above the turned gun
            float angle = 2F * (float) Math.acos(Math.min(1F, Math.abs(twist.w)));
            off.y -= drop / 16F * s * (float) Math.abs(Math.sin(angle));
        }
        Vector3f restArm = new Vector3f(rest.shoulder).sub(rest.hand);
        // The shoulder stays where it is at rest, except that the gun's roll swings the arm around
        // the barrel axis as it is at rest. Other motion (lifting) is taken up by the hand sliding.
        Vector3f handAtGrip = new Vector3f(grip).add(off);
        Vector3f rolledHand = twist.transform(new Vector3f(rest.hand).sub(rest.axis)).add(rest.axis);
        Vector3f shoulder = twist.transform(new Vector3f(restArm)).add(rolledHand);
        // The arm may stretch a little; beyond that the hand slides along the gun's surface toward
        // the stock until the arm reaches it.
        float limit = length * (1F + Math.max(0F, Config.armStretch));
        Vector3f hand = handAtGrip;
        if (Config.armSlide && path != null && handAtGrip.distance(shoulder) > limit) {
            float lo = Math.min(Config.armSlideTo, gripF), hi = gripF;
            if (Config.armSlideNearest) {
                // armSlideNearest (per gun): the first point from the grip on that the arm reaches, stepping along the
                // gun; halving the whole stretch could land far from the grip when the distance is not monotonic
                // (Henry being lifted: the right hand jumped down the stock in one frame)
                // none reachable (the lifted Henry: the stock is no nearer the shoulder): the point nearest it, no halving
                float prev = hi, found = Float.NaN, nearest = hi, nearestD = Float.MAX_VALUE;
                for (int k = 1; k <= 64; k++) {
                    float f = hi + (lo - hi) * k / 64F;
                    float d = onPath(path, f, bodyInCamera, off).distance(shoulder);
                    if (d <= limit) {
                        found = f;
                        break;
                    }
                    if (d < nearestD) {
                        nearestD = d;
                        nearest = f;
                    }
                    prev = f;
                }
                if (Float.isNaN(found)) {
                    lo = hi = nearest;
                } else {
                    lo = found;
                    hi = prev;
                }
            }
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
        if (!keepLength) length = Math.max(reachNow, length);
        // arm orientation: the rest orientation rolled with the gun, then aimed at the new direction
        Vector3f restY = new Vector3f(restArm).negate().normalize();
        Vector3f rolledY = twist.transform(new Vector3f(restY));
        Vector3f newY = new Vector3f(hand).sub(new Vector3f(back).normalize().mul(length).add(hand)).normalize();
        Quaternionf turn = new Quaternionf().rotationTo(rolledY, newY).mul(twist).normalize();
        if (!Float.isFinite(turn.x + turn.y + turn.z + turn.w)) turn.identity();
        return local(hand, turn, new Vector3f(restY).negate(), right, slim, s, length);
    }

    private static void submit(SubmitNodeCollector collector, int light, ModelPart arm, Identifier skin, Matrix4f m,
            int crop, boolean right, boolean slim, boolean sleeveShown, ModelPart sleeve) {
        // armSwitchMs (per gun): a new animation (or its end) starts from where the arm was drawn last, over
        // this many ms. NTM's first frame of an animation comes ~50 ms in, keyed hands jumped there.
        int hand = right ? 0 : 1;
        if (Config.armSwitchMs > 0F) {
            HbmAnimations.Animation anim = HbmAnimations.getRelevantAnim(0);
            var player = Minecraft.getInstance().player;
            String item = player == null ? "" : String.valueOf(System.identityHashCode(player.getMainHandItem().getItem()));
            String key = item + "|" + (anim == null ? "none" : HbmAnimations.getGunAnimation(0) + "@" + anim.startMillis);
            // another gun: no blend from the last one's arm
            if (switchKey[hand] != null && !switchKey[hand].startsWith(item + "|")) switchLast[hand] = null;
            long now = com.hbm.util.GameTime.now();
            Matrix4f here = new Matrix4f(CAMERA).invert().mul(m);
            if (switchKey[hand] != null && !key.equals(switchKey[hand]) && switchLast[hand] != null) {
                switchFrom[hand] = new Matrix4f(switchLast[hand]);
                switchFromCrop[hand] = switchLastCrop[hand];
                switchAt[hand] = now;
            }
            switchKey[hand] = key;
            float u = switchFrom[hand] == null ? 1F : (now - switchAt[hand]) / Config.armSwitchMs;
            if (u < 1F) {
                u = Math.max(0F, u);
                float e = u * u * (3F - 2F * u);
                here = blendAtHand(switchFrom[hand], switchFromCrop[hand], here, crop, e, right, slim);
                crop = blendCrop;
                m = new Matrix4f(CAMERA).mul(here);
            } else {
                switchFrom[hand] = null;
            }
            switchLast[hand] = here;
            switchLastCrop[hand] = crop;
        }
        final int shown = crop;
        if (debugArms) {
            GunAnimation type = HbmAnimations.getGunAnimation(0);
            Vector3f origin = m.transformPosition(new Vector3f());
            Vector3f along = m.transformDirection(new Vector3f(0F, 1F, 0F));
            GunFeel.LOGGER.info("[armdebug] {} {} t={} {} | {} | origin={} up={} crop={} | {}", right ? "R" : "L",
                    HbmAnimations.getRelevantAnim(0) == null ? "-" : type, fmt(animMillis()), branch, right ? "" : branchNote,
                    fmt(origin), fmt(along), shown, onScreen(m, shown, right, slim) + (right ? "" : bottleOnScreen()));
            branchNote = "";
        }
        PoseStack pose = new PoseStack();
        pose.mulPose(m);
        if (shown < 12) {
            ModelPart[] cut = CUT.computeIfAbsent((right ? 1000 : 0) + (slim ? 100 : 0) + shown, k -> cutArm(right, slim, shown));
            arm = cut[0];
            sleeve = cut[1];
        }
        arm.resetPose();
        arm.visible = true;
        sleeve.visible = sleeveShown;
        collector.submitModelPart(arm, pose, RenderTypes.entityTranslucent(skin), light, OverlayTexture.NO_OVERLAY, null);
    }

    private static final Map<Integer, ModelPart[]> CUT = new HashMap<>();

    // The hand end of the player's arm (and sleeve): its last `n` of 12 pixels, textured as on the whole arm.
    // Same boxes, texture offsets and pivot as PlayerModel's arms; the sides take their texture from the hand
    // end (texture offset moved by 12 - n), the end caps from the usual place.
    private static ModelPart[] cutArm(boolean right, boolean slim, int n) {
        float w = slim ? 3F : 4F;
        float x0 = right ? (slim ? -2F : -3F) : -1F;
        float y0 = -2F + (12 - n);
        int au = right ? 40 : 32, av = right ? 16 : 48, su = right ? 40 : 48, sv = right ? 32 : 48;
        java.util.Set<net.minecraft.core.Direction> sides = java.util.EnumSet.of(net.minecraft.core.Direction.NORTH,
                net.minecraft.core.Direction.SOUTH, net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.WEST);
        java.util.Set<net.minecraft.core.Direction> caps = java.util.EnumSet.of(net.minecraft.core.Direction.UP, net.minecraft.core.Direction.DOWN);
        ModelPart sleeve = new ModelPart(List.of(
                new ModelPart.Cube(su, sv + 12 - n, x0, y0, -2F, w, n, 4F, 0.25F, 0.25F, 0.25F, false, 64F, 64F, sides),
                new ModelPart.Cube(su, sv, x0, y0, -2F, w, n, 4F, 0.25F, 0.25F, 0.25F, false, 64F, 64F, caps)), Map.of());
        ModelPart arm = new ModelPart(List.of(
                new ModelPart.Cube(au, av + 12 - n, x0, y0, -2F, w, n, 4F, 0F, 0F, 0F, false, 64F, 64F, sides),
                new ModelPart.Cube(au, av, x0, y0, -2F, w, n, 4F, 0F, 0F, 0F, false, 64F, 64F, caps)), Map.of("sleeve", sleeve));
        arm.setInitialPose(net.minecraft.client.model.geom.PartPose.offset(right ? -5F : 5F, 2F, 0F));
        sleeve.setInitialPose(net.minecraft.client.model.geom.PartPose.ZERO);
        return new ModelPart[] {arm, sleeve};
    }
}
