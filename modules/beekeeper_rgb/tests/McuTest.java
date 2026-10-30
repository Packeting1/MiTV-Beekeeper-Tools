import io.github.rin.beekeeper.rgb.*;
import java.nio.file.*;
import java.util.*;
public class McuTest {
    interface Checked {void run()throws Exception;}
    static void rejected(Checked f)throws Exception {try{f.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError("Unsafe input accepted");}
    public static void main(String[] args)throws Exception {
        String[] good={"beekeeper","otfcn1n",McuPolicy.SYSTEM,"34",McuPolicy.FINGERPRINT,McuPolicy.ORIGINAL,McuPolicy.LIBRARY};
        McuPolicy.check(good[0],good[1],good[2],good[3],good[4],good[5],good[6]);
        for(int i=0;i<good.length;i++){String[] bad=good.clone();bad[i]+="x";rejected(()->McuPolicy.check(bad[0],bad[1],bad[2],bad[3],bad[4],bad[5],bad[6]));}
        for(long t:new long[]{0,9999,10000,10001,19999,20000,20001,309999,310000,310001})if(McuPolicy.ready(10000,t)!=(t>=20000&&t<=310000))throw new AssertionError("Countdown bypass");
        if(McuPolicy.ready(0,999999)||McuPolicy.ready(10000,9999))throw new AssertionError();
        byte[] commit=new byte[32];commit[2]=2;commit[3]=4;commit[5]=6;McuProtocol.ack(commit,4);
        byte[] failed=commit.clone();failed[4]=1;rejected(()->McuProtocol.ack(failed,4));
        byte[] ver=new byte[32];byte[] prefix=new byte[]{2,0,16,'m','i','t','v','_','o','m','2','f','_','v','1','.','0','7',0,0x2d};System.arraycopy(prefix,0,ver,0,20);McuProtocol.version(ver);
        for(int i=0;i<20;i++){byte[] bad=ver.clone();bad[i]^=1;rejected(()->McuProtocol.version(bad));}
        for(String path:args){
            byte[] raw=Files.readAllBytes(Paths.get(path));McuProtocol.validate(raw);
            byte[] bad=raw.clone();bad[100]^=1;rejected(()->McuProtocol.validate(bad));rejected(()->McuProtocol.validate(Arrays.copyOf(raw,raw.length-1)));
            final int[] calls={0},blocks={0};
            McuProtocol.stage(raw,new McuProtocol.Link(){public byte[] send(byte[] p){int n=calls[0]++;int cmd=n==0?1:n==1?3:6;if((p[0]&255)!=cmd)throw new AssertionError();
                if(cmd==6){if(p.length!=515||p[1]!=2||p[2]!=0||!Arrays.equals(Arrays.copyOfRange(p,3,515),Arrays.copyOfRange(raw,32+(n-2)*512,32+(n-1)*512)))throw new AssertionError("Block differs");}
                else if(cmd==3&&!Arrays.equals(Arrays.copyOfRange(p,3,35),Arrays.copyOf(raw,32)))throw new AssertionError();
                byte[] r=new byte[32];r[2]=2;r[3]=(byte)cmd;r[5]=(byte)(2^cmd);return r;
            }public void progress(int n){blocks[0]=n;}public void settle(){if(calls[0]!=1)throw new AssertionError();}});
            if(calls[0]!=63||blocks[0]!=61)throw new AssertionError();
            final int[] failedCalls={0};rejected(()->McuProtocol.stage(raw,new McuProtocol.Link(){public byte[] send(byte[] p){failedCalls[0]++;return new byte[32];}public void progress(int n){throw new AssertionError();}}));
            if(failedCalls[0]!=1)throw new AssertionError("Mutation retried after rejection");
        }
        System.out.println("MCU exact allowlist, countdown, image/version integrity, all 61 blocks and fail-stop passed");
    }
}
