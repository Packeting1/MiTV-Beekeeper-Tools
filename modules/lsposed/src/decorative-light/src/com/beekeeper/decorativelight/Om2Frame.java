package com.beekeeper.decorativelight;

/** 自定义 MCU 固件的逐灯帧：24 颗灯珠的 RGB，加头部和异或校验。 */
public final class Om2Frame {
    public static final int PIXELS = 24;

    public static byte[] encode(byte[] rgb) {
        if (rgb.length != PIXELS * 3) throw new IllegalArgumentException("逐灯帧必须是 72 字节");
        byte[] packet = new byte[75];
        packet[0] = (byte) 0x81;
        packet[1] = 12;
        System.arraycopy(rgb, 0, packet, 2, rgb.length);
        for (int i = 0; i < 74; i++) packet[74] ^= packet[i];
        return packet;
    }

    private Om2Frame() { }
}