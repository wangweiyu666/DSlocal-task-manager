# Android 实施状态与归档路线图

> 产品规则以 [`preview.md`](preview.md) 为准；本文件记录 Android W00–W32 的最终实现状态和归档边界。

## 当前基线

| 项目 | 当前值 |
|---|---|
| 协议 | DST1 / DST1.1 |
| 应用 | `0.1.0-alpha.4`（versionCode 5） |
| Room | v1.6（内部 Schema 6），显式 `1→2→3→4→5→6` migration |
| 自动测试 | 111/111 项 JVM 测试通过；既有 Preview 截图与仪器测试基线保持不变 |
| 最近完成 | DST1.1 重复任务单日例外；`v0.1.0-alpha.4` 开发中 |
| 尚未开放的 DST1 能力 | 无 |

已形成的数据闭环：

```text
DST1 校验与预览
→ Room 原子导入
→ 单次/重复实例
→ 步骤与软件内执行
→ 完成、撤销和积分流水
→ 每日结果与历史修订
→ 延期、重开和日期/分组迁移
→ 本地隐私提醒与系统事件重建
→ 今日分类列表与完整任务执行界面
→ 历史分页、日历、结果与只读任务详情
→ 今日结果、长图与信息告知分享
→ “我的”统计、积分组归档与积分流水
→ 独立设置、固定主题模式与本地偏好
→ DSTB1 本地备份、合并恢复与失败回滚
→ 诊断导出、隐私与许可页面、签名 Release 与发布审核
```

## 已完成窗口

| 窗口 | 交付 | 提交 | 说明 |
|---|---|---|---|
| W00 | DST1 契约、Schema、共享测试向量 | `54055c5` | [`dst1-test-vectors.md`](dst1-test-vectors.md) |
| W01 | Room v1.3、连续迁移、模块边界 | `169ca26` | [`android-database-v3.md`](android-database-v3.md) |
| W10 | 计数、计时、信息告知 | `fa3f527` | [`w10-execution.md`](w10-execution.md) |
| W11 | 每日/每周重复实例 | `fa3f527` | [`w11-recurrence.md`](w11-recurrence.md) |
| W12 | 每日结果、积分迁移、结果修订 | `7735418` | [`w12-results.md`](w12-results.md) |
| W20 | 延期、重开、显式日期迁移 | `4266987` | [`w20-delay-and-reopen.md`](w20-delay-and-reopen.md) |
| W21 | 既有 `h`、隐私通知、权限与系统事件重建 | `2b2acd7` | [`w21-local-notifications.md`](w21-local-notifications.md) |
| W22 | 今日分类、任务详情与完整执行交互 | `8b3eb20` | [`w22-today-execution-ui.md`](w22-today-execution-ui.md) |
| W23 | 历史分页、筛选、日历、日期结果与共享只读详情 | `77fa295` | [`w23-history-ui.md`](w23-history-ui.md) |
| W24 | 今日结果、长图与信息告知分享 | `f50151a` | [`w24-results-and-sharing.md`](w24-results-and-sharing.md) |
| W25 | “我的”统计、积分组归档与积分流水 | `f50151a` | [`w25-profile-statistics.md`](w25-profile-statistics.md) |
| W26 | 独立设置、外观、动效、通知与隐私选项 | `1a552f3` | [`w26-settings.md`](w26-settings.md) |
| W31 | DSTB1 格式、系统文件选择器、合并/替换恢复与回滚 | `6d74ee3` | [`w31-backup-and-restore.md`](w31-backup-and-restore.md) |
| W32 | 诊断、隐私与许可、签名 Release、CI 与发布审核 | `70223d6` | [`w32-release-hardening.md`](w32-release-hardening.md) |

## 归档后的产品边界

- Dom 任务生成网页；
- Google Play 分发、账号、云同步、遥测与联网更新均不纳入当前版本；
- 其他明确后置项见[归档摘要](archive-summary.md#明确后置或不纳入本版本)。

## 依赖顺序

```text
W23 历史 UI ──────┐
W24 结果与分享 ───┼─→ W25 统计 ─→ W26 设置 ─┐
W31 备份恢复 ─────┘                         ├─→ W32 发布加固 ─→ v0.1.0-alpha.1
Dom 网页（独立轨道，未排期）────────────────┘
```

图中记录历史依赖关系。UI 只能消费领域服务，不能重写状态、积分或结果规则。

## W32 完成结论

W32 已完成 migration 链路、恶意输入、模拟器和真机关键流程、字体缩放、Manifest 权限、隐私、许可及签名发布审核。最终标签、APK 校验值和 CI 证据见[归档摘要](archive-summary.md)。当前不预先分配下一窗口；恢复开发时应根据新目标建立新的窗口文档。

## 发布后增量窗口

- W33：新增“靛紫 / 晴空”两种内置 UI 配色、本地选择持久化、对应浅色/深色方案及分享图片配色。详见 [W33 实现说明](w33-ui-palettes.md)。

## 窗口统一交付标准

每个窗口必须：

1. 先更新契约或领域规则，再实现；
2. 保持预览与正式执行共用规划器；
3. 跨 DAO 写入使用一个 Room 事务；
4. 定义幂等、回滚和审计行为；
5. 同步测试和直接相关文档；
6. 运行：

```powershell
./gradlew.bat testDebugUnitTest assembleDebug
git diff --check
```

交接只需报告：完成项、刻意后置、协议/数据库变化、主要文件、测试结果和已知风险。
