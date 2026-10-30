import io.github.rin.beekeeper.rgb.SingleFrame;
import java.util.Arrays;
public class SingleFrameTest {
    public static void main(String[] args) {
        if(!Arrays.equals(SingleFrame.encode(new byte[]{0x66,(byte)0xff,(byte)0xcc}),new byte[]{(byte)0x81,1,0x66,(byte)0xff,(byte)0xcc,(byte)0xd5}))throw new AssertionError();
        for(int n=0;n<=255;n++){byte[] p=SingleFrame.encode(new byte[]{(byte)n,(byte)(255-n),(byte)(n^55)});int x=0;for(byte b:p)x^=b&255;if(x!=0||p.length!=6)throw new AssertionError();}
        for(int n:new int[]{0,1,2,4,72})try{SingleFrame.encode(new byte[n]);throw new AssertionError();}catch(IllegalArgumentException expected){}
        System.out.println("Stock six-byte RGB/XOR protocol and length checks passed");
    }
}
