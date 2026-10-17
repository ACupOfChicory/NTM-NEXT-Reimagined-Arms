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

// Per-gun hand positions, config/ntm-gunfeel-grips.properties:
//   hbm\:gun_maresleg.right = 0.5,0.05,0.30
// z = fraction of the gun length, stock (0) to muzzle (1); x and y = fractions of the model's
// cross-section at that z (y 0 = underside), see GunGeometry.grip.
public final class Grips {
    public record Grip(float[] right, float[] left) {
    }

    public static final Grip DEFAULT = new Grip(new float[] {0.5F, 0F, 0.33F}, new float[] {0.5F, 0F, 0.70F});
    private static final Map<String, float[]> VALUES = new HashMap<>();
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
        float[] r = VALUES.get(key + ".right"), l = VALUES.get(key + ".left");
        return new Grip(r != null ? r : DEFAULT.right(), l != null ? l : DEFAULT.left());
    }

    public static void set(Identifier id, boolean right, float x, float y, float z) {
        VALUES.put(id + (right ? ".right" : ".left"), new float[] {x, y, z});
        version++;
        save();
    }

    public static void reset(Identifier id) {
        VALUES.remove(id + ".right");
        VALUES.remove(id + ".left");
        version++;
        save();
    }

    static void poll() {
        try {
            if (file == null) file = FMLPaths.CONFIGDIR.get().resolve("ntm-gunfeel-grips.properties");
            if (!Files.exists(file)) return;
            long modified = Files.getLastModifiedTime(file).toMillis();
            if (modified == stamp) return;
            stamp = modified;
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(file)) {
                p.load(r);
            }
            VALUES.clear();
            version++;
            for (String key : p.stringPropertyNames()) {
                String[] parts = p.getProperty(key).split(",");
                if (parts.length != 3) continue;
                try {
                    VALUES.put(key, new float[] {Float.parseFloat(parts[0].trim()), Float.parseFloat(parts[1].trim()),
                            Float.parseFloat(parts[2].trim())});
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException | RuntimeException e) {
            GunFeel.LOGGER.warn("Could not read {}", file, e);
        }
    }

    private static void save() {
        try {
            if (file == null) file = FMLPaths.CONFIGDIR.get().resolve("ntm-gunfeel-grips.properties");
            try (Writer w = Files.newBufferedWriter(file)) {
                w.write("# NTM Gun Feel hand positions per gun: z stock(0)-muzzle(1); x, y across the model there, y 0 = underside\n");
                w.write("# default right = 0.5,0,0.33  left = 0.5,0,0.70 (the hand sits under that point)\n");
                for (Map.Entry<String, float[]> e : new TreeMap<>(VALUES).entrySet()) {
                    float[] v = e.getValue();
                    w.write(e.getKey().replace(":", "\\:") + " = " + v[0] + "," + v[1] + "," + v[2] + "\n");
                }
            }
            stamp = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            GunFeel.LOGGER.warn("Could not write {}", file, e);
        }
    }
}
