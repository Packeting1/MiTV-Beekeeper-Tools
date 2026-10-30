package io.github.rin.beekeeper.rgb;

/** A start-to-start rate cap. Late work is never followed by catch-up bursts. */
public final class FramePacer {
    public static final int FPS=24;
    public static final long INTERVAL_NS=(1_000_000_000L+FPS-1)/FPS;
    private boolean scheduled;
    private long next;
    public boolean due(long now){return !scheduled||now-next>=0;}
    public long delay(long now){return due(now)?0:next-now;}
    public void sent(long started){scheduled=true;next=started+INTERVAL_NS;}
}
