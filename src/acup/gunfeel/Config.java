package acup.gunfeel;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import net.neoforged.fml.loading.FMLPaths;

// config/ntm-gunfeel.properties, re-read whenever the file changes so values can be tuned in game.
public final class Config {
    public static boolean enabled = true;
    // Hip-fire X position of the gun relative to NTM's: 1 = NTM default, 0 = screen centre.
    public static float hipX = 0.6F;
    // Hip-fire Y position relative to NTM's (1 = unchanged).
    public static float hipY = 1.0F;
    public static float hipZ = 0.75F;
    // Camera recoil: degrees per unit of NTM recoil, curve duration, roll shake.
    public static float cameraScale = 0.55F;
    public static float cameraTime = 1.0F;
    public static float cameraShake = 1.0F;
    // recoil curves per gun, "t:v t:v ..." in seconds; empty = the built-in TaCZ M870 shapes (Curve.java)
    public static String cameraPitch = "", cameraYaw = "", cameraRoll = "";
    public static String kickPitch = "", kickYaw = "", kickRoll = "", kickPosY = "", kickPosZ = "";
    // Gun model kick per shot, and how much of NTM's own RECOIL animation is kept.
    public static float modelKick = 1.0F;
    public static float modelRotate = 1.0F;
    public static float modelSpeed = 1.0F;
    public static float ntmRecoilAnim = 0.3F;
    public static boolean hideOffhand = true;
    public static boolean cornerHud = true;
    // Both-hands grip. Each arm runs from its hand back along a fixed direction (camera frame: +X left,
    // +Y up, +Z forward) until it reaches the depth armReach*; the directions follow how a modern
    // shooter's arms come into view (right arm back past the cheek, left arm up from below).
    // Thickness keeps a vanilla arm's 4:18 proportion to the arm's length, so a far hand gets a
    // proportionally thicker arm, the way an arm looks when it is closer to the eye.
    public static boolean arms = true;
    public static float armScale = 0.8F;
    public static float armMaxScale = 4.0F;
    public static float armRollRight = -5F;
    public static float armRollLeft = -55F;
    public static float[] armDirRight = {-0.21F, 0.13F, -1.09F};
    public static float[] armDirLeft = {0.29F, -0.53F, -0.95F};
    public static float armReachRight = -0.81F;
    public static float armReachLeft = 0.41F;
    // How much the arms turn with the gun when an animation (inspect, reload) turns it away from rest.
    public static float armFollow = 1.0F;
    // Support (left) hand roll while firing/cycling; the gun turns inside the loose hand.
    public static float armFollowSupport = 0.25F;
    // During animations the hands slide along the gun toward the stock instead of the arms stretching.
    public static boolean armSlide = true;
    public static float armSlideTo = 0.05F;
    public static float armStretch = 0.1F;
    public static float armInspectDrop = 1.0F;
    // Model part a hand moves with (a pump, a lever), by its name in the model; empty = the gun's body.
    public static String armPartRight = "";
    public static String armPartLeft = "";
    // NTM animation bus (an angle, like Maresleg's FLIP) during which the left hand lets go of the gun
    public static String armReleaseLeft = "";
    public static float[] armReleaseMove = {4F, 0F, 0F};
    // the same for the right hand; it stays where it was by default (Henry inspect: the gun spins away from it)
    public static String armReleaseRight = "";
    public static float[] armReleaseMoveRight = {0F, 0F, 0F};
    // left hand moves around the held part in an animation: "RELOAD 0:-90 240:90 ...; INSPECT ..."
    public static String armLeftKeys = "";
    // right hand carries something NTM animates (Old Classic's new shells): "<part> <bus> <grab ms> x,y,z"
    public static String armRightCarry = "";
    public static String armRightCarryKeys = "";
    public static String armLeftCarry = "";
    public static String armLeftCarryKeys = "";
    // arm thickness before armScale, the same on every gun (Maresleg / Old Classic); 0 = grows with the arm's length
    public static float armBaseRight = 1.70F;
    public static float armBaseLeft = 3.02F;
    // thickness of an arm loading shells (armRightCarry / armLeftCarry), on top of armScale
    public static float armLoadScaleRight = 1F;
    public static float armLoadScaleLeft = 1F;
    // longest arm, in vanilla arms of its thickness (1 = never longer than the vanilla 4:12 arm); shorter arms are
    // cut, not squashed; 0 = the old way, stretched or squashed to reach armReach
    public static float armLength = 1.0F;
    // turn of an arm around its own axis while it loads shells, degrees; NaN = the same as armRollRight/Left
    public static float armLoadRollRight = Float.NaN;
    public static float armLoadRollLeft = Float.NaN;
    // draw NTM's muzzle smoke rising up the screen whatever frame the renderer gives it (SPAS-12 sends it at the camera)
    public static boolean smokeUpright = false;
    public static boolean armAroundSmooth = false;
    public static float armAroundTopDrop = 0F;
    public static float armAroundShift = 0F;
    public static float armCarryTurnRight = 0F;
    public static float armCarryTurnLeft = 0F;
    public static float armAroundShiftReload = Float.NaN;
    public static float armAroundTopDropReload = Float.NaN;
    // width of NTM's muzzle smoke, 1 = as NTM draws it (SPAS-12's is 3x as wide as Maresleg's)
    public static float smokeWidth = 1F;
    public static float armScaleRight = 1F;
    public static float armScaleLeft = 1F;

    private static final String HEADER = """
            NTM Gun Feel. Saved values are re-read while the game runs.
            hipX: gun X position in hip fire, 1 = NTM default, 0 = centre of the screen
            hipY: gun Y position in hip fire, 1 = NTM default
            hipZ: gun distance in hip fire, 1 = NTM default, less = closer and bigger
            cameraScale: camera kick, degrees per unit of NTM recoil (0.55: lever shotgun ~5.5 deg like TaCZ M870)
            cameraTime: duration of the camera kick, 1 = like M870
            cameraShake: camera roll shake on each shot, 0 = off
            cameraPitch, cameraYaw, cameraRoll: camera recoil curves "t:v t:v ..." (seconds:value), empty = TaCZ M870;
              pitch and yaw peak at 1 (times NTM recoil x cameraScale), roll in degrees (times cameraShake)
            kickPitch, kickYaw, kickRoll, kickPosY, kickPosZ: gun model kick curves, same format, empty = TaCZ M870;
              degrees and 1/16 gun-length units as in a TaCZ shoot animation (pitch = -root rotation x), end on 0
            modelKick: strength of the gun model kick (M870 shoot animation), 0 = off
            modelRotate: share of the rotation in the model kick (muzzle up, yaw, roll)
            modelSpeed: playback speed of the model kick, higher = shorter
            ntmRecoilAnim: share of NTM's own recoil animation kept, 1 = unchanged
            hideOffhand: hide the off-hand item while holding an NTM gun
            cornerHud: TaCZ-style ammo counter in the bottom-right corner instead of NTM's
            arms: draw the player's arms holding the gun with both hands
            armScale: arm thickness on top of the automatic one, 1 = vanilla arm proportions
            armMaxScale: upper limit of the automatic thickness
            armBaseRight, armBaseLeft: arm thickness before armScale, the same on every gun (as on Maresleg); 0 = automatic (grows with the arm's length)
            armLength: longest arm in vanilla arms of its thickness (1 = never stretched); a shorter arm is cut at armReach, not squashed; 0 = stretched/squashed to armReach
            armLoadScaleRight, armLoadScaleLeft: thickness of each arm while it loads shells into the gun, on top of armScale, 1 = unchanged
            armLoadRollRight, armLoadRollLeft: turn of each arm around its own axis while it loads shells, degrees, NaN = armRollRight/Left
            armAroundTopDrop: while armLeftKeys holds the left hand over the part (90), lower it toward the part by this many arm pixels; none at the side
            armAroundShift: while armLeftKeys moves the left hand round a part, move it left (out of the part) by this many arm pixels; none under the part (-90)
            armAroundShiftReload, armAroundTopDropReload: the same during reloads, NaN = armAroundShift / armAroundTopDrop
            armCarryTurnRight, armCarryTurnLeft: while a hand carries a shell, turn the arm around that point about the shell's long axis, degrees, + = the shoulder end to the right
            armAroundSmooth: while armLeftKeys moves the left hand round a part, keep the arm's length and blend into the usual hold below -45 (Liberator)
            smokeWidth: width of the muzzle smoke, 1 = as NTM draws it
            smokeUpright: draw the muzzle smoke rising up the screen, for guns whose smoke goes the wrong way (SPAS-12)
            armScaleRight, armScaleLeft: thickness of each arm holding the gun on top of armScale (not while carrying shells), 1 = unchanged
            armRollRight, armRollLeft: turn of each arm around its own axis, degrees
            armDirRight, armDirLeft: direction from the hand to the shoulder: x (+ left), y (+ up), z (+ forward)
            armReachRight, armReachLeft: the arm goes back until this distance in front of the eye (- = behind)
            armFollow: how much the arms roll with the gun in animations (inspect etc.), 0 = not at all, 1 = fully
            armFollowSupport: same for the left hand while firing and cycling (the gun turns inside the hand)
            armSlide: in animations the hands slide along the gun instead of the arms stretching
            armSlideTo: how far toward the stock a hand may slide, 0 = stock end, 1 = muzzle
            armInspectDrop: in inspect animations, how far the left hand drops on screen when the gun lies on its side, arm pixels
            armStretch: how much an arm may stretch in animations before the hand starts sliding, 0.1 = 10%
            armPartRight, armPartLeft: model part the hand moves with (a pump: SPAS-12 PumpGrip), empty = the gun's body
            armReleaseLeft: NTM animation bus (a turn angle) during which the left hand lets go (sawn-off Maresleg: FLIP), empty = never
            armReleaseMove: where the released left hand moves and back, arm pixels: x (+ left), y (+ up), z (+ forward)
            armReleaseRight, armReleaseMoveRight: the same for the right hand (default move 0,0,0 = it stays where it was)
            armLeftKeys: the left hand goes around the part it holds during NTM animations, per animation:
              "RELOAD 0:-90 240:90 1250:90 1500:-90; INSPECT ..." = milliseconds:angle, angle around the part
              (-90 under, 0 the gun's left side, 90 on top, 180 right side); empty = the hand stays put
            armRightCarry, armLeftCarry: the hand carries something an NTM animation bus moves, "<part> <bus> <grab ms> x,y,z [dx,dy,dz]":
              the bus moves it within that part, the hand holds point x,y,z from side dx,dy,dz (default 0,0,-1 = behind;
              the side's length is the distance in half arm widths, 1 = the point on the hand's surface),
              and before the grab time it reaches for where the thing will be then (Old Classic: "BarrelShort SHELLS 900 0,0,-1.75")
              several buses and points split by | ("Barrel SHELL1|SHELL2 0 x,y,z|x,y,z"): the one whose bus moves now (Liberator)
            armRightCarryKeys, armLeftCarryKeys: how much the hand is on the carried thing, per animation:
              "RELOAD 650:0 900:1 1300:1 1500:0" = ms:weight; ms:weight/b takes the thing where the bus has it at b ms
              (walking b back retraces its path); ms:weight@x,y,z = a fixed spot instead; RELOAD/<bus> = only when that bus plays
            Every value except enabled can be overridden per gun in config/ntm-gunfeel-guns.properties
            (hbm\\:gun_maresleg.armRollLeft = -55) or with /gunfeel set <key> <value> for the held gun.
            Hand positions per gun: <gun>.right / <gun>.left there, or /gunfeel grip""";

    private static final Properties GLOBAL = new Properties();
    private static final Properties BUILTIN = snapshot();
    private static String selected;
    private static int selectedVersion = -1;
    private static Path file;
    private static long stamp = Long.MIN_VALUE;
    private static long nextCheck;

    private Config() {
    }

    public static void poll() {
        long now = System.currentTimeMillis();
        if (now < nextCheck) return;
        nextCheck = now + 1000;
        Grips.poll();
        try {
            if (file == null) file = FMLPaths.CONFIGDIR.get().resolve("ntm-gunfeel.properties");
            if (!Files.exists(file)) {
                save();
            }
            long modified = Files.getLastModifiedTime(file).toMillis();
            if (modified == stamp) return;
            stamp = modified;
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(file)) {
                p.load(r);
            }
            GLOBAL.clear();
            GLOBAL.putAll(p);
            selected = null;
            GunFeel.LOGGER.info("Loaded {}", file);
        } catch (IOException | RuntimeException e) {
            GunFeel.LOGGER.warn("Could not read {}", file, e);
        }
    }

    // Values in effect for one gun: built-in defaults, then the global file, then the gun's overrides.
    // Called every frame with the held gun; re-applies only when something changed.
    public static void select(String gun) {
        int v = Grips.version();
        if (java.util.Objects.equals(gun, selected) && v == selectedVersion) return;
        selected = gun;
        selectedVersion = v;
        Properties p = new Properties();
        p.putAll(BUILTIN);
        p.putAll(GLOBAL);
        boolean on = bool(p, "enabled", true);
        if (gun != null) p.putAll(Grips.overrides(gun));
        apply(p);
        enabled = on;
    }

    public static boolean isKey(String key) {
        return BUILTIN.containsKey(key);
    }

    public static java.util.Set<String> keys() {
        return new java.util.TreeSet<>(BUILTIN.stringPropertyNames());
    }

    public static String globalValue(String key) {
        return GLOBAL.getProperty(key, BUILTIN.getProperty(key));
    }

    private static void apply(Properties p) {
        enabled = bool(p, "enabled", enabled);
        hipX = num(p, "hipX", hipX);
        hipY = num(p, "hipY", hipY);
        hipZ = num(p, "hipZ", hipZ);
        cameraScale = num(p, "cameraScale", cameraScale);
        cameraTime = Math.max(0.05F, num(p, "cameraTime", cameraTime));
        cameraShake = num(p, "cameraShake", cameraShake);
        cameraPitch = p.getProperty("cameraPitch", cameraPitch).trim();
        cameraYaw = p.getProperty("cameraYaw", cameraYaw).trim();
        cameraRoll = p.getProperty("cameraRoll", cameraRoll).trim();
        kickPitch = p.getProperty("kickPitch", kickPitch).trim();
        kickYaw = p.getProperty("kickYaw", kickYaw).trim();
        kickRoll = p.getProperty("kickRoll", kickRoll).trim();
        kickPosY = p.getProperty("kickPosY", kickPosY).trim();
        kickPosZ = p.getProperty("kickPosZ", kickPosZ).trim();
        modelKick = num(p, "modelKick", modelKick);
        modelRotate = num(p, "modelRotate", modelRotate);
        modelSpeed = Math.max(0.05F, num(p, "modelSpeed", modelSpeed));
        ntmRecoilAnim = num(p, "ntmRecoilAnim", ntmRecoilAnim);
        hideOffhand = bool(p, "hideOffhand", hideOffhand);
        cornerHud = bool(p, "cornerHud", cornerHud);
        arms = bool(p, "arms", arms);
        armScale = num(p, "armScale", armScale);
        armRollRight = num(p, "armRollRight", armRollRight);
        armRollLeft = num(p, "armRollLeft", armRollLeft);
        armMaxScale = num(p, "armMaxScale", armMaxScale);
        armDirRight = vec(p, "armDirRight", armDirRight);
        armDirLeft = vec(p, "armDirLeft", armDirLeft);
        armReachRight = num(p, "armReachRight", armReachRight);
        armReachLeft = num(p, "armReachLeft", armReachLeft);
        armFollow = num(p, "armFollow", armFollow);
        armFollowSupport = num(p, "armFollowSupport", armFollowSupport);
        armSlide = bool(p, "armSlide", armSlide);
        armSlideTo = num(p, "armSlideTo", armSlideTo);
        armStretch = num(p, "armStretch", armStretch);
        armInspectDrop = num(p, "armInspectDrop", armInspectDrop);
        armPartRight = p.getProperty("armPartRight", armPartRight).trim();
        armPartLeft = p.getProperty("armPartLeft", armPartLeft).trim();
        armReleaseLeft = p.getProperty("armReleaseLeft", armReleaseLeft).trim();
        armReleaseMove = vec(p, "armReleaseMove", armReleaseMove);
        armReleaseRight = p.getProperty("armReleaseRight", armReleaseRight).trim();
        armReleaseMoveRight = vec(p, "armReleaseMoveRight", armReleaseMoveRight);
        armLeftKeys = p.getProperty("armLeftKeys", armLeftKeys).trim();
        armRightCarry = p.getProperty("armRightCarry", armRightCarry).trim();
        armRightCarryKeys = p.getProperty("armRightCarryKeys", armRightCarryKeys).trim();
        armLeftCarry = p.getProperty("armLeftCarry", armLeftCarry).trim();
        armLeftCarryKeys = p.getProperty("armLeftCarryKeys", armLeftCarryKeys).trim();
        armBaseRight = num(p, "armBaseRight", armBaseRight);
        armBaseLeft = num(p, "armBaseLeft", armBaseLeft);
        armLoadScaleRight = num(p, "armLoadScaleRight", armLoadScaleRight);
        armLoadScaleLeft = num(p, "armLoadScaleLeft", armLoadScaleLeft);
        armLength = num(p, "armLength", armLength);
        armLoadRollRight = num(p, "armLoadRollRight", armLoadRollRight);
        armLoadRollLeft = num(p, "armLoadRollLeft", armLoadRollLeft);
        armAroundSmooth = bool(p, "armAroundSmooth", armAroundSmooth);
        armAroundTopDrop = num(p, "armAroundTopDrop", armAroundTopDrop);
        armAroundShift = num(p, "armAroundShift", armAroundShift);
        armCarryTurnRight = num(p, "armCarryTurnRight", armCarryTurnRight);
        armCarryTurnLeft = num(p, "armCarryTurnLeft", armCarryTurnLeft);
        armAroundShiftReload = num(p, "armAroundShiftReload", armAroundShiftReload);
        armAroundTopDropReload = num(p, "armAroundTopDropReload", armAroundTopDropReload);
        smokeUpright = Boolean.parseBoolean(p.getProperty("smokeUpright", Boolean.toString(smokeUpright)).trim());
        smokeWidth = num(p, "smokeWidth", smokeWidth);
        armScaleRight = num(p, "armScaleRight", armScaleRight);
        armScaleLeft = num(p, "armScaleLeft", armScaleLeft);
    }

    private static Properties snapshot() {
        Properties p = new Properties() {
            // keep a stable, readable order
            @Override
            public synchronized java.util.Set<java.util.Map.Entry<Object, Object>> entrySet() {
                java.util.Set<java.util.Map.Entry<Object, Object>> s = new java.util.LinkedHashSet<>();
                super.entrySet().stream().sorted(java.util.Comparator.comparing(e -> e.getKey().toString())).forEach(s::add);
                return s;
            }
        };
        p.setProperty("enabled", Boolean.toString(enabled));
        p.setProperty("hipX", Float.toString(hipX));
        p.setProperty("hipY", Float.toString(hipY));
        p.setProperty("hipZ", Float.toString(hipZ));
        p.setProperty("cameraScale", Float.toString(cameraScale));
        p.setProperty("cameraTime", Float.toString(cameraTime));
        p.setProperty("cameraShake", Float.toString(cameraShake));
        p.setProperty("cameraPitch", cameraPitch);
        p.setProperty("cameraYaw", cameraYaw);
        p.setProperty("cameraRoll", cameraRoll);
        p.setProperty("kickPitch", kickPitch);
        p.setProperty("kickYaw", kickYaw);
        p.setProperty("kickRoll", kickRoll);
        p.setProperty("kickPosY", kickPosY);
        p.setProperty("kickPosZ", kickPosZ);
        p.setProperty("modelKick", Float.toString(modelKick));
        p.setProperty("modelRotate", Float.toString(modelRotate));
        p.setProperty("modelSpeed", Float.toString(modelSpeed));
        p.setProperty("ntmRecoilAnim", Float.toString(ntmRecoilAnim));
        p.setProperty("hideOffhand", Boolean.toString(hideOffhand));
        p.setProperty("cornerHud", Boolean.toString(cornerHud));
        p.setProperty("arms", Boolean.toString(arms));
        p.setProperty("armScale", Float.toString(armScale));
        p.setProperty("armRollRight", Float.toString(armRollRight));
        p.setProperty("armRollLeft", Float.toString(armRollLeft));
        p.setProperty("armMaxScale", Float.toString(armMaxScale));
        p.setProperty("armDirRight", armDirRight[0] + "," + armDirRight[1] + "," + armDirRight[2]);
        p.setProperty("armDirLeft", armDirLeft[0] + "," + armDirLeft[1] + "," + armDirLeft[2]);
        p.setProperty("armReachRight", Float.toString(armReachRight));
        p.setProperty("armReachLeft", Float.toString(armReachLeft));
        p.setProperty("armFollow", Float.toString(armFollow));
        p.setProperty("armFollowSupport", Float.toString(armFollowSupport));
        p.setProperty("armSlide", Boolean.toString(armSlide));
        p.setProperty("armSlideTo", Float.toString(armSlideTo));
        p.setProperty("armStretch", Float.toString(armStretch));
        p.setProperty("armInspectDrop", Float.toString(armInspectDrop));
        p.setProperty("armPartRight", armPartRight);
        p.setProperty("armPartLeft", armPartLeft);
        p.setProperty("armReleaseLeft", armReleaseLeft);
        p.setProperty("armReleaseMove", armReleaseMove[0] + "," + armReleaseMove[1] + "," + armReleaseMove[2]);
        p.setProperty("armReleaseRight", armReleaseRight);
        p.setProperty("armReleaseMoveRight", armReleaseMoveRight[0] + "," + armReleaseMoveRight[1] + "," + armReleaseMoveRight[2]);
        p.setProperty("armLeftKeys", armLeftKeys);
        p.setProperty("armRightCarry", armRightCarry);
        p.setProperty("armRightCarryKeys", armRightCarryKeys);
        p.setProperty("armLeftCarry", armLeftCarry);
        p.setProperty("armLeftCarryKeys", armLeftCarryKeys);
        p.setProperty("armBaseRight", Float.toString(armBaseRight));
        p.setProperty("armBaseLeft", Float.toString(armBaseLeft));
        p.setProperty("armLoadScaleRight", Float.toString(armLoadScaleRight));
        p.setProperty("armLoadScaleLeft", Float.toString(armLoadScaleLeft));
        p.setProperty("armLength", Float.toString(armLength));
        p.setProperty("armLoadRollRight", Float.toString(armLoadRollRight));
        p.setProperty("armLoadRollLeft", Float.toString(armLoadRollLeft));
        p.setProperty("armAroundSmooth", Boolean.toString(armAroundSmooth));
        p.setProperty("armAroundTopDrop", Float.toString(armAroundTopDrop));
        p.setProperty("armAroundShift", Float.toString(armAroundShift));
        p.setProperty("armCarryTurnRight", Float.toString(armCarryTurnRight));
        p.setProperty("armCarryTurnLeft", Float.toString(armCarryTurnLeft));
        p.setProperty("armAroundShiftReload", Float.toString(armAroundShiftReload));
        p.setProperty("armAroundTopDropReload", Float.toString(armAroundTopDropReload));
        p.setProperty("smokeUpright", Boolean.toString(smokeUpright));
        p.setProperty("smokeWidth", Float.toString(smokeWidth));
        p.setProperty("armScaleRight", Float.toString(armScaleRight));
        p.setProperty("armScaleLeft", Float.toString(armScaleLeft));
        return p;
    }

    private static void save() throws IOException {
        Properties p = snapshot();
        try (Writer w = Files.newBufferedWriter(file)) {
            p.store(w, HEADER);
        }
    }

    private static float num(Properties p, String key, float def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static float[] vec(Properties p, String key, float[] def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        String[] parts = v.split(",");
        if (parts.length != 3) return def;
        try {
            return new float[] {Float.parseFloat(parts[0].trim()), Float.parseFloat(parts[1].trim()), Float.parseFloat(parts[2].trim())};
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static boolean bool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }
}
