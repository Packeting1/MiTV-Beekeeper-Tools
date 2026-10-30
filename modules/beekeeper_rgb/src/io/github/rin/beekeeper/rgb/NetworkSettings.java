package io.github.rin.beekeeper.rgb;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.*;
import java.net.*;
import java.util.Collections;

public final class NetworkSettings {
    public static void pause(Context ctx) {
        if(get(ctx,"enabled",0)==1)put(ctx,"enabled",0);
    }
    public static int get(Context c,String key,int fallback) {return Settings.Global.getInt(c.getContentResolver(),"beekeeper_net_"+key,fallback);}
    private static void put(Context c,String key,int value) {Settings.Global.putInt(c.getContentResolver(),"beekeeper_net_"+key,value);}
    public static void show(Context ctx,Runnable refresh) {
        LinearLayout form=new LinearLayout(ctx);form.setOrientation(1);form.setPadding(36,8,36,16);
        TextView address=new TextView(ctx);address.setText("设备 IP："+addresses()+"\nE1.31：UDP 5568（单播/组播）　DDP：UDP 4048\n每颗灯珠使用独立的 R、G、B 三字节，按灯珠顺序映射。");address.setTextSize(16);form.addView(address);
        Switch enabled=new Switch(ctx);enabled.setText("启用网络接收");enabled.setChecked(get(ctx,"enabled",0)==1);form.addView(enabled);
        CheckBox sacn=new CheckBox(ctx);sacn.setText("E1.31 / sACN");sacn.setChecked(get(ctx,"sacn",1)==1);form.addView(sacn);
        CheckBox ddp=new CheckBox(ctx);ddp.setText("DDP / RGB24");ddp.setChecked(get(ctx,"ddp",1)==1);form.addView(ddp);
        TextView count=new TextView(ctx);count.setTextSize(18);form.addView(count);
        Button firmware=new Button(ctx);firmware.setText("MCU 固件管理 / 刷写");firmware.setOnClickListener(v->McuSettings.show(ctx));form.addView(firmware);
        LinearLayout pair=new LinearLayout(ctx);pair.setOrientation(0);form.addView(pair);
        LinearLayout left=new LinearLayout(ctx);left.setOrientation(1);
        LinearLayout right=new LinearLayout(ctx);right.setOrientation(1);right.setPadding(20,0,0,0);
        pair.addView(left,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        pair.addView(right,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));
        EditText universe=number(ctx,left,"sACN Universe（1–63999）",get(ctx,"universe",1),0x66ffcc03);
        EditText channel=number(ctx,right,"sACN 起始通道（RGB）",get(ctx,"channel",1),0x66ffcc04);
        TextView note=new TextView(ctx);note.setText("原厂 MCU：按 1 颗配置，第一组 RGB 控制整条灯。已安装自定义 MCU：按 24 颗配置，逐颗独立控制。DDP 从偏移 0 开始；3 秒无数据后恢复本地光效。手动调整灯光会退出网络接管。");note.setTextSize(15);form.addView(note);
        TextView status=new TextView(ctx);status.setTextSize(15);status.setFocusable(true);form.addView(status);
        ScrollView scroll=new ScrollView(ctx);scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(ctx).setTitle("网络灯控").setView(scroll).setPositiveButton("保存",null).setNegativeButton("取消",null).create();
        Handler handler=new Handler(Looper.getMainLooper());
        Runnable updater=new Runnable(){public void run(){if(!dialog.isShowing())return;int n=get(ctx,"capacity",1);count.setText(n==24?"灯珠数量：24（自定义 MCU）":"灯珠数量：1（整条灯同色 / 原厂兼容）");String s=Settings.Global.getString(ctx.getContentResolver(),"beekeeper_net_status");status.setText("状态："+(s==null?"后台服务尚未启动":s));handler.postDelayed(this,1500);}};
        dialog.setOnShowListener(v->{
            updater.run();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
                int n=get(ctx,"capacity",1)==24?24:1,u=parse(universe,-1),ch=parse(channel,-1);
                if(u<1||u>63999){universe.setError("Universe 范围为 1–63999");return;}
                if(ch<1||ch+Math.max(1,n)*3-1>512){channel.setError("全部灯珠的 RGB 通道须位于同一个 Universe 的 1–512 范围");return;}
                if(enabled.isChecked()&&!sacn.isChecked()&&!ddp.isChecked()){sacn.setError("至少启用一种协议");return;}
                put(ctx,"enabled",0);put(ctx,"count",n);put(ctx,"universe",u);put(ctx,"channel",ch);
                put(ctx,"sacn",sacn.isChecked()?1:0);put(ctx,"ddp",ddp.isChecked()?1:0);
                put(ctx,"enabled",enabled.isChecked()?1:0);refresh.run();dialog.dismiss();
            });
        });
        dialog.setOnDismissListener(v->handler.removeCallbacks(updater));dialog.show();
    }
    private static EditText number(Context c,LinearLayout form,String title,int value,int id) {
        TextView label=new TextView(c);label.setText(title);label.setTextSize(16);form.addView(label);
        EditText field=new EditText(c);field.setId(id);field.setSingleLine();field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setTextSize(20);field.setTextColor(Color.WHITE);field.setPadding(8,0,8,0);
        if(value>0)field.setText(String.valueOf(value));field.setSelectAllOnFocus(true);
        form.addView(field,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,Math.round(48*c.getResources().getDisplayMetrics().density)));return field;
    }
    private static int parse(EditText v,int fallback){try{return Integer.parseInt(v.getText().toString());}catch(Exception e){return fallback;}}
    private static String addresses() {
        StringBuilder out=new StringBuilder();
        try {for(NetworkInterface n:Collections.list(NetworkInterface.getNetworkInterfaces()))for(InetAddress a:Collections.list(n.getInetAddresses()))if(a instanceof Inet4Address&&!a.isLoopbackAddress()&&!a.isLinkLocalAddress()){if(out.length()>0)out.append(" / ");out.append(a.getHostAddress());}}
        catch(Exception ignored){}return out.length()==0?"未连接网络":out.toString();
    }
    private NetworkSettings(){}
}
