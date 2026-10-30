package io.github.rin.beekeeper.rgb;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

final class Om2Output implements Closeable {
    static final String MARKER="/data/adb/beekeeper-rgb/om2-pixel-firmware.sha256";
    static final String FIRMWARE="69f4c469f391dabeef52489d01b3c41fbe7fe16297b4ebea144e0afdef793b5f";
    private final String path;
    private Process process;
    private OutputStream input;
    private InputStream result;
    private boolean pixels;
    Om2Output(String path){this.path=path;}
    static boolean customFirmware() {
        try {return FIRMWARE.equals(new String(Files.readAllBytes(new File(MARKER).toPath()),StandardCharsets.US_ASCII).trim());}
        catch(IOException e){return false;}
    }
    void checkFirmware() throws IOException {
        File marker=new File(MARKER);
        if(!marker.isFile()||!FIRMWARE.equals(new String(Files.readAllBytes(marker.toPath()),StandardCharsets.US_ASCII).trim()))
            throw new IOException("需要已验证的 OM2 逐灯固件");
        if(!new File(path).canExecute())throw new IOException("缺少 OM2 输出程序");
    }
    void open(boolean pixels) throws IOException {
        this.pixels=pixels;
        if(pixels)checkFirmware();
        String writer=pixels?path:new File(new File(path).getParentFile(),"om2-single-writer").getPath();
        if(!new File(writer).canExecute())throw new IOException("缺少灯控输出程序");
        process=new ProcessBuilder(writer).start();input=process.getOutputStream();result=process.getInputStream();
    }
    void send(byte[] rgb) throws Exception {
        if(process==null)throw new IOException("OM2 输出尚未接管");
        input.write(pixels?Om2Frame.encode(rgb):SingleFrame.encode(rgb));input.flush();
        long deadline=System.nanoTime()+1000000000L;
        while(result.available()==0) {
            if(!process.isAlive())throw new IOException("OM2 输出进程退出："+process.exitValue());
            if(System.nanoTime()>=deadline)throw new IOException("OM2 I2C 写入超时");
            Thread.sleep(2);
        }
        if(result.read()!=0)throw new IOException("OM2 输出响应异常");
    }
    public void close() {
        if(process==null)return;
        try{input.close();}catch(Exception ignored){}
        try{if(!process.waitFor(200,TimeUnit.MILLISECONDS))process.destroyForcibly();}
        catch(Exception ignored){process.destroy();}
        try{result.close();}catch(Exception ignored){}
        process=null;input=null;result=null;
    }
}
