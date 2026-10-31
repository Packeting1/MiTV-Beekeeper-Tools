package com.beekeeper.slim;

import static com.beekeeper.slim.Catalog.*;

/** 一个勾选项：应用组 / Aerial / Magisk 模块 / LSPosed 模块。 */
final class Group {
    final String title, desc, warn;
    final String[] pkgs;
    final int kind;
    String modId, asset, confirm, requires, requiresMsg;
    /** 放在“修复类”页的 Magisk 模块。 */
    boolean fix;
    ItemView box;
    /** render 时给勾选框设的状态；应用类只在用户改过勾选时才处理，避免“部分禁用”的组被一并恢复。 */
    boolean shown;
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
    /** 勾选前先弹窗确认，取消则保持未勾选。 */
    Group confirm(String text) {
        this.confirm = text;
        return this;
    }
    /** 依赖另一个 Magisk 模块（按模块 id）：对方没勾选时本项锁定（点击提示 lockedMsg），本项勾选时也不能取消对方。 */
    Group requires(String modId, String lockedMsg) {
        this.requires = modId; this.requiresMsg = lockedMsg;
        return this;
    }
    Group fix() {
        this.fix = true;
        return this;
    }
    /** LSPosed 模块（APK）：包名即标识。 */
    static Group apk(String title, String desc, String warn, String pkg, String asset) {
        Group g = new Group(KIND_APK, title, desc, warn, pkg);
        g.asset = asset;
        return g;
    }
}
