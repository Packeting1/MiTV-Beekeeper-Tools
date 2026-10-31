#!/system/bin/sh
setprop sys.beekeeper_rgb.native 0
settings put global beekeeper_net_enabled 0
settings put global beekeeper_rgb_enabled 0
pm uninstall --user 0 com.beekeeper.decorativelight
