package io.github.rin.beekeeper.rgb;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import android.os.SystemClock;

/** Runs only in the Magisk root worker, serially with LED output. No UDP upgrade commands. */
final class McuManager {
    interface Host {String savedEnable();void pause() throws Exception;void restore(String enable) throws Exception;}
    static final String PREFIX="beekeeper_mcu_";
    static final String VENDOR="/vendor/tvconfig/bsp/chip/ldm_xiaomi/ldm_otfcn1n/MCU.bin";
    private final File module,base=new File("/data/adb/beekeeper-rgb");
    private final File journal=new File(base,"mcu-flash-journal.properties");
    private final Host host;
    private String token="",action="";
    private long armed;
    McuManager(File module,Host host) throws Exception {
        this.module=module;this.host=host;base.mkdirs();
        setting("request","");setting("ticket","");
        if(journal.exists())NetworkDaemon.putSetting("beekeeper_net_enabled","0");
        setting("status",journal.exists()?"发现未完成刷写记录，禁止再次刷写；请检查日志并人工恢复":"尚未刷写");
    }
    boolean recoveryRequired(){return journal.exists();}
    static String sha(byte[] bytes) throws Exception {
        StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();
    }
    private String prop(String name) throws Exception {return new String(exec(new String[]{"/system/bin/getprop",name},3000),StandardCharsets.UTF_8).trim();}
    void preflight() throws Exception {
        McuPolicy.check(prop("ro.product.device"),prop("ro.boot.product_id"),prop("ro.build.version.incremental"),prop("ro.build.version.sdk"),prop("ro.build.fingerprint"),sha(Files.readAllBytes(new File(VENDOR).toPath())),sha(Files.readAllBytes(new File("/vendor/lib64/libbspmserver_config.so").toPath())));
        image("install");image("restore");
        for(String name:new String[]{"om2-flash-transport","om2-flash-read"}) {
            File f=new File(module,"bin/"+name);
            String expected=new String(Files.readAllBytes(new File(module,"firmware/"+name+".sha256").toPath()),StandardCharsets.US_ASCII).trim();
            if(!f.canExecute()||!expected.equals(sha(Files.readAllBytes(f.toPath()))))throw new IOException("刷写传输程序校验失败");
        }
        if(journal.exists())throw new IOException("存在未完成的刷写记录，请人工检查 /data/adb/beekeeper-rgb");
    }
    private byte[] image(String which) throws Exception {
        byte[] raw=Files.readAllBytes(new File(module,"firmware/"+("install".equals(which)?"pixels.bin":"original.bin")).toPath());
        if(!("install".equals(which)?McuPolicy.CUSTOM:McuPolicy.ORIGINAL).equals(sha(raw)))throw new IOException("内置 MCU 镜像 SHA-256 不匹配");
        McuProtocol.validate(raw);return raw;
    }
    void poll(Map<String,String> settings) throws Exception {
        String request=settings.get(PREFIX+"request");if(request==null||request.isEmpty()||request.equals("null"))return;
        setting("request","");
        try {
            String[] p=request.split(":");
            if(p.length==3&&p[0].equals("prepare")&&(p[1].equals("install")||p[1].equals("restore"))&&p[2].matches("[a-fA-F0-9-]{36}")) {
                token="";armed=0;setting("ticket","");preflight();action=p[1];token=UUID.randomUUID().toString();armed=SystemClock.elapsedRealtime();
                setting("ticket",p[2]+":"+token+":"+armed);setting("status","版本与镜像校验通过，等待 10 秒风险提示");
            } else if(p.length==2&&p[0].equals("cancel")&&p[1].equals(token)) {token="";armed=0;setting("ticket","");}
            else if(p.length==2&&p[0].equals("start")&&!token.isEmpty()&&p[1].equals(token)) {
                if(!McuPolicy.ready(armed,SystemClock.elapsedRealtime()))throw new IOException("必须等待完整 10 秒，授权 5 分钟后失效");
                String selected=action;token="";armed=0;setting("ticket","");preflight();flash(selected);
            } else throw new IOException("无效或过期的刷写请求，请重新打开页面");
        } catch(Exception e) {token="";armed=0;setting("ticket","");setting("status","刷写未完成："+e.getMessage());System.err.println("MCU: "+e);}
    }
    private void save(Properties p) throws Exception {
        File temp=new File(base,"mcu-flash-journal.tmp");
        try(FileOutputStream out=new FileOutputStream(temp)){p.store(out,"MCU flash recovery record");out.getFD().sync();}
        Files.move(temp.toPath(),journal.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    private void flash(String selected) throws Exception {
        byte[] raw=image(selected);String enabled=NetworkDaemon.readSettings().get("beekeeper_net_enabled"),enable=host.savedEnable();
        if(!"0".equals(enable)&&!"1".equals(enable))throw new IOException("原驱动状态无效");
        Properties p=new Properties();p.setProperty("action",selected);p.setProperty("network",String.valueOf(enabled));p.setProperty("driver",enable);p.setProperty("image",sha(raw));p.setProperty("phase","prepared");save(p);
        boolean success=false;
        try {
            host.pause();setting("status","正在读取实时 MCU 版本");McuProtocol.version(transport(McuProtocol.command(2)));
            // Keep the known original image and exact installed target for software recovery.
            Files.copy(new File(module,"firmware/original.bin").toPath(),new File(base,"mcu-original.bin").toPath(),StandardCopyOption.REPLACE_EXISTING);
            Files.copy(new File(module,"firmware/"+(selected.equals("install")?"pixels.bin":"original.bin")).toPath(),new File(base,"mcu-last-target.bin").toPath(),StandardCopyOption.REPLACE_EXISTING);
            p.setProperty("phase","staging");save(p);
            McuProtocol.stage(raw,new McuProtocol.Link(){public byte[] send(byte[] packet)throws Exception{return transport(packet);}public void progress(int blocks)throws Exception{p.setProperty("blocks",String.valueOf(blocks));save(p);setting("status","暂存 MCU 镜像 "+blocks+" / 61，请勿断电");}});
            p.setProperty("phase","commit-requested");save(p);
            Files.deleteIfExists(new File(Om2Output.MARKER).toPath());
            setting("status","正在提交 MCU 应用区，请勿断电或重启");
            byte[] commit=transport(McuProtocol.command(4));
            if(commit[2]==2&&commit[3]==4)McuProtocol.ack(commit,4);
            Thread.sleep(5000);
            McuProtocol.version(transport(McuProtocol.command(2)));
            if(selected.equals("install")) {
                File temp=new File(base,"pixel-marker.tmp");Files.write(temp.toPath(),(McuPolicy.CUSTOM+"\n").getBytes(StandardCharsets.US_ASCII));
                Files.move(temp.toPath(),new File(Om2Output.MARKER).toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            }
            p.setProperty("phase","version-verified");save(p);success=true;
        } finally {
            try {host.restore(enable);} catch(Exception e){success=false;throw e;}
            finally {
                NetworkDaemon.putSetting("beekeeper_net_count",Om2Output.customFirmware()?"24":"1");
                NetworkDaemon.putSetting("beekeeper_net_enabled",success&&"1".equals(enabled)?"1":"0");
                if(success){Files.move(journal.toPath(),new File(base,"mcu-flash-last.properties").toPath(),StandardCopyOption.REPLACE_EXISTING);setting("status",selected.equals("install")?"刷写完成，已恢复通信；24 灯珠模式，请检查画面和灯光":"原版刷写完成，已恢复通信；单灯模式，请检查画面和灯光");}
            }
        }
    }
    private byte[] transport(byte[] packet) throws Exception {
        if(packet.length<1||packet.length>544)throw new IOException("MCU 数据包越界");
        Files.write(new File(base,"mcu-packet.bin").toPath(),packet);
        log("write command="+(packet[0]&255)+" bytes="+packet.length);
        exec(new String[]{new File(module,"bin/om2-flash-transport").getPath(),"write"},5000);
        int cmd=packet[0]&255;Thread.sleep(cmd==1||cmd==4||cmd==5?3000:100);
        for(int n=0;n<(cmd==4?120:10);n++) {
            try {byte[] response=exec(new String[]{new File(module,"bin/om2-flash-read").getPath()},3000);if(response.length==32){log("response command="+cmd+" bytes="+Arrays.toString(response));return response;}}
            catch(IOException e){System.err.println("MCU read: "+e.getMessage());}
            Thread.sleep(300);
        }
        throw new IOException("MCU 响应超时；未重发写入命令，请检查恢复记录");
    }
    private byte[] exec(String[] args,long timeout) throws Exception {
        Process child=new ProcessBuilder(args).start();
        if(!child.waitFor(timeout,TimeUnit.MILLISECONDS)){child.destroyForcibly();throw new IOException("程序超时："+new File(args[0]).getName());}
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;
        try(InputStream in=child.getInputStream()){while((n=in.read(b))!=-1)out.write(b,0,n);}
        if(child.exitValue()!=0)throw new IOException("传输程序退出："+child.exitValue());return out.toByteArray();
    }
    private void setting(String key,String value)throws Exception {NetworkDaemon.putSetting(PREFIX+key,value);}
    private void log(String event)throws IOException {try(FileOutputStream out=new FileOutputStream(new File(base,"mcu-flash.log"),true)){out.write((System.currentTimeMillis()+" "+event+"\n").getBytes(StandardCharsets.UTF_8));out.getFD().sync();}}
}
