package acup.gunfeel;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import net.minecraft.resources.Identifier;
import net.neoforged.fml.loading.FMLPaths;

// Per-gun settings, config/ntm-gunfeel-guns.properties, keys "<gun id>.<setting>":
//   hbm\:gun_maresleg.right = 0.5,0.05,0.30      hand positions (see below)
//   hbm\:gun_maresleg.armRollLeft = -55          any setting from ntm-gunfeel.properties
// Hand positions: z = fraction of the gun length, stock (0) to muzzle (1); x and y = fractions of the
// model's cross-section at that z (y 0 = underside), see GunGeometry.grip.
//   hbm\:gun_g3.rightAt = 0,-1.8,-4.6              or a point in model units (x left, y up, z to the muzzle),
// which does not move when attachments change the model's size; it wins over .right/.left.
public final class Grips {
    // rightAt / leftAt: point in model units or null
    public record Grip(float[] right, float[] left, float[] rightAt, float[] leftAt) {
    }

    public static final Grip DEFAULT = new Grip(new float[] {0.5F, 0F, 0.33F}, new float[] {0.5F, 0F, 0.70F}, null, null);
    private static final String FILE = "ntm-gunfeel-guns.properties";
    private static final String OLD_FILE = "ntm-gunfeel-grips.properties";
    // what this gun's settings are: the ones shipped in the jar (BUILTIN), with the player's file (USER) on top.
    // The file keeps only what differs from the jar; "" there hides a shipped value.
    private static final Map<String, String> BUILTIN = builtin();
    private static final Map<String, String> USER = new HashMap<>();
    private static final Map<String, String> VALUES = new HashMap<>(BUILTIN);
    private static Path file;
    private static long stamp = Long.MIN_VALUE;
    private static int version;

    public static int version() {
        return version;
    }

    private Grips() {
    }

    public static Grip get(Identifier id) {
        return get(id, "");
    }

    // A variant of the gun (missing = the model parts it does not draw, e.g. "-Barrel" for a sawn-off) may
    // have its own hand positions: "<gun id>-Barrel.leftAt"; each hand falls back to the gun's own.
    public static Grip get(Identifier id, String missing) {
        String base = canon(id.toString()), own = base + missing;
        Grip b = read(base);
        if (missing.isEmpty()) return b;
        Grip v = read(own);
        boolean vr = VALUES.containsKey(own + ".right") || VALUES.containsKey(own + ".rightAt");
        boolean vl = VALUES.containsKey(own + ".left") || VALUES.containsKey(own + ".leftAt");
        return new Grip(vr ? v.right() : b.right(), vl ? v.left() : b.left(), vr ? v.rightAt() : b.rightAt(), vl ? v.leftAt() : b.leftAt());
    }

    private static Grip read(String key) {
        float[] r = vec(VALUES.get(key + ".right")), l = vec(VALUES.get(key + ".left"));
        return new Grip(r != null ? r : DEFAULT.right(), l != null ? l : DEFAULT.left(), vec(VALUES.get(key + ".rightAt")),
                vec(VALUES.get(key + ".leftAt")));
    }

    // settings overridden for this gun (without hand positions)
    // A gun with sameAs takes the other gun's settings; its own entries (other than sameAs) win over them.
    public static Map<String, String> overrides(String gun) {
        Map<String, String> out = new TreeMap<>();
        String target = canon(gun);
        collect(target, out);
        if (!target.equals(gun)) collect(gun, out);
        out.remove("sameAs");
        return out;
    }

    private static void collect(String gun, Map<String, String> out) {
        String prefix = gun + ".";
        for (Map.Entry<String, String> e : VALUES.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            String k = e.getKey().substring(prefix.length());
            if (k.equals("right") || k.equals("left") || k.equals("rightAt") || k.equals("leftAt") || e.getValue().isEmpty()) continue;
            out.put(k, e.getValue());
        }
    }

    // this gun's value shipped in the jar (its own, else the sameAs gun's); null = none
    public static String shipped(String gun, String key) {
        String v = BUILTIN.get(gun + "." + key);
        if (v == null || v.isEmpty()) v = BUILTIN.get(canon(gun) + "." + key);
        return v == null || v.isEmpty() ? null : v;
    }

    // this gun's value from the player's file, "" = hides the shipped one; null = none
    public static String own(String gun, String key) {
        String v = USER.get(gun + "." + key);
        return v != null ? v : USER.get(canon(gun) + "." + key);
    }

    public static void set(Identifier id, boolean right, float x, float y, float z) {
        set(id.toString(), right, x, y, z);
    }

    // gun = "<gun id>" or "<gun id><missing parts>" for a variant (a sawn-off: "hbm:gun_maresleg-Stock-Barrel")
    public static void set(String gun, boolean right, float x, float y, float z) {
        gun = canon(gun);
        // a point in model units would win over these fractions: hide it
        String at = gun + (right ? ".rightAt" : ".leftAt");
        if (BUILTIN.containsKey(at)) USER.put(at, "");
        else USER.remove(at);
        put(gun, right ? "right" : "left", x + "," + y + "," + z);
    }

    // a point in model units for one hand (x left, y up, z to the muzzle)
    public static void setAt(String gun, boolean right, float x, float y, float z) {
        gun = canon(gun);
        put(gun, right ? "rightAt" : "leftAt", x + "," + y + "," + z);
    }

    // value null = back to the jar's value (or none)
    public static void put(String gun, String key, String value) {
        // a setting the gun has of its own stays its own; anything else goes to the gun it is the same as
        if (!key.equals("sameAs") && !VALUES.containsKey(gun + "." + key)) gun = canon(gun);
        String k = gun + "." + key;
        if (value == null || value.equals(BUILTIN.get(k))) USER.remove(k);
        else USER.put(k, value);
        merge();
        save();
    }

    public static void reset(Identifier id) {
        reset(id.toString());
    }

    // hand positions back to the jar's (or computed from the model when the jar has none)
    public static void reset(String id) {
        id = canon(id);
        USER.remove(id + ".right");
        USER.remove(id + ".left");
        USER.remove(id + ".rightAt");
        USER.remove(id + ".leftAt");
        merge();
        save();
    }

    // "<gun id>.sameAs = <other gun id>": a gun with the same model (Sacred Dragon = Old Classic) reads and
    // writes all its settings under the other gun. A variant suffix ("-Barrel") is kept.
    static String canon(String gun) {
        int dash = gun.indexOf('-');
        String base = dash < 0 ? gun : gun.substring(0, dash), rest = dash < 0 ? "" : gun.substring(dash);
        String to = VALUES.get(base + ".sameAs");
        return to == null || to.isEmpty() ? gun : to + rest;
    }

    private static void merge() {
        VALUES.clear();
        VALUES.putAll(BUILTIN);
        VALUES.putAll(USER);
        version++;
    }

    private static Map<String, String> builtin() {
        Map<String, String> out = new HashMap<>();
        try (var in = Grips.class.getResourceAsStream("/ntmgunfeel-guns.properties")) {
            if (in == null) return out;
            Properties p = new Properties();
            p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            for (String key : p.stringPropertyNames()) out.put(key, p.getProperty(key).trim());
        } catch (IOException | RuntimeException e) {
            GunFeel.LOGGER.warn("Could not read the built-in gun settings", e);
        }
        return out;
    }

    private static float[] vec(String v) {
        if (v == null) return null;
        String[] parts = v.split(",");
        if (parts.length != 3) return null;
        try {
            return new float[] {Float.parseFloat(parts[0].trim()), Float.parseFloat(parts[1].trim()), Float.parseFloat(parts[2].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Path file() throws IOException {
        if (file == null) {
            Path dir = FMLPaths.CONFIGDIR.get();
            file = dir.resolve(FILE);
            Path old = dir.resolve(OLD_FILE);
            if (!Files.exists(file) && Files.exists(old)) Files.move(old, file);
        }
        return file;
    }

    static void poll() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            long modified = Files.getLastModifiedTime(f).toMillis();
            if (modified == stamp) return;
            stamp = modified;
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(f)) {
                p.load(r);
            }
            USER.clear();
            for (String key : p.stringPropertyNames()) {
                String v = p.getProperty(key).trim();
                // a value the jar already has is not the player's own
                if (!v.equals(BUILTIN.get(key))) USER.put(key, v);
            }
            merge();
        } catch (IOException | RuntimeException e) {
            GunFeel.LOGGER.warn("Could not read {}", file, e);
        }
    }

    private static void save() {
        try {
            Path f = file();
            try (Writer w = Files.newBufferedWriter(f)) {
                w.write("# NTM-NEXT Reimagined Arms: your own settings per gun, on top of the ones in the mod jar\n");
                w.write("# <gun id>.<setting> = value (any key from ntm-gunfeel.properties); an empty value hides the jar's one\n");
                w.write("# hand positions: <gun id>.right / .left = x,y,z  z stock(0)-muzzle(1); x, y across the model there, y 0 = underside\n");
                w.write("# default right = 0.5,0,0.33  left = 0.5,0,0.70 (the hand sits under that point)\n");
                w.write("# or .rightAt / .leftAt = x,y,z in model units (x left, y up, z to the muzzle); wins over .right / .left\n");
                for (Map.Entry<String, String> e : new TreeMap<>(USER).entrySet()) {
                    w.write(e.getKey().replace(":", "\\:") + " = " + e.getValue() + "\n");
                }
            }
            stamp = Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            GunFeel.LOGGER.warn("Could not write {}", file, e);
        }
    }
}
