package io.github.rin.beekeeper.rgb;

public final class NetworkLimits {
    public static final int MAX_PIXELS = 24;
    public static boolean validCount(int count) { return count==1||count==MAX_PIXELS; }
    public static void checkFrame(int bytes) {
        if(bytes!=MAX_PIXELS*3)
            throw new IllegalArgumentException("Invalid or unsafe pixel frame length");
    }
    private NetworkLimits() { }
}
