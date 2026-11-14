package com.beekeeper.micstream;

/** 常驻服务（mitv.service 进程）与设置页、应用共用的 Settings.Global 键。 */
final class Keys {

  /** 开关；设置页写，服务观察。 */
  static final String ENABLED = "beekeeper_mic_enabled";

  /** 连接状态文字，服务写。 */
  static final String STATUS = "beekeeper_mic_status";

  /** 已配对电脑列表，每行：编号、IP、名称、配对时间、上次连接时间、是否正在使用（制表符分隔）。口令不在其中。 */
  static final String PAIRS = "beekeeper_mic_pairs";

  /** 应用要求取消配对：电脑编号，或 "*" 表示全部。服务处理后清空。 */
  static final String UNPAIR = "beekeeper_mic_unpair";

  static final int PORT = 9777;

  private Keys() {}
}
