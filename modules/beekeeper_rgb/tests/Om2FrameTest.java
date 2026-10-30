import io.github.rin.beekeeper.rgb.Om2Frame;
public class Om2FrameTest {
    public static void main(String[] args) {
        for(int n=0;n<24;n++) {
            byte[] rgb=new byte[72];rgb[n*3]=(byte)255;
            byte[] packet=Om2Frame.encode(rgb);
            if(packet.length!=75||packet[0]!=(byte)0x81||packet[1]!=12)throw new AssertionError();
            int crc=0;for(byte b:packet)crc^=b&255;
            if(crc!=0)throw new AssertionError();
            for(int i=0;i<72;i++)if(packet[i+2]!=(i==n*3?(byte)255:0))throw new AssertionError("Pixel shifted: "+n);
        }
        System.out.println("All 24 addresses encoded independently with OM2 XOR");
    }
}
