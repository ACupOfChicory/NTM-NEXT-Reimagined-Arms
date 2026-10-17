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
    // [2]: the part the right hand carries things within (armRightCarry)
    private static final Matrix4f[] FOLLOW = {new Matrix4f(), new Matrix4f(), new Matrix4f()};
    private static final boolean[] HAVE_FOLLOW = new boolean[3];
    private static final int[] FOLLOW_ID = {-1, -1, -1};

    // the parts NTM drew this frame (of the first model it drew) and where
    private static final int MAX_PARTS = 128;
    private static final int[] FRAME_PART = new int[MAX_PARTS];
    private static final Matrix4f[] FRAME_POSE = new Matrix4f[MAX_PARTS];
    private static int frameParts;
    private static HFRWavefrontObject frameModel;

    private static Item item;
    private static GunGeometry geometry;
    private static boolean capturing, haveCamera;

    private Arms() {
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
        HAVE_FOLLOW[0] = HAVE_FOLLOW[1] = HAVE_FOLLOW[2] = false;
        FOLLOW_ID[0] = FOLLOW_ID[1] = FOLLOW_ID[2] = -1;
        capturing = true;
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
            FOLLOW_ID[2] = partByName(model, carry().part);
        }
        if (model != frameModel) return;
        if (frameParts < MAX_PARTS) {
            if (FRAME_POSE[frameParts] == null) FRAME_POSE[frameParts] = new Matrix4f();
            FRAME_POSE[frameParts].set(pose.last().pose());
            FRAME_PART[frameParts++] = part;
        }
        for (int i = 0; i < 3; i++) {
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
        BODY.mul(fromAligned);
        for (int i = 0; i < 3; i++) if (HAVE_FOLLOW[i]) FOLLOW[i].mul(fromAligned);
        REL.set(KICKED).invert().mul(BODY);
        haveRel = true;
        relItem = item;
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
                submit(collector, light, arm, skin, new Matrix4f(CAMERA).mul(r.local), sleeveShown, sleeve);
                continue;
            }
            // The left hand lets go while the gun spins (sawn-off Maresleg cocking): it keeps its rest pose,
            // moves aside and back, and takes the gun again when the turn is complete.
            float release = right ? 0F : release();
            if (release > 0F) {
                float[] m = Config.armReleaseMove;
                float k = (0.5F - 0.5F * (float) Math.cos(2.0 * Math.PI * release)) / 16F * r.s;
                Matrix4f moved = new Matrix4f().translate(m[0] * k, m[1] * k, m[2] * k).mul(r.local);
                submit(collector, light, arm, skin, new Matrix4f(CAMERA).mul(moved), sleeveShown, sleeve);
                continue;
            }
            // a hand on a moving part (a pump) goes along with that part
            Matrix4f holder = HAVE_FOLLOW[i] ? new Matrix4f(cameraInv).mul(FOLLOW[i]) : bodyInCamera;
            // scripted: the left hand goes around the part it holds (over a break-action's barrels to open them)
            float around = right ? Float.NaN : leftAngle();
            if (!Float.isNaN(around)) {
                float[] sec = geometry.sectionAt(grip[i][2]);
                if (sec != null) {
                    float a = (float) Math.toRadians(around), c = (float) Math.cos(a), sn = (float) Math.sin(a);
                    float cx = (sec[0] + sec[1]) * 0.5F, cy = (sec[2] + sec[3]) * 0.5F;
                    Vector3f g = holder.transformPosition(cx + (sec[1] - sec[0]) * 0.5F * c, cy + (sec[3] - sec[2]) * 0.5F * sn, grip[i][2], new Vector3f());
                    Vector3f out = holder.transformDirection(new Vector3f(c, sn, 0F)).normalize();
                    // back under the gun (e.g. as it is flicked up to close) the hand slides along it like at rest
                    boolean under = Math.abs(around + 90F) < 0.5F;
                    arm(collector, light, arm, skin, slim, right, g, under ? profiles(item)[i] : null, geometry.fraction(grip[i][2]), holder, out, g, false,
                            new Quaternionf(), 0F, axisPoint, r, sleeveShown, sleeve);
                    continue;
                }
            }
            Vector3f g = holder.transformPosition(grip[i][0], grip[i][1], grip[i][2], new Vector3f());
            float[][] path = profiles(item)[i];
            Vector3f center = holder.transformPosition(grip[i + 2][0], grip[i + 2][1], grip[i + 2][2], new Vector3f());
            // while firing/cycling the gun turns inside the loosely holding support hand
            boolean spin = !right && !inspect;
            float gripF = geometry.fraction(grip[i][2]);
            // How much of the gun's roll this arm turns with. The hand itself always stays on the gun's
            // surface; a hand that follows less of the roll shows the gun turning inside it.
            Quaternionf own = new Quaternionf().slerp(twist, right ? followRight : followLeft).normalize();
            if (!Float.isFinite(own.x + own.y + own.z + own.w)) own.identity();
            // the right hand leaves the grip to carry something (new shells into the chambers) and comes back
            float carry = right ? keyValue(Config.armRightCarryKeys, 1) : Float.NaN;
            if (carry > 0F && HAVE_FOLLOW[2]) {
                Matrix4f within = new Matrix4f(cameraInv).mul(FOLLOW[2]);
                Vector3f at = within.transformPosition(carryPoint());
                Vector3f behind = within.transformDirection(new Vector3f(0F, 0F, -1F)).normalize();
                Vector3f p = new Vector3f(g).lerp(at, carry);
                Vector3f off = new Vector3f(down).lerp(behind, carry);
                if (off.lengthSquared() < 1e-6F) off.set(behind);
                arm(collector, light, arm, skin, slim, right, p, null, gripF, bodyInCamera, off.normalize(), p, false, own, 0F, axisPoint, r, sleeveShown, sleeve);
                continue;
            }
            arm(collector, light, arm, skin, slim, right, g, path, gripF, bodyInCamera, down, center, spin, own,
                    inspect && !right ? Config.armInspectDrop : 0F, axisPoint, r, sleeveShown, sleeve);
        }
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

    private record Carry(String part, String bus, int grab, float[] at) {
    }

    private static String carrySource;
    private static Carry carry = new Carry("", "", 0, new float[3]);

    // armRightCarry: "<part> <bus> <grab ms> x,y,z"
    private static Carry carry() {
        if (!Config.armRightCarry.equals(carrySource)) {
            carrySource = Config.armRightCarry;
            carry = new Carry("", "", 0, new float[3]);
            String[] w = carrySource.split("\\s+");
            if (w.length == 4) {
                try {
                    String[] xyz = w[3].split(",");
                    carry = new Carry(w[0], w[1], Integer.parseInt(w[2]),
                            new float[] {Float.parseFloat(xyz[0]), Float.parseFloat(xyz[1]), Float.parseFloat(xyz[2])});
                } catch (RuntimeException e) {
                    // malformed: no carrying
                }
            }
        }
        return carry;
    }

    // The carried point within its part (aligned frame): the bus moves it; before the grab time the hand
    // reaches for where it will be at that time instead of following what the bus does earlier.
    private static Vector3f carryPoint() {
        Carry c = carry();
        Vector3f p = new Vector3f(c.at[0], c.at[1], c.at[2]);
        HbmAnimations.Animation anim = HbmAnimations.getRelevantAnim(0);
        if (anim == null) return p;
        com.hbm.render.anim.BusAnimationSequence seq = anim.animation.getBus(c.bus);
        double[] t = seq == null ? null : seq.getTransformation((int) Math.max(animMillis(), c.grab));
        if (t == null) return p;
        return p.add(geometry.toAligned.transform(new Vector3f((float) t[0], (float) t[1], (float) t[2])));
    }

    // progress (0..1) of the turn on the armReleaseLeft bus while the left hand is off the gun, else 0
    private static float release() {
        if (Config.armReleaseLeft.isEmpty() || HbmAnimations.getRelevantAnim(0) == null) return 0F;
        double turns = Math.abs(HbmAnimations.getRelevantTransformation(Config.armReleaseLeft)[0]) / 360.0;
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
        Vector3f dir = new Vector3f(dirCfg[0], dirCfg[1], dirCfg[2]);
        if (dir.lengthSquared() < 1e-6F) {
            rest.valid = false;
            return;
        }
        dir.normalize();
        // run back from the hand until depth `reach`; at least one vanilla arm length
        float length = dir.z < -1e-3F ? (grip.z - reach) / -dir.z : 18F / 16F;
        length = Math.max(length, 18F / 16F);
        float s = Math.min(length / (18F / 16F), Config.armMaxScale) * Config.armScale;
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
    }

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
        return new Matrix4f()
                .translate(hand)
                .rotate(turn)
                .mul(basis)
                .rotateY((float) Math.toRadians(right ? Config.armRollRight : Config.armRollLeft))
                .scale(s, length / end, s)
                .translate(-cx, -end, 0F);
    }

    // An arm during an animation, in camera space. The arm cube runs from the shoulder end (y = 0) to the
    // hand end (y = 12/16) along +Y.
    private static void arm(SubmitNodeCollector collector, int light, ModelPart arm, Identifier skin, boolean slim,
            boolean right, Vector3f grip, float[][] path, float gripF, Matrix4f bodyInCamera, Vector3f down, Vector3f center, boolean spin, Quaternionf twist, float drop, Vector3f axisPoint, ArmRest rest,
            boolean sleeveShown, ModelPart sleeve) {
        if (spin) {
            // The rest arm, carried along with the held part and turned (by its share of the roll) around
            // that part's centre: a round forend turning inside the hand never leaves the palm.
            Matrix4f moved = new Matrix4f().translate(center).rotate(twist).translate(new Vector3f(rest.center).negate()).mul(rest.local);
            submit(collector, light, arm, skin, new Matrix4f(CAMERA).mul(moved), sleeveShown, sleeve);
            return;
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
        length = Math.max(reachNow, length);
        // arm orientation: the rest orientation rolled with the gun, then aimed at the new direction
        Vector3f restY = new Vector3f(restArm).negate().normalize();
        Vector3f rolledY = twist.transform(new Vector3f(restY));
        Vector3f newY = new Vector3f(hand).sub(new Vector3f(back).normalize().mul(length).add(hand)).normalize();
        Quaternionf turn = new Quaternionf().rotationTo(rolledY, newY).mul(twist).normalize();
        if (!Float.isFinite(turn.x + turn.y + turn.z + turn.w)) turn.identity();
        submit(collector, light, arm, skin, new Matrix4f(CAMERA).mul(local(hand, turn, new Vector3f(restY).negate(), right, slim, s, length)), sleeveShown, sleeve);
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
