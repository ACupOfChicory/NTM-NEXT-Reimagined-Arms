package acup.gunfeel;

// Client: where the frame time of bullets goes. Every 5 s with any bullet work, the average per frame of each part
// to the log ("Bullet frame cost").
public final class Perf {
    private Perf() {
    }

    // (flight holds hit effects; making holes runs apart, a few a frame; drawing holes holds following parts and block
    // checks)
    // the parts of drawing holes (in it, not in the total): sorting = batch() and the sort of the rest each frame; one by
    // one = holes not in a batch, written each frame; glass = afterWeather's (written and sent each frame); batches =
    // the baked batches sent
    public static final int FLY = 0, HOLE_MAKE = 1, FX = 2, HOLE_DRAW = 3, TRACERS = 4, HOLE_BAKE = 5, FOLLOW = 6, CHECKS = 7, SORT = 8, ONE = 9,
            LATE = 10, BATCHES = 11;
    private static final String[] NAMES = {"flight", "making holes", "hit effects", "drawing holes", "tracers", "baking holes", "following parts",
            "block checks", "sorting", "one by one", "glass", "batches sent"};
    // holes written one by one each frame, by why: on glass, fading, on a moving part, else (holeCache off)
    public static final int ON_GLASS = 0, FADING = 1, MOVING = 2, OTHER = 3;
    private static final long[] ONE_BY = new long[4];
    private static long batched;

    public static void oneByOne(int why) {
        ONE_BY[why]++;
    }

    public static void batched(int n) {
        batched += n;
    }
    // not ours, for comparing (measured only): the game's particles (tick, gathering them for drawing with how many there
    // are, handing them to the renderer) and Flywheel's frame (NTM's explosion smoke and machines are its visuals)
    public static final int P_TICK = 0, P_EXTRACT = 1, P_SUBMIT = 2, FW_FRAME = 3, FW_RENDER = 4;
    private static final String[] OTHER_NAMES = {"particle tick", "particle gathering", "particle handing over", "Flywheel frame", "Flywheel drawing"};
    private static final long[] OTHER_NANOS = new long[OTHER_NAMES.length];
    private static final int[] OTHER_CALLS = new int[OTHER_NAMES.length];
    private static long particles, particlesMax;
    // the longest frame (from one frame() to the next) and how much of it was ours (the parts in the total)
    private static long lastFrame, longest, longestOurs, ours;

    public static void other(int part, long started) {
        OTHER_NANOS[part] += System.nanoTime() - started;
        OTHER_CALLS[part]++;
    }

    public static void particles(long n) {
        particles += n;
        particlesMax = Math.max(particlesMax, n);
    }
    private static final long[] NANOS = new long[NAMES.length];
    private static final int[] CALLS = new int[NAMES.length];
    private static long since, frames;
    private static boolean busy;

    public static long start() {
        return System.nanoTime();
    }

    public static void add(int part, long started) {
        long d = System.nanoTime() - started;
        NANOS[part] += d;
        CALLS[part]++;
        if (part <= HOLE_BAKE) ours += d;
        if (part == FLY || part == HOLE_MAKE || part == FX || part == HOLE_BAKE) busy = true;
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
        if (lastFrame != 0L && now - lastFrame > longest) {
            longest = now - lastFrame;
            longestOurs = ours;
        }
        lastFrame = now;
        ours = 0;
        if (now - since < 5_000_000_000L) return;
        if (busy || holes > 0) {
            StringBuilder b = new StringBuilder();
            double total = 0;
            for (int i = 0; i < NAMES.length; i++) {
                double ms = NANOS[i] / 1e6 / Math.max(1, frames);
                if (i <= HOLE_BAKE) total += ms; // the total as before, for comparing
                b.append(String.format(java.util.Locale.ROOT, "%s %.3f ms (%d calls), ", NAMES[i], ms, CALLS[i]));
            }
            GunFeel.LOGGER.info("Bullet frame cost over {} frames ({} fps): {}total {} ms a frame; {} shots flying, {} holes",
                    frames, Math.round(frames / ((now - since) / 1e9)), b, String.format(java.util.Locale.ROOT, "%.3f", total), shots, holes);
            double f = Math.max(1, frames);
            GunFeel.LOGGER.info("Holes a frame: {} in batches; one by one {} on glass, {} fading, {} on moving parts, {} else",
                    Math.round(batched / f), Math.round(ONE_BY[ON_GLASS] / f), Math.round(ONE_BY[FADING] / f), Math.round(ONE_BY[MOVING] / f), Math.round(ONE_BY[OTHER] / f));
            if (moved > 0) GunFeel.LOGGER.info("Holes on moving parts moved {} times on {} block entities: pose {} (largest change {}), cut {} (largest {}){}", moved,
                    MOVED_ON.size(), byPose, poseMax, byCut, cutMax, nanSeen ? ", some first placed or reshaped" : "");
            StringBuilder o = new StringBuilder();
            for (int i = 0; i < OTHER_NAMES.length; i++)
                o.append(String.format(java.util.Locale.ROOT, "%s %.3f ms (%d calls), ", OTHER_NAMES[i], OTHER_NANOS[i] / 1e6 / f, OTHER_CALLS[i]));
            GunFeel.LOGGER.info("Not ours, a frame: {}particles {} on average, {} at most; longest frame {} ms, ours in it {} ms", o,
                    Math.round(particles / (double) Math.max(1, OTHER_CALLS[P_EXTRACT])), particlesMax,
                    String.format(java.util.Locale.ROOT, "%.1f", longest / 1e6), String.format(java.util.Locale.ROOT, "%.1f", longestOurs / 1e6));
        }
        java.util.Arrays.fill(OTHER_NANOS, 0L);
        java.util.Arrays.fill(OTHER_CALLS, 0);
        particles = particlesMax = 0;
        longest = longestOurs = 0;
        java.util.Arrays.fill(ONE_BY, 0L);
        batched = 0;
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
