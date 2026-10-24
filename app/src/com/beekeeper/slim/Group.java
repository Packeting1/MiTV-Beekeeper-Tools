package com.beekeeper.slim;

import android.widget.CheckBox;

import static com.beekeeper.slim.Catalog.*;

/** 一个勾选项：应用组 / Aerial / Magisk 模块 / LSPosed 模块。 */
final class Group {
    final String title, desc, warn;
    final String[] pkgs;
    final int kind;
    String modId, asset;
    CheckBox box;
    Group(String title, String desc, String warn, String... pkgs) {
        this(KIND_PKGS, title, desc, warn, pkgs);
    }
    Group(int kind, String title, String desc, String warn, String... pkgs) {
        this.kind = kind; this.title = title; this.desc = desc; this.warn = warn; this.pkgs = pkgs;
    }
    Group mod(String id, String asset) {
        this.modId = id; this.asset = asset;
        return this;
    }
}
