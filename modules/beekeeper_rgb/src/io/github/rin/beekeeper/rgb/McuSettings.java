package io.github.rin.beekeeper.rgb;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import java.util.UUID;

public final class McuSettings {
    private static String get(Context c,String key){String s=Settings.Global.getString(c.getContentResolver(),"beekeeper_mcu_"+key);return s==null?"":s;}
    private static void put(Context c,String value){Settings.Global.putString(c.getContentResolver(),"beekeeper_mcu_request",value);}
    public static void show(Context ctx) {
        new AlertDialog.Builder(ctx).setTitle("MCU 固件管理")
            .setItems(new String[]{"刷写 24 灯珠自定义固件","刷回内置原版固件（单灯控制）"},(d,index)->confirm(ctx,index==0?"install":"restore")).setNegativeButton("关闭",null).show();
    }
    private static void confirm(Context ctx,String action) {
        LinearLayout content=new LinearLayout(ctx);content.setOrientation(1);content.setPadding(36,16,36,16);
        TextView warning=new TextView(ctx);warning.setTextColor(Color.rgb(255,110,80));warning.setTextSize(19);
        warning.setText("警告：此 MCU 同时参与显示与背光控制。刷写失败或中途断电可能造成黑屏、背光或灯光异常；目前没有经过验证的纯软件救砖办法。内置原版镜像不是完整 MCU 闪存备份，也不能保证故障后可刷回。\n\n刷写期间不要断电、拔线、重启设备或更新模块。页面关闭后后台仍会继续刷写。请确认能承担上述风险。");content.addView(warning);
        TextView requirements=new TextView(ctx);requirements.setTextSize(16);requirements.setText("仅支持 beekeeper / otfcn1n\n系统："+McuPolicy.SYSTEM+"（Android 14）\n还会强制校验完整系统指纹、灯控库、原版镜像、内置镜像及实时 MCU v1.07。版本不匹配禁止刷写。\n开始时会以红灯闪烁提示，随后可能暂时黑屏。");content.addView(requirements);
        CheckBox consent=new CheckBox(ctx);consent.setText("我已阅读并接受刷写风险");content.addView(consent);
        TextView status=new TextView(ctx);status.setTextSize(16);status.setText("正在请求后台校验…");content.addView(status);
        ScrollView scroll=new ScrollView(ctx);scroll.addView(content);
        AlertDialog dialog=new AlertDialog.Builder(ctx).setTitle(action.equals("install")?"刷写自定义 MCU 固件":"刷回原版 MCU 固件").setView(scroll).setPositiveButton("请稍候",null).setNegativeButton("取消",null).create();
        Handler handler=new Handler(Looper.getMainLooper());String request=UUID.randomUUID().toString();String[] token={""};long[] armed={0};boolean[] started={false};
        Runnable update=new Runnable(){public void run(){
            if(!dialog.isShowing())return;
            String[] ticket=get(ctx,"ticket").split(":");
            if(ticket.length==3&&ticket[0].equals(request)){token[0]=ticket[1];try{armed[0]=Long.parseLong(ticket[2]);}catch(Exception ignored){armed[0]=0;}}
            else {token[0]="";armed[0]=0;}
            long now=SystemClock.elapsedRealtime();int remaining=armed[0]>0?(int)Math.max(0,(10000-(now-armed[0])+999)/1000):10;
            boolean ready=!started[0]&&!token[0].isEmpty()&&McuPolicy.ready(armed[0],now);
            Button start=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            start.setEnabled(ready&&consent.isChecked());start.setText(started[0]?"正在刷写":armed[0]==0?"等待校验":remaining>0?"开始刷写（"+remaining+" 秒）":now-armed[0]>300000?"已过期，请重新打开":"开始刷写");
            String state=get(ctx,"status");status.setText("后台："+(state.isEmpty()?"服务未响应，请检查 Magisk 模块":state));
            handler.postDelayed(this,250);
        }};
        dialog.setOnShowListener(d->{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);put(ctx,"prepare:"+action+":"+request);update.run();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                if(started[0]||!consent.isChecked()||token[0].isEmpty()||!McuPolicy.ready(armed[0],SystemClock.elapsedRealtime()))return;
                put(ctx,"start:"+token[0]);started[0]=true;dialog.setCancelable(false);dialog.setCanceledOnTouchOutside(false);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText("关闭页面（后台继续）");
            });
        });
        dialog.setOnDismissListener(d->{handler.removeCallbacks(update);if(!started[0]&&!token[0].isEmpty())put(ctx,"cancel:"+token[0]);});dialog.show();
    }
    private McuSettings(){}
}
