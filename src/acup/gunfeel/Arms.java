package acup.gunfeel;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;

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
// arms are the player's skinned arms stretched from shoulder points fixed to the camera. Arm
// orientation is built against the camera's up axis so arms don't twist when the view turns.
public final class Arms {
    private static final Map<Item, GunGeometry> GEOMETRY = new IdentityHashMap<>();
    private static final Map<Item, float[][]> GRIP_CACHE = new HashMap<>();
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
            gripVersion = Grips.version();
        }
        return GRIP_CACHE.computeIfAbsent(held, it -> {
            Grips.Grip g = Grips.get(BuiltInRegistries.ITEM.getKey(it));
            GunGeometry geo = geometry(it);
            return new float[][] {geo.grip(g.right()), geo.grip(g.left())};
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
        Matrix4f toCamera = new Matrix4f(CAMERA).invert();

        // Hands sit under the grip points: move them down (in the gun's frame) by half the arm's thickness.
        Matrix4f bodyInCamera = new Matrix4f(toCamera).mul(BODY);
        Vector3f down = bodyInCamera.transformDirection(new Vector3f(0F, -1F, 0F)).normalize(2F / 16F * Config.armScale);
        Vector3f right = bodyInCamera.transformPosition(grip[0][0], grip[0][1], grip[0][2], new Vector3f()).add(down);
        Vector3f left = bodyInCamera.transformPosition(grip[1][0], grip[1][1], grip[1][2], new Vector3f()).add(down);
        arm(collector, light, model.rightArm, model.rightSleeve, skin, slim, true, right, new Vector3f(Config.shoulderRight),
                player.isModelPartShown(PlayerModelPart.RIGHT_SLEEVE));
        arm(collector, light, model.leftArm, model.leftSleeve, skin, slim, false, left, new Vector3f(Config.shoulderLeft),
                player.isModelPartShown(PlayerModelPart.LEFT_SLEEVE));
    }

    // In camera space. The arm cube runs from the shoulder (y = 0) to the hand (y = 12/16) along +Y;
    // the palm centre is 2 px short of the end.
    private static void arm(SubmitNodeCollector collector, int light, ModelPart arm, ModelPart sleeve, Identifier skin,
            boolean slim, boolean right, Vector3f hand, Vector3f shoulder, boolean sleeveShown) {
        Vector3f y = new Vector3f(hand).sub(shoulder);
        float length = y.length();
        if (length < 1e-4F) return;
        y.div(length);
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
        float palm = 10F / 16F;
        float s = Config.armScale;
        Matrix4f m = new Matrix4f(CAMERA)
                .translate(hand)
                .mul(basis)
                .rotateY((float) Math.toRadians(right ? Config.armRoll : -Config.armRoll))
                .scale(s, length / palm, s)
                .translate(-cx, -palm, 0F);

        PoseStack pose = new PoseStack();
        pose.mulPose(m);
        arm.resetPose();
        arm.visible = true;
        sleeve.visible = sleeveShown;
        collector.submitModelPart(arm, pose, RenderTypes.entityTranslucent(skin), light, OverlayTexture.NO_OVERLAY, null);
    }
}
