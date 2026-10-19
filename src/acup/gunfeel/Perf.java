package acup.gunfeel;

// Client: where the frame time of bullets goes. Every 5 s with any bullet work, the average per frame of each part
// to the log ("Bullet frame cost").
public final class Perf {
    private Perf() {
    }

    // (flight holds making holes and hit effects; drawing holes holds following parts and block checks)
    public static final int FLY = 0, HOLE_MAKE = 1, FX = 2, HOLE_DRAW = 3, TRACERS = 4, HOLE_BAKE = 5, FOLLOW = 6, CHECKS = 7;
    private static final String[] NAMES = {"flight", "making holes", "hit effects", "drawing holes", "tracers", "baking holes", "following parts",
            "block checks"};
    private static final long[] NANOS = new long[NAMES.length];
    private static final int[] CALLS = new int[NAMES.length];
    private static long since, frames;
    private static boolean busy;

    public static long start() {
        return System.nanoTime();
    }

    public static void add(int part, long started) {
        NANOS[part] += System.nanoTime() - started;
        CALLS[part]++;
        if (part != HOLE_DRAW && part != TRACERS && part != FOLLOW && part != CHECKS) busy = true;
    }

    // holes on moving parts that moved: how many, on how many block entities, by pose or by cut, the largest change
    private static int moved, byPose, byCut;
    private static final java.util.Set<Object> MOVED_ON = new java.util.HashSet<>();
    private static float poseMax, cutMax;
    private static boolean nanSeen;

    public static void moved(Object be, boolean pose, boolean cut, float poseDiff, float cutDiff) {
        moved++;
        MOVED_ON.add(be);
        if (pose) byPose++;
        if (cut) byCut++;
        if (Float.isNaN(poseDiff) || Float.isNaN(cutDiff)) nanSeen = true;
        if (pose && !Float.isNaN(poseDiff)) poseMax = Math.max(poseMax, poseDiff);
        if (cut && !Float.isNaN(cutDiff)) cutMax = Math.max(cutMax, cutDiff);
    }

    // once a frame: shots flying, holes kept
    public static void frame(int shots, int holes) {
        long now = System.nanoTime();
        if (since == 0L) since = now;
        frames++;
        if (now - since < 5_000_000_000L) return;
        if (busy) {
            StringBuilder b = new StringBuilder();
            double total = 0;
            for (int i = 0; i < NAMES.length; i++) {
                double ms = NANOS[i] / 1e6 / Math.max(1, frames);
                if (i != FOLLOW && i != CHECKS) total += ms; // the total as before, for comparing
                b.append(String.format(java.util.Locale.ROOT, "%s %.3f ms (%d calls), ", NAMES[i], ms, CALLS[i]));
            }
            GunFeel.LOGGER.info("Bullet frame cost over {} frames ({} fps): {}total {} ms a frame; {} shots flying, {} holes",
                    frames, Math.round(frames / ((now - since) / 1e9)), b, String.format(java.util.Locale.ROOT, "%.3f", total), shots, holes);
            if (moved > 0) GunFeel.LOGGER.info("Holes on moving parts moved {} times on {} block entities: pose {} (largest change {}), cut {} (largest {}){}", moved,
                    MOVED_ON.size(), byPose, poseMax, byCut, cutMax, nanSeen ? ", some first placed or reshaped" : "");
        }
        moved = byPose = byCut = 0;
        MOVED_ON.clear();
        poseMax = cutMax = 0;
        nanSeen = false;
        java.util.Arrays.fill(NANOS, 0L);
        java.util.Arrays.fill(CALLS, 0);
        frames = 0;
        since = now;
        busy = false;
    }
}
