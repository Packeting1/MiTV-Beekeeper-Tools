package com.beekeeper.decorativelight;

import java.util.Arrays;

/** OM2 MCU 升级协议：擦除、写头部、61 个 512 字节数据块，每步都必须确认。 */
public final class McuProtocol {
    private static final int HEADER = 32;
    private static final int BLOCK = 512;
    private static final int BLOCKS = 61;
    private static final int SIZE = BLOCKS * BLOCK;
    private static final byte[] VERSION = {2, 0, 16, 'm', 'i', 't', 'v', '_', 'o', 'm', '2', 'f', '_', 'v', '1', '.', '0', '7', 0};

    public interface Link {
        byte[] send(byte[] packet) throws Exception;

        void progress(int blocks) throws Exception;

        /** 擦除命令确认后再等一会儿，等擦除完成。 */
        default void settle() throws Exception {
            Thread.sleep(3000);
        }
    }

    public static void validate(byte[] raw) {
        if (raw.length != HEADER + SIZE || u32(raw, 24) != SIZE || u32(raw, 32) != SIZE)
            throw new IllegalArgumentException("MCU 镜像长度错误");
        int crc = 255;
        for (int i = 36; i < raw.length - 1; i++) crc ^= raw[i] & 255;
        if ((raw[raw.length - 1] & 255) != crc || u32(raw, 28) != crc)
            throw new IllegalArgumentException("MCU 镜像校验错误");
    }

    public static void version(byte[] response) {
        if (response.length != 32 || !Arrays.equals(Arrays.copyOf(response, VERSION.length), VERSION)
                || response[VERSION.length] != 0x2d)
            throw new IllegalArgumentException("实时 MCU 版本校验失败，需要 mitv_om2f_v1.07");
    }

    public static byte[] command(int cmd) {
        return new byte[]{(byte) cmd, 0, 0, (byte) cmd};
    }

    public static void ack(byte[] response, int cmd) {
        if (response.length != 32 || response[2] != 2 || response[3] != cmd
                || response[4] != 0 || response[5] != (2 ^ cmd))
            throw new IllegalArgumentException("MCU 未确认数据块，已停止，命令 " + cmd);
    }

    /** 任何一步未确认就立即停止，不重发。 */
    public static void stage(byte[] raw, Link link) throws Exception {
        validate(raw);
        ack(link.send(command(1)), 1);
        link.settle();
        byte[] header = new byte[3 + HEADER];
        header[0] = 3;
        header[2] = HEADER;
        System.arraycopy(raw, 0, header, 3, HEADER);
        ack(link.send(header), 3);
        for (int i = 0; i < BLOCKS; i++) {
            byte[] packet = new byte[3 + BLOCK];
            packet[0] = 6;
            packet[1] = 2;
            System.arraycopy(raw, HEADER + i * BLOCK, packet, 3, BLOCK);
            ack(link.send(packet), 6);
            link.progress(i + 1);
        }
    }

    private static long u32(byte[] b, int i) {
        return (b[i] & 255L) | ((b[i + 1] & 255L) << 8) | ((b[i + 2] & 255L) << 16) | ((b[i + 3] & 255L) << 24);
    }

    private McuProtocol() { }
}