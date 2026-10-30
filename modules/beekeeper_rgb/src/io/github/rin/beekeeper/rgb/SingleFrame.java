package io.github.rin.beekeeper.rgb;

/** Original MCU protocol: whole strip, steady mode, RGB, XOR. */
public final class SingleFrame {
    public static byte[] encode(byte[] rgb) {
        if(rgb.length!=3)throw new IllegalArgumentException("Single RGB frame required");
        byte[] p={(byte)0x81,1,rgb[0],rgb[1],rgb[2],0};
        for(int n=0;n<5;n++)p[5]^=p[n];return p;
    }
    private SingleFrame(){}
}
