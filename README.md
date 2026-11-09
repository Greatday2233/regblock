# RegBlocker

模块包名：`io.github.greatday2233.vivodmguard`

在 **采集总闸** 与 **落库口** 拦截 vivo 预装组件 `com.vivo.devicereg`（内嵌彩讯 `cn.richinfo.dm` DM SDK）对应用清单、应用使用时长、分应用流量、基站 / Wi-Fi、SIM 标识的采集与上报。

不改系统分区、不联网、不采集任何数据，日志只写本机缓存目录。

## 它拦的是什么

| 项 | 值 |
| --- | --- |
| 作用域包名 | `com.vivo.devicereg` |
| 总闸类 | `cn.richinfo.dm.DMController`（`implements cn.richinfo.dm.DMFuncImpl`） |
| 落库类 | `cn.richinfo.dm.db.DbManager`，方法 `insert(String, ContentValues)` |
| 上报基址 | `https://a.fxltsbl.com`（配置里 `key_base_config_url` / `key_base_accept_url`） |
| 本地库 | `/data/user/0/com.vivo.devicereg/databases/cmcc_dm.db` |

`DMController` 的采集入口按组匹配，不写死单一命名：

```
应用清单   onAppReport / onAppReportRetry / appInfoReport
心跳       onHeartbeat / onHeartbeatRetry / onHeartbeatSuccess / reportHeartbeat
网络基站   onNetworkCollect / reportNetworkMsg
SIM 注册   onSimStateChange / onRegisterRetry / onRegSuccess
其他       onBluetoothChange / onConnectivityChange / onSnapshotRetry
带 Intent  onBatteryChange / onThirdRequest / onVolteChange
```

共 18 个候选名，命中即挂 hook，且**全部短路**（不执行原方法）。

## 版本自检（这是它和写死 hook 的区别）

模块在注入前先统计候选名命中数：

- 命中 `>= 10`：正常挂 hook，日志写 `probe hit=x/18`、`installed=N`、`targetVer=...`
- 命中 `< 10`：**一个 hook 都不挂**，日志写 `unsupported build (hit<10), no hook installed` 后退出

这样换一台设备不会出现「装了、显示已启用、实际全漏」的假象。

## 已测机型

| 机型 | 系统 | 目标组件 | 自检 | 结果 |
| --- | --- | --- | --- | --- |
| iQOO 13（V2408A） | Android 16 / OriginOS | `com.vivo.devicereg` 5.4.0.0（54000） | `probe hit=18/18`，`installed=19` | 采集与落库全部被拦，双卡 IMS 正常 |

其他机型/版本未测。目标组件升级后若命名变动，日志会给出 `probe` 与 `unsupported` 两种结论，可据此判断是否需要补充候选名。

## 未触及

`DmAlarmReceiver`、`DmOnlineConfigReceiver`、`VDCJobService`、`UpgradeServive`，以及 IMS / APN 注册链（`com.vivo.devicereg.dm.reg.IMS_RETRY_ALARM_SIM1/SIM2` 等）保持原状，双卡通话、短信、数据不受影响。

## 验证

```bash
# 看模块日志
adb shell su -c "tail -20 /data/user/0/com.vivo.devicereg/cache/regblock.log"

# 主动触发一次应用清单采集
adb shell su -c "am force-stop com.vivo.devicereg"
adb shell su -c "am broadcast -a cn.richinfo.dmyk.action.APPINFO -p com.vivo.devicereg"

# 确认本地库不再增长
adb shell su -c "stat -c '%y %s' /data/user/0/com.vivo.devicereg/databases/cmcc_dm.db"
adb shell su -c "sqlite3 /data/user/0/com.vivo.devicereg/databases/cmcc_dm.db 'select count(*) from table_app_collect_list'"
```

预期：日志出现 `block onAppReport #N`，库文件的修改时间与行数保持不变。

## 关闭方式

LSPosed 中停用本模块，或：

```bash
adb shell su -c "am force-stop com.vivo.devicereg"
```

模块无后台常驻、无定时任务、无网络权限。

## 已知限制

- 只覆盖 `com.vivo.devicereg`，不含 `com.mobiletools.systemhelper`（联通那套是独立组件）与 ColorOS 的 `com.coloros.regservice`。
- 目标是 Java 层短路；native 层（`libaes-jni.so`）未 hook，但由于采集与发送都由 Java 层调度，短路总闸后不产生待发数据。
- 目标组件 OTA 升级后类名/方法名可能变化，届时日志会写 `unsupported build`，需要重新适配。
- 需要 LSPosed（Xposed API 82 兼容）。

## 隐私

无 INTERNET 权限，不采集、不上传任何数据。日志仅写
`/data/user/0/com.vivo.devicereg/cache/regblock.log`，超过 256 KB 自动轮转一份 `.1`。

## License

MIT
