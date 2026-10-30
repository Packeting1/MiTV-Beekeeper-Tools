import io.github.rin.beekeeper.rgb.NetworkLimits;

public class NetworkLimitsTest {
    public static void main(String[] args) {
        for(int n=0;n<=84;n++)if(NetworkLimits.validCount(n)!=(n==1||n==24))throw new AssertionError();
        NetworkLimits.checkFrame(72);
        for(int n:new int[]{0,1,2,3,71,73,249,250,252,Integer.MAX_VALUE}) {
            try {NetworkLimits.checkFrame(n);throw new AssertionError("Unsafe frame accepted: "+n);}
            catch(IllegalArgumentException expected) { }
        }
        System.out.println("Network supports 1 or 24 pixels; custom OM2 frames remain exactly 72 RGB bytes");
    }
}
