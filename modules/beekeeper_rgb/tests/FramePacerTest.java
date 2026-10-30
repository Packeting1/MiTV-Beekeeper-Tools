import io.github.rin.beekeeper.rgb.FramePacer;
public class FramePacerTest {
    public static void main(String[] args) {
        FramePacer p=new FramePacer();long period=FramePacer.INTERVAL_NS;
        if(!p.due(0)||FramePacer.FPS!=24||period*24<1_000_000_000L)throw new AssertionError();
        p.sent(0);
        if(p.due(period-1)||!p.due(period)||p.delay(period-100)!=100)throw new AssertionError();
        // 30fps input, renderer gets the latest frame at its independent deadlines.
        p=new FramePacer();long lastInput=-1,lastRendered=-1,lastStart=-period;int frames=0;
        for(long t=0;t<10_000_000_000L;t+=100_000L) {
            lastInput=t/33_333_333L;
            if(lastInput!=lastRendered&&p.due(t)) {
                if(t-lastStart<period)throw new AssertionError("Burst above 24fps");
                p.sent(t);lastStart=t;lastRendered=lastInput;frames++;
            }
        }
        if(frames<239||frames>240)throw new AssertionError("Incorrect rate: "+frames);
        long late=lastStart+5_000_000_000L;
        if(!p.due(late))throw new AssertionError();p.sent(late);
        if(p.due(late+1)||!p.due(late+period))throw new AssertionError("Catch-up burst");
        // System.nanoTime may be negative; the cap still uses relative differences.
        p=new FramePacer();p.sent(-1_000_000_000L);
        if(p.due(-1_000_000_000L+period-1)||!p.due(-1_000_000_000L+period))throw new AssertionError();
        System.out.println("24fps cap, 30fps coalescing, negative clock and no catch-up bursts passed");
    }
}
