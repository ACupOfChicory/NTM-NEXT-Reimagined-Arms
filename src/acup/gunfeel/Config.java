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
    // Gun model kick per shot, and how much of NTM's own RECOIL animation is kept.
    public static float modelKick = 1.0F;
    public static float modelRotate = 1.0F;
    public static float modelSpeed = 1.0F;
    public static float ntmRecoilAnim = 0.3F;
    public static boolean hideOffhand = true;
    public static boolean cornerHud = true;
    // Both-hands grip: player arms from fixed shoulder points (camera frame: +X left, +Y up, +Z forward).
    public static boolean arms = true;
    public static float armScale = 1.5F;
    public static float armRoll = 0F;
    public static float[] shoulderRight = {-1.6F, -2.4F, 0.4F};
    public static float[] shoulderLeft = {1.4F, -2.4F, 0.8F};

    private static final String HEADER = """
            NTM Gun Feel. Saved values are re-read while the game runs.
            hipX: gun X position in hip fire, 1 = NTM default, 0 = centre of the screen
            hipY: gun Y position in hip fire, 1 = NTM default
            hipZ: gun distance in hip fire, 1 = NTM default, less = closer and bigger
            cameraScale: camera kick, degrees per unit of NTM recoil (0.55: lever shotgun ~5.5 deg like TaCZ M870)
            cameraTime: duration of the camera kick, 1 = like M870
            cameraShake: camera roll shake on each shot, 0 = off
            modelKick: strength of the gun model kick (M870 shoot animation), 0 = off
            modelRotate: share of the rotation in the model kick (muzzle up, yaw, roll)
            modelSpeed: playback speed of the model kick, higher = shorter
            ntmRecoilAnim: share of NTM's own recoil animation kept, 1 = unchanged
            hideOffhand: hide the off-hand item while holding an NTM gun
            cornerHud: TaCZ-style ammo counter in the bottom-right corner instead of NTM's
            arms: draw the player's arms holding the gun with both hands
            armScale: arm thickness
            armRoll: turn of the arms around their own axis, degrees
            shoulderRight, shoulderLeft: where the arms come from: x (+ left), y (+ up), z (+ forward)
            Hand positions per gun: config/ntm-gunfeel-grips.properties or the /gunfeel grip command""";

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
            enabled = bool(p, "enabled", enabled);
            hipX = num(p, "hipX", hipX);
            hipY = num(p, "hipY", hipY);
            hipZ = num(p, "hipZ", hipZ);
            cameraScale = num(p, "cameraScale", cameraScale);
            cameraTime = Math.max(0.05F, num(p, "cameraTime", cameraTime));
            cameraShake = num(p, "cameraShake", cameraShake);
            modelKick = num(p, "modelKick", modelKick);
            modelRotate = num(p, "modelRotate", modelRotate);
            modelSpeed = Math.max(0.05F, num(p, "modelSpeed", modelSpeed));
            ntmRecoilAnim = num(p, "ntmRecoilAnim", ntmRecoilAnim);
            hideOffhand = bool(p, "hideOffhand", hideOffhand);
            cornerHud = bool(p, "cornerHud", cornerHud);
            arms = bool(p, "arms", arms);
            armScale = num(p, "armScale", armScale);
            armRoll = num(p, "armRoll", armRoll);
            shoulderRight = vec(p, "shoulderRight", shoulderRight);
            shoulderLeft = vec(p, "shoulderLeft", shoulderLeft);
            GunFeel.LOGGER.info("Loaded {}", file);
        } catch (IOException | RuntimeException e) {
            GunFeel.LOGGER.warn("Could not read {}", file, e);
        }
    }

    private static void save() throws IOException {
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
        p.setProperty("modelKick", Float.toString(modelKick));
        p.setProperty("modelRotate", Float.toString(modelRotate));
        p.setProperty("modelSpeed", Float.toString(modelSpeed));
        p.setProperty("ntmRecoilAnim", Float.toString(ntmRecoilAnim));
        p.setProperty("hideOffhand", Boolean.toString(hideOffhand));
        p.setProperty("cornerHud", Boolean.toString(cornerHud));
        p.setProperty("arms", Boolean.toString(arms));
        p.setProperty("armScale", Float.toString(armScale));
        p.setProperty("armRoll", Float.toString(armRoll));
        p.setProperty("shoulderRight", shoulderRight[0] + "," + shoulderRight[1] + "," + shoulderRight[2]);
        p.setProperty("shoulderLeft", shoulderLeft[0] + "," + shoulderLeft[1] + "," + shoulderLeft[2]);
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
