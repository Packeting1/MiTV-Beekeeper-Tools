package com.beekeeper.slim;

import static com.beekeeper.slim.Catalog.*;

/** 一个勾选项：应用组 / Aerial / Magisk 模块 / Xposed 模块。 */
final class Group {
  final String title, desc, warn;
  final String[] pkgs;
  final int kind;
  String modId, dir, asset, confirm, confirmOff, requires, requiresMsg;

  /** Magisk 模块的配套 Xposed APK（资产名，包名即 pkgs[0]）：由应用随模块一起安装和卸载。 */
  String companion;

  /** 放在“修复类”页的 Magisk 模块。 */
  boolean fix;

  /** 放在“基础”页；用内置版本安装，更新另外从 GitHub Releases 检测。 */
  boolean base;

  /** 安装本项前要先删掉的另一个 Magisk 模块（目录名即 id）及其显示名。 */
  String replaces, replacesName;

  /** 更新来源：GitHub 仓库的最新 Release（repoAsset 以 * 开头按后缀匹配）。 */
  String repo, repoAsset;

  /** 只适用于 Catalog.FIRMWARE：其它固件上未安装时不能勾选。 */
  boolean fwOnly;

  ItemView box;

  /** render 时给勾选框设的状态；应用类只在用户改过勾选时才处理，避免“部分禁用”的组被一并恢复。 */
  boolean shown;

  Group(String title, String desc, String warn, String... pkgs) {
    this(KIND_PKGS, title, desc, warn, pkgs);
  }

  Group(int kind, String title, String desc, String warn, String... pkgs) {
    this.kind = kind;
    this.title = title;
    this.desc = desc;
    this.warn = warn;
    this.pkgs = pkgs;
  }

  Group mod(String id, String asset) {
    this.modId = id;
    this.asset = asset;
    return this;
  }

  Group companion(String asset) {
    this.companion = asset;
    return this;
  }

  /** 勾选前先弹窗确认，取消则保持未勾选。 */
  Group confirm(String text) {
    this.confirm = text;
    return this;
  }

  /** 取消勾选前先弹窗确认，取消则保持勾选。 */
  Group confirmOff(String text) {
    this.confirmOff = text;
    return this;
  }

  /** 模块在 /data/adb/modules 下的真实目录名；和 modId 不同时才设置（NeoZygisk 与 Zygisk Next 都用 zygisksu）。 */
  Group dir(String dir) {
    this.dir = dir;
    return this;
  }

  String dir() {
    return dir != null ? dir : modId;
  }

  /** 依赖另一个 Magisk 模块（按模块 id）：对方没勾选时本项锁定（点击提示 lockedMsg），本项勾选时也不能取消对方。 */
  Group requires(String modId, String lockedMsg) {
    this.requires = modId;
    this.requiresMsg = lockedMsg;
    return this;
  }

  Group fix() {
    this.fix = true;
    return this;
  }

  Group base() {
    this.base = true;
    return this;
  }

  Group source(String repo, String asset) {
    this.repo = repo;
    this.repoAsset = asset;
    return this;
  }

  Group replaces(String modId, String name) {
    this.replaces = modId;
    this.replacesName = name;
    return this;
  }

  Group fwOnly() {
    this.fwOnly = true;
    return this;
  }

  /** 标题后的类型标签：Magisk 模块带配套 Xposed APK 时（pkgs 非空）两个都有。 */
  String[] tags() {
    if (kind == KIND_APK) return new String[] {"Xposed"};
    if (kind != KIND_MOD) return new String[0];
    return pkgs.length == 0 ? new String[] {"Magisk"} : new String[] {"Magisk", "Xposed"};
  }

  /** Xposed 模块（APK）：包名即标识。 */
  static Group apk(String title, String desc, String warn, String pkg, String asset) {
    Group g = new Group(KIND_APK, title, desc, warn, pkg);
    g.asset = asset;
    return g;
  }
}
