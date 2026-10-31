package com.beekeeper.decorativelight;

/** 原厂 MCU 协议：整条灯、常亮、RGB、异或校验。 */
public final class SingleFrame {
    public static byte[] encode(byte[] rgb) {
        if (rgb.length != 3) throw new IllegalArgumentException("整灯帧必须是 3 字节");
        byte[] packet = {(byte) 0x81, 1, rgb[0], rgb[1], rgb[2], 0};
        for (int i = 0; i < 5; i++) packet[5] ^= packet[i];
        return packet;
    }

    private SingleFrame() { }
}