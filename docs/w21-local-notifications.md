# W21 本地提醒与通知实现说明

> 前置：W00、W01、W11、W20
> 协议：仅实现 DST1 v1 已有 `h`，没有新增字段或语义
> 数据库：继续使用 Room v1.3，无 migration

## 交付

- 将既有 `h` 从能力门禁接入任务模型及定义/实例快照；
- 以 `reminder_record` 为事实来源，保存计划、跳过、取消和送达状态；
- 使用 `AlarmManager.setAndAllowWhileIdle` 安排非精确提醒；
- 闹钟触发时重新读取 Room，阻止旧计划、结束实例和重复事件发送通知；
- 支持重启、系统时间、时区和应用升级后的全量核对；
- Android 13+ 仅在用户主动点击时请求通知权限，拒绝不影响其他功能；
- 通知只显示必做/选做通用文案，点击进入对应任务详情。

## 计划规则

```text
理论时间 = 截止本地时间 - h 分钟
```

仅当理论时间不早于 `publishedAt` 且晚于当前时间、实例仍为 `NOT_STARTED/PENDING` 时安排。已完成、未完成、撤销、发布前和已过期提醒不会补发。

提醒状态：

```text
SCHEDULED
DELIVERED
SKIPPED_BEFORE_PUBLISHED
SKIPPED_PAST
SKIPPED_PERMISSION
CANCELLED
```

Room 提交和系统闹钟无法组成同一事务，因此 Room 始终是事实来源；应用前台和系统事件核对负责修复中断后的投影。

## 隐私与权限

- 不申请 `INTERNET`、`SCHEDULE_EXACT_ALARM` 或 `USE_EXACT_ALARM`；
- 通知不包含名称、描述、步骤、积分、分组、Dom、正文、备注或内部 ID；
- 无权限时到期记录为 `SKIPPED_PERMISSION`，授权后只安排仍在未来的提醒；
- Receiver 使用显式不可变 PendingIntent，通知 ID 和闹钟身份由实例键稳定生成。

## 验证

- 协议统一向量：合法 `h` 为 Android `VALID`，既有非法向量保持原错误；
- 领域测试：发布时间、过期、结束状态和截止时提醒；
- Room/Robolectric：快照、幂等核对、送达和权限拒绝；
- 完整 `testDebugUnitTest assembleDebug`；
- API 26/33/35 模拟器用于通知兼容、运行时权限及系统事件回归。

## 后续边界

- W22 继续拥有完整今日与详情交互，W21 只提供通知点击定位；
- W31 不备份 `reminder_record`，恢复后重新生成；
- W32 已扩展真实重启、时区、系统省电和 Android 13+ 真机验证。
