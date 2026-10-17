package acup.gunfeel;

// Keyframe curve with Catmull-Rom interpolation, as used by TaCZ/Bedrock animations.
public final class Curve {
    private final float[] t, v;

    // pairs: time, value, time, value, ...
    public Curve(float... pairs) {
        int n = pairs.length / 2;
        t = new float[n];
        v = new float[n];
        for (int i = 0; i < n; i++) {
            t[i] = pairs[2 * i];
            v[i] = pairs[2 * i + 1];
        }
    }

    private static final java.util.Map<String, Curve> PARSED = new java.util.concurrent.ConcurrentHashMap<>();

    // "t:v t:v ..." (seconds:value) from a config key; empty or malformed = the built-in curve
    public static Curve of(String src, Curve builtin) {
        if (src == null || src.isBlank()) return builtin;
        return PARSED.computeIfAbsent(src, k -> {
            try {
                String[] w = k.trim().split("\\s+");
                float[] pairs = new float[w.length * 2];
                for (int i = 0; i < w.length; i++) {
                    String[] tv = w[i].split(":");
                    pairs[2 * i] = Float.parseFloat(tv[0]);
                    pairs[2 * i + 1] = Float.parseFloat(tv[1]);
                    if (i > 0 && pairs[2 * i] <= pairs[2 * i - 2]) return builtin;
                }
                return w.length >= 2 ? new Curve(pairs) : builtin;
            } catch (RuntimeException e) {
                return builtin;
            }
        });
    }

    public float length() {
        return t[t.length - 1];
    }

    public float at(float time) {
        if (time <= t[0]) return v[0];
        int n = t.length;
        if (time >= t[n - 1]) return v[n - 1];
        int i = 0;
        while (time > t[i + 1]) i++;
        float u = (time - t[i]) / (t[i + 1] - t[i]);
        float p0 = v[Math.max(0, i - 1)], p1 = v[i], p2 = v[i + 1], p3 = v[Math.min(n - 1, i + 2)];
        float u2 = u * u, u3 = u2 * u;
        return 0.5F * (2F * p1 + (-p0 + p2) * u + (2F * p0 - 5F * p1 + 4F * p2 - p3) * u2 + (-p0 + 3F * p1 - 3F * p2 + p3) * u3);
    }

    // Recoil shapes tuned by feel, with a modern shotgun (TaCZ's M870) as the reference for how a
    // heavy shot should feel: a sharp muzzle rise with a twist, a short hold, a smooth settle.
    // pitch + = muzzle up, z + = toward the player, y + = up; positions in 1/16 of a gun-length unit.
    public static final Curve PITCH = new Curve(0F, 0F, .04F, 2F, .075F, 10.8F, .12F, 12.3F, .22F, 9.4F, .32F, 6.6F,
            .45F, .6F, .6F, -.4F, .8F, .1F, 1F, 0F);
    public static final Curve YAW = new Curve(0F, 0F, .035F, 2.8F, .07F, 5.1F, .14F, 3.3F, .25F, 2.2F, .35F, 1.6F,
            .48F, 0F, .65F, .25F, 1F, 0F);
    public static final Curve ROLL = new Curve(0F, 0F, .03F, -6.4F, .07F, 9.8F, .13F, -1.5F, .21F, -3.4F, .31F, -4.6F,
            .46F, .2F, .64F, .45F, .85F, -.35F, 1F, 0F);
    public static final Curve POS_Y = new Curve(0F, 0F, .03F, -.7F, .07F, -.2F, .11F, .35F, .18F, .8F, .31F, .7F,
            .44F, .05F, .55F, -.3F, .7F, -.12F, 1F, 0F);
    public static final Curve POS_Z = new Curve(0F, 0F, .03F, 3.8F, .065F, 4.5F, .1F, .4F, .17F, .95F, .29F, .9F,
            .42F, .75F, .54F, .45F, .68F, .05F, .84F, .08F, 1F, 0F);
    // camera: pitch and yaw normalised to 1 at the peak, roll shake in degrees
    public static final Curve CAM_PITCH = new Curve(0F, 0F, .015F, 1F, .09F, .85F, .135F, .88F, .25F, .45F, .39F, -.08F,
            .52F, .04F, .68F, 0F);
    public static final Curve CAM_YAW = new Curve(0F, 0F, .015F, 1F, .11F, .82F, .3F, .3F, .48F, -.07F, .64F, 0F);
    public static final Curve CAM_ROLL = new Curve(0F, 0F, .015F, 2F, .035F, -1.9F, .13F, 1.35F, .205F, -1.05F, .27F, .5F,
            .34F, -.3F, .44F, .15F, .58F, 0F);
}
