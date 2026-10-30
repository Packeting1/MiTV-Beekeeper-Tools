package io.github.rin.beekeeper.rgb;

/** Wire format for the physically verified experimental OM2 firmware. */
public final class Om2Frame {
    public static final int PIXELS=24;
    public static byte[] encode(byte[] rgb) {
        NetworkLimits.checkFrame(rgb.length);
        byte[] packet=new byte[75];packet[0]=(byte)0x81;packet[1]=12;
        System.arraycopy(rgb,0,packet,2,72);
        byte crc=0;for(int i=0;i<74;i++)crc^=packet[i];packet[74]=crc;
        return packet;
    }
    private Om2Frame(){}
}
