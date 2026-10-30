package io.github.rin.beekeeper.rgb;

import java.util.Arrays;

/** Bounds-checked RGB24 data packets. No Android or hardware dependencies. */
public final class NetworkPacket {
    public final byte[] data;
    public final byte[] cid;
    public final long offset;
    public final int sequence, priority, universe;
    public final boolean push, terminated;

    private NetworkPacket(byte[] data, byte[] cid, long offset, int sequence,
            int priority, int universe, boolean push, boolean terminated) {
        this.data=data; this.cid=cid; this.offset=offset; this.sequence=sequence;
        this.priority=priority; this.universe=universe; this.push=push; this.terminated=terminated;
    }

    public static NetworkPacket sacn(byte[] p, int n) {
        if (p==null || n<126 || n>p.length || u16(p,0)!=16 || u16(p,2)!=0) return null;
        byte[] id={65,83,67,45,69,49,46,49,55,0,0,0};
        for(int i=0;i<id.length;i++) if(p[4+i]!=id[i]) return null;
        if(!pdu(p,16,n) || !pdu(p,38,n) || !pdu(p,115,n)) return null;
        if(u32(p,18)!=4 || u32(p,40)!=2 || u8(p,117)!=2 || u8(p,118)!=0xa1
                || u16(p,119)!=0 || u16(p,121)!=1 || u8(p,125)!=0) return null;
        int universe=u16(p,113), count=u16(p,123), priority=u8(p,108), options=u8(p,112);
        if(universe<1 || universe>63999 || priority>200 || count<1 || count>513
                || 125+count>n || (options&0x80)!=0 || u16(p,109)!=0) return null;
        if((u16(p,115)&0xfff)!=10+count) return null;
        return new NetworkPacket(Arrays.copyOfRange(p,126,125+count),Arrays.copyOfRange(p,22,38),
                0,u8(p,111),priority,universe,true,(options&0x40)!=0);
    }

    public static NetworkPacket ddp(byte[] p, int n) {
        if(p==null || n<10 || n>p.length) return null;
        int flags=u8(p,0), type=u8(p,2), destination=u8(p,3);
        if((flags&0xc0)!=0x40 || (flags&0x0e)!=0 || (type!=0 && type!=1 && type!=0x0b)
                || destination!=1 || (u8(p,1)&0xf0)!=0) return null;
        int start=(flags&0x10)!=0?14:10, count=u16(p,8);
        if(start+count>n) return null;
        return new NetworkPacket(Arrays.copyOfRange(p,start,start+count),null,u32(p,4),
                u8(p,1),100,0,(flags&1)!=0,false);
    }

    public static boolean newer(int previous, int next, int modulo) {
        int delta=(next-previous+modulo)%modulo;
        return delta>0 && delta<=modulo/2;
    }
    /** OpenRGB repeats its complete cached PUSH packet without advancing sequence. */
    public boolean repeatsDdpFrame(int previous,byte[] frame) {
        return cid==null && previous!=0 && sequence==previous && push && offset==0
                && frame!=null && data.length>=frame.length && Arrays.equals(Arrays.copyOf(data,frame.length),frame);
    }
    private static boolean pdu(byte[] p,int offset,int n) {
        int value=u16(p,offset);
        return (value&0xf000)==0x7000 && (value&0xfff)==n-offset;
    }
    private static int u8(byte[] p,int i) { return p[i]&255; }
    private static int u16(byte[] p,int i) { return (u8(p,i)<<8)|u8(p,i+1); }
    private static long u32(byte[] p,int i) { return ((long)u16(p,i)<<16)|u16(p,i+2); }
}
