package acup.gunfeel;

// Client: where the frame time of bullets goes. Every 5 s with any bullet work, the average per frame of each part
// to the log ("Bullet frame cost").
public final class Perf {
    private Perf() {
    }

    public static final int FLY = 0, HOLE_MAKE = 1, FX = 2, HOLE_DRAW = 3, TRACERS = 4, HOLE_BAKE = 5;
    private static final String[] NAMES = {"flight", "making holes", "hit effects", "drawing holes", "tracers", "baking holes"};
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
        if (part != HOLE_DRAW && part != TRACERS) busy = true;
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
                total += ms;
                b.append(String.format(java.util.Locale.ROOT, "%s %.3f ms (%d calls), ", NAMES[i], ms, CALLS[i]));
            }
            GunFeel.LOGGER.info("Bullet frame cost over {} frames ({} fps): {}total {} ms a frame; {} shots flying, {} holes",
                    frames, Math.round(frames / ((now - since) / 1e9)), b, String.format(java.util.Locale.ROOT, "%.3f", total), shots, holes);
        }
        java.util.Arrays.fill(NANOS, 0L);
        java.util.Arrays.fill(CALLS, 0);
        frames = 0;
        since = now;
        busy = false;
    }
}
