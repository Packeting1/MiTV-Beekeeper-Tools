import io.github.rin.beekeeper.rgb.NetworkPacket;
import java.util.Arrays;

public class NetworkPacketTest {
    static void check(boolean b) { if(!b) throw new AssertionError(); }
    static void put16(byte[] p,int i,int x) { p[i]=(byte)(x>>8);p[i+1]=(byte)x; }
    static void put32(byte[] p,int i,int x) { put16(p,i,x>>16);put16(p,i+2,x); }
    static byte[] sacn() {
        byte[] p=new byte[135];
        put16(p,0,16);byte[] id={65,83,67,45,69,49,46,49,55,0,0,0};System.arraycopy(id,0,p,4,12);
        put16(p,16,0x7000+p.length-16);put32(p,18,4);p[22]=1;
        put16(p,38,0x7000+p.length-38);put32(p,40,2);p[108]=100;p[111]=(byte)255;put16(p,113,1);
        put16(p,115,0x7000+p.length-115);p[117]=2;p[118]=(byte)0xa1;put16(p,121,1);put16(p,123,10);
        byte[] rgb={(byte)255,0,0,0,(byte)255,0,0,0,(byte)255};System.arraycopy(rgb,0,p,126,9);return p;
    }
    public static void main(String[] args) {
        byte[] p=sacn();NetworkPacket v=NetworkPacket.sacn(p,p.length);
        check(v!=null && v.universe==1 && v.priority==100 && v.sequence==255 && v.data.length==9);
        check(v.data[0]==(byte)255 && v.data[4]==(byte)255 && v.data[8]==(byte)255);
        for(int n=0;n<p.length;n++) check(NetworkPacket.sacn(p,n)==null);
        byte[] bad=p.clone();bad[112]=(byte)0x80;check(NetworkPacket.sacn(bad,bad.length)==null);
        bad=p.clone();bad[112]=0x40;check(NetworkPacket.sacn(bad,bad.length).terminated);
        bad=p.clone();put16(bad,123,514);check(NetworkPacket.sacn(bad,bad.length)==null);
        bad=p.clone();put16(bad,113,0);check(NetworkPacket.sacn(bad,bad.length)==null);
        bad=p.clone();put16(bad,109,1);check(NetworkPacket.sacn(bad,bad.length)==null);
        byte[] d={0x41,1,0x0b,1,0,0,0,3,0,3,12,34,56};
        v=NetworkPacket.ddp(d,d.length);check(v!=null && v.offset==3 && v.push && Arrays.equals(v.data,new byte[]{12,34,56}));
        for(int n=0;n<d.length;n++) check(NetworkPacket.ddp(d,n)==null);
        bad=d.clone();bad[2]=0x1b;check(NetworkPacket.ddp(bad,bad.length)==null);
        bad=d.clone();bad[0]=0x43;check(NetworkPacket.ddp(bad,bad.length)==null);
        bad=d.clone();bad[4]=(byte)0xff;check(NetworkPacket.ddp(bad,bad.length).offset>Integer.MAX_VALUE);
        check(NetworkPacket.newer(255,0,256));check(!NetworkPacket.newer(10,9,256));check(!NetworkPacket.newer(10,10,256));
        // Captured OpenRGB v1.1 packet: type 1, sequence 8, 24 yellow pixels.
        byte[] openrgb=new byte[82];openrgb[0]=0x41;openrgb[1]=8;openrgb[2]=1;openrgb[3]=1;put16(openrgb,8,72);
        for(int i=0;i<24;i++){openrgb[10+i*3]=(byte)255;openrgb[11+i*3]=(byte)255;}
        v=NetworkPacket.ddp(openrgb,openrgb.length);
        check(v!=null&&v.data.length==72&&v.sequence==8&&v.push);
        check(v.repeatsDdpFrame(8,v.data.clone()));
        check(v.repeatsDdpFrame(8,Arrays.copyOf(v.data,3))); // Stock MCU consumes first RGB even if sender is still set to 24.
        check(!v.repeatsDdpFrame(8,new byte[]{0,0,0}));
        check(!v.repeatsDdpFrame(7,v.data));check(!v.repeatsDdpFrame(0,v.data));
        byte[] changed=v.data.clone();changed[0]=0;check(!v.repeatsDdpFrame(8,changed));
        bad=openrgb.clone();bad[0]=0x40;check(!NetworkPacket.ddp(bad,bad.length).repeatsDdpFrame(8,v.data));
        bad=openrgb.clone();bad[7]=3;check(!NetworkPacket.ddp(bad,bad.length).repeatsDdpFrame(8,v.data));
        bad=openrgb.clone();bad[2]=2;check(NetworkPacket.ddp(bad,bad.length)==null);
        System.out.println("E1.31/DDP RGB24 parsing, pixel separation, sequence wrap and malformed-packet tests passed");
    }
}
