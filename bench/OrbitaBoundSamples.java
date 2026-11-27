package dev.stillmotion.wallpaper;

/** Emits real renderer bounds for the host OpenGL comparison. */
public final class OrbitaBoundSamples {
    public static void main(String[] args) {
        OrbitaBounds bounds = new OrbitaBounds();
        for (int[] size : new int[][] {{720,1560}, {1080,2340}, {1440,2560}}) {
            for (int radial = 0; radial <= 1; radial++) {
                for (int gas = 0; gas <= 1; gas++) {
                    for (int stage = 0; stage < 4; stage++) {
                        float wake = stage == 0 ? 0f : 1f;
                        float zoom = stage < 2 ? 0f : stage == 2 ? .5f : 1f;
                        bounds.update(size[0], size[1], wake, zoom, radial != 0);
                        System.out.printf(java.util.Locale.ROOT, "%d,%d,%d,%d,%d,%.1f,%.1f,%d,%d,%d,%d%n",
                                size[0], size[1], radial, gas, stage, wake, zoom,
                                bounds.left, bounds.bottom, bounds.width, bounds.height);
                    }
                }
            }
        }
    }
}
