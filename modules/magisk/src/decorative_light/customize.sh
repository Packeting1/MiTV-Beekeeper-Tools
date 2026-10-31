#!/system/bin/sh
[ "$BOOTMODE" = true ] || abort "请在启动后的 Magisk 中安装"
[ "$(getprop ro.product.device)" = beekeeper ] || abort "仅支持 beekeeper"
[ "$(getprop ro.build.version.incremental)" = OS3.0.109.0.UFFMAMN ] || abort "固件版本不匹配"
lsp=/data/adb/modules/zygisk_vector
[ -d $lsp ] || lsp=/data/adb/modules/zygisk_lsposed
[ -d $lsp ] || abort "需要已安装的 LSPosed"
[ ! -f $lsp/disable ] || abort "请先启用 LSPosed"

# 灯控库：模块不带厂商文件，用电视自带的原版库在设备上打补丁生成。
# 模块已启用时 /vendor 里看到的就是补丁版，此时直接复制。
LIB=/vendor/lib64/libbspmserver_config.so
DST=$MODPATH/system/vendor/lib64/libbspmserver_config.so
STOCK=986aa78c1761d22142463183b69db729f40be10ba45485e9aca68e2d5972a992
PATCHED=5aac620745177370f59b0627e9b2f75dd95be2a4cf428d2658f8b5b7e44b4f11
sha() { sha256sum "$1" 2>/dev/null | cut -d ' ' -f 1; }
actual=$(sha $LIB)
[ "$actual" = $STOCK ] || [ "$actual" = $PATCHED ] || abort "灯控库校验不匹配，未安装"
mkdir -p "${DST%/*}"
cp $LIB "$DST" || abort "复制灯控库失败"
if [ "$actual" = $STOCK ]; then
    put() { printf "$2" | dd of="$DST" bs=1 seek=$(($1)) conv=notrunc 2>/dev/null; }
    # 新增一个 SetColorfulLed 的处理分支：模式 1/2（常亮/呼吸）颜色为 0x01000000 | RRGGBB，
    # 模式 13 停止灯效定时器，交给网络接管。预设颜色的原路径和共用的出错收尾不变。
    # 0x10510  cmp w20, #13; b.eq 0x10584; b 0x10868
    put 0x10510 '\237\066\000\161\200\003\000\124\324\000\000\024'
    # 0x10580  b 0x105bc
    put 0x10580 '\017\000\000\024'
    # 0x10584  adrp x8, 0x30000; ldr x8, [x8, #0x690]; cbz x8, 0x10868; ldr x8, [x8, #0x18]; cbz x8, 0x10868; blr x8; b 0x1086c
    put 0x10584 '\010\001\000\220\010\111\103\371\350\026\000\264\010\015\100\371\250\026\000\264\000\001\077\326\264\000\000\024'
    # 0x105bc  tbz w19, #24, 0x10868; lsr w8, w19, #16; strb w8, [sp, #4]; lsr w8, w19, #8; strb w8, [sp, #5]; strb w19, [sp, #6];
    #          adrp x8, 0x30000; ldr x22, [x8, #0x690]; cbz x22, 0x10868; b 0x107d0; nop; nop
    put 0x105bc '\163\025\300\066\150\176\020\123\350\023\000\071\150\176\010\123\350\027\000\071\363\033\000\071\010\001\000\220\026\111\103\371\166\024\000\264\174\000\000\024\037\040\003\325\037\040\003\325'
fi
[ "$(sha "$DST")" = $PATCHED ] || { rm -f "$DST"; abort "灯控库补丁结果校验失败，未安装"; }
ui_print "- 已验证设备、固件与灯控库"

pm install -r --user 0 "$MODPATH/app/DecorativeLight.apk" || abort "配套 APK 安装失败"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$DST" 0 0 0644 u:object_r:same_process_hal_file:s0
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm_recursive "$MODPATH/bin" 0 0 0700 0700
set_perm_recursive "$MODPATH/firmware" 0 0 0700 0600
ui_print "- 请在 LSPosed 启用“DecorativeLight”及推荐作用域"
ui_print "- 重启后，在系统装饰灯设置中选择“自定义颜色”"