package io.github.rin.beekeeper.rgb;

import java.util.Arrays;

public final class McuProtocol {
    public interface Link {byte[] send(byte[] packet) throws Exception;void progress(int blocks) throws Exception;default void settle()throws Exception{Thread.sleep(3000);}}
    public static void validate(byte[] raw) {
        if(raw.length!=31264||u32(raw,24)!=31232||u32(raw,32)!=31232)throw new IllegalArgumentException("MCU 镜像长度错误");
        int crc=255;for(int n=36;n<raw.length-1;n++)crc^=raw[n]&255;
        if((raw[raw.length-1]&255)!=crc||u32(raw,28)!=crc)throw new IllegalArgumentException("MCU 镜像校验错误");
    }
    private static long u32(byte[] b,int i){return (b[i]&255L)|((b[i+1]&255L)<<8)|((b[i+2]&255L)<<16)|((b[i+3]&255L)<<24);}
    public static void version(byte[] response) {
        byte[] prefix=new byte[]{2,0,16,'m','i','t','v','_','o','m','2','f','_','v','1','.','0','7',0};
        if(response.length!=32||!Arrays.equals(Arrays.copyOf(response,prefix.length),prefix)||response[19]!=0x2d)
            throw new IllegalArgumentException("实时 MCU 版本校验失败，需要 mitv_om2f_v1.07");
    }
    public static byte[] command(int n){return new byte[]{(byte)n,0,0,(byte)n};}
    public static void ack(byte[] r,int cmd) {
        if(r.length!=32||r[2]!=2||r[3]!=cmd||r[4]!=0||r[5]!=(2^cmd))throw new IllegalArgumentException("MCU 未确认数据块，已停止，命令="+cmd);
    }
    public static void stage(byte[] raw,Link link) throws Exception {
        validate(raw);ack(link.send(command(1)),1);link.settle();
        byte[] header=new byte[35];header[0]=3;header[2]=32;System.arraycopy(raw,0,header,3,32);ack(link.send(header),3);
        for(int n=0;n<61;n++) {
            byte[] p=new byte[515];p[0]=6;p[1]=2;System.arraycopy(raw,32+n*512,p,3,512);
            ack(link.send(p),6);link.progress(n+1);
        }
    }
    private McuProtocol(){}
}
