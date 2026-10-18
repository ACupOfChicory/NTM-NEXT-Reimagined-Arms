package acup.gunfeel;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import net.neoforged.fml.loading.FMLPaths;

// Server side: how long before the end of a reload / an inspect the held aim comes back, per gun, in seconds.
// config/ntm-gunfeel-server.properties: "hbm\:gun_double_barrel.reaimInspect = 0.6"; set with /gunaim.
public final class ReaimTimes {
    public static final float DEFAULT = 0.3F;
    private static final String FILE = "ntm-gunfeel-server.properties";
    private static final Map<String, String> VALUES = new TreeMap<>();
    // the times shipped in the mod jar (ntmgunfeel-server.properties); the server's file wins over them
    private static final Map<String, String> BUILTIN = builtin();

    private static Map<String, String> builtin() {
        Map<String, String> out = new TreeMap<>();
        try (var in = ReaimTimes.class.getResourceAsStream("/ntmgunfeel-server.properties")) {
            if (in == null) return out;
            Properties p = new Properties();
            p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            for (String key : p.stringPropertyNames()) out.put(key, p.getProperty(key).trim());
        } catch (java.io.IOException | RuntimeException e) {
            // no built-in times
        }
        return out;
    }
    private static long stamp = Long.MIN_VALUE;
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("ntm-gunfeel");

    private ReaimTimes() {
    }

    // kind: "reload" or "inspect"
    public static float seconds(String gun, String kind) {
        poll();
        String key = gun + ".reaim" + cap(kind);
        String v = VALUES.get(key);
        if (v == null) v = BUILTIN.get(key);
        if (v == null) return DEFAULT;
        try {
            return Math.max(0F, Float.parseFloat(v.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT;
        }
    }

    private static long lastPoll;
    private static final Map<String, Float> NUMBERS = new java.util.HashMap<>();
    private static long numbersStamp = Long.MIN_VALUE;

    // a plain server setting (bulletSpeed = 1.6); read often, so the file is checked at most once a second
    public static float number(String key, float def) {
        long now = System.currentTimeMillis();
        if (now - lastPoll > 1000L) {
            lastPoll = now;
            poll();
        }
        if (numbersStamp != stamp) {
            numbersStamp = stamp;
            NUMBERS.clear();
        }
        Float cached = NUMBERS.get(key);
        if (cached != null) return cached;
        String v = VALUES.get(key);
        if (v == null) v = BUILTIN.get(key);
        float out = def;
        if (v != null) {
            try {
                out = Float.parseFloat(v.trim());
            } catch (NumberFormatException e) {
                // keep the default
            }
        }
        NUMBERS.put(key, out);
        return out;
    }

    public static int ticks(String gun, String kind) {
        return Math.round(seconds(gun, kind) * 20F);
    }

    public static void set(String gun, String kind, float seconds) {
        poll();
        VALUES.put(gun + ".reaim" + cap(kind), Float.toString(seconds));
        save();
    }

    private static String cap(String kind) {
        return Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve(FILE);
    }

    private static void poll() {
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
            for (String key : p.stringPropertyNames()) VALUES.put(key, p.getProperty(key));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read {}", FILE, e);
        }
    }

    private static void save() {
        try {
            Path f = file();
            try (Writer w = Files.newBufferedWriter(f)) {
                w.write("# NTM Gun Feel, server side. With the aim key held, the aim comes back this many seconds before\n");
                w.write("# a reload / an inspect ends: <gun id>.reaimReload, <gun id>.reaimInspect (default " + DEFAULT + "). /gunaim sets them.\n");
                for (Map.Entry<String, String> e : VALUES.entrySet()) w.write(e.getKey().replace(":", "\\:") + " = " + e.getValue() + "\n");
            }
            stamp = Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            LOGGER.warn("Could not write {}", FILE, e);
        }
    }
}
