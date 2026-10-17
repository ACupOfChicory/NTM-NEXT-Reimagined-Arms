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
public final class Grips {
    public record Grip(float[] right, float[] left) {
    }

    public static final Grip DEFAULT = new Grip(new float[] {0.5F, 0F, 0.33F}, new float[] {0.5F, 0F, 0.70F});
    private static final String FILE = "ntm-gunfeel-guns.properties";
    private static final String OLD_FILE = "ntm-gunfeel-grips.properties";
    private static final Map<String, String> VALUES = new HashMap<>();
    private static Path file;
    private static long stamp = Long.MIN_VALUE;
    private static int version;

    public static int version() {
        return version;
    }

    private Grips() {
    }

    public static Grip get(Identifier id) {
        String key = id.toString();
        float[] r = vec(VALUES.get(key + ".right")), l = vec(VALUES.get(key + ".left"));
        return new Grip(r != null ? r : DEFAULT.right(), l != null ? l : DEFAULT.left());
    }

    // settings overridden for this gun (without hand positions)
    public static Map<String, String> overrides(String gun) {
        Map<String, String> out = new TreeMap<>();
        String prefix = gun + ".";
        for (Map.Entry<String, String> e : VALUES.entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            String k = e.getKey().substring(prefix.length());
            if (k.equals("right") || k.equals("left")) continue;
            out.put(k, e.getValue());
        }
        return out;
    }

    public static void set(Identifier id, boolean right, float x, float y, float z) {
        put(id.toString(), right ? "right" : "left", x + "," + y + "," + z);
    }

    public static void put(String gun, String key, String value) {
        if (value == null) VALUES.remove(gun + "." + key);
        else VALUES.put(gun + "." + key, value);
        version++;
        save();
    }

    public static void reset(Identifier id) {
        VALUES.remove(id + ".right");
        VALUES.remove(id + ".left");
        version++;
        save();
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
            VALUES.clear();
            for (String key : p.stringPropertyNames()) VALUES.put(key, p.getProperty(key).trim());
            version++;
        } catch (IOException | RuntimeException e) {
            GunFeel.LOGGER.warn("Could not read {}", file, e);
        }
    }

    private static void save() {
        try {
            Path f = file();
            try (Writer w = Files.newBufferedWriter(f)) {
                w.write("# NTM Gun Feel settings per gun: <gun id>.<setting> = value (any key from ntm-gunfeel.properties)\n");
                w.write("# hand positions: <gun id>.right / .left = x,y,z  z stock(0)-muzzle(1); x, y across the model there, y 0 = underside\n");
                w.write("# default right = 0.5,0,0.33  left = 0.5,0,0.70 (the hand sits under that point)\n");
                for (Map.Entry<String, String> e : new TreeMap<>(VALUES).entrySet()) {
                    w.write(e.getKey().replace(":", "\\:") + " = " + e.getValue() + "\n");
                }
            }
            stamp = Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            GunFeel.LOGGER.warn("Could not write {}", file, e);
        }
    }
}
