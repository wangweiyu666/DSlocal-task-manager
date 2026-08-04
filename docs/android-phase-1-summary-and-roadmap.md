# Android 实施状态与路线图

> 产品规则以 [`preview.md`](preview.md) 为准；本文件只记录实现状态和下一步。

## 当前基线

| 项目 | 当前值 |
|---|---|
| 协议 | DST1 v1 |
| 应用 | `0.1.0-alpha` |
| Room | v5，显式 `1→2→3→4→5` migration |
| 自动测试 | 83 项单元/集成/迁移测试，API 35 为 17 项仪器测试，API 26 为 5 项 W25 关键流程，10 项 Preview 截图测试 |
| 最近完成 | W26 独立设置页面（仪器测试待外部窗口复测） |
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
```

## 已完成窗口

| 窗口 | 交付 | 提交 | 说明 |
|---|---|---|---|
| W00 | DST1 契约、Schema、共享测试向量 | `54055c5` | [`dst1-test-vectors.md`](dst1-test-vectors.md) |
| W01 | Room v3、连续迁移、模块边界 | `169ca26` | [`android-database-v3.md`](android-database-v3.md) |
| W10 | 计数、计时、信息告知 | `fa3f527` | [`w10-execution.md`](w10-execution.md) |
| W11 | 每日/每周重复实例 | `fa3f527` | [`w11-recurrence.md`](w11-recurrence.md) |
| W12 | 每日结果、积分迁移、结果修订 | `7735418` | [`w12-results.md`](w12-results.md) |
| W20 | 延期、重开、显式日期迁移 | `4266987` | [`w20-delay-and-reopen.md`](w20-delay-and-reopen.md) |
| W21 | 既有 `h`、隐私通知、权限与系统事件重建 | `2b2acd7` | [`w21-local-notifications.md`](w21-local-notifications.md) |
| W22 | 今日分类、任务详情与完整执行交互 | `8b3eb20` | [`w22-today-execution-ui.md`](w22-today-execution-ui.md) |
| W23 | 历史分页、筛选、日历、日期结果与共享只读详情 | `77fa295` | [`w23-history-ui.md`](w23-history-ui.md) |
| W24 | 今日结果、长图与信息告知分享 | `f50151a` | [`w24-results-and-sharing.md`](w24-results-and-sharing.md) |
| W25 | “我的”统计、积分组归档与积分流水 | `f50151a` | [`w25-profile-statistics.md`](w25-profile-statistics.md) |
| W26 | 独立设置、外观、动效、通知与隐私选项 | 本提交 | [`w26-settings.md`](w26-settings.md) |

## 未完成产品面

- Dom 任务生成网页；
- DSTB1 备份与恢复；
- 仪器测试、无障碍和发布加固。

## 依赖顺序

```text
W23 历史 UI ──────┐
W24 结果与分享 ───┼─→ W25 统计 ─→ W26 设置 ─→ W32 发布加固
W31 备份恢复 ─────┘
Dom 网页可独立推进 ──────────────→ W32
```

W22、W23、W24 可并行，但只能消费领域服务，不能在 UI 重写状态、积分或结果规则。

## 后续窗口

### W31：DSTB1 备份与恢复

- UTF-8、zlib、CRC32 和版本化文件头；
- 系统文件选择器导入导出；
- 完全替换、失败回滚和按 ID 合并；
- 通知不备份，恢复后重建。

验收：损坏文件不修改现有数据，完全替换失败可自动恢复。

### W32：发布加固

- migration 全链路、性能和恶意输入测试；
- 真机/模拟器、通知、重启和时区测试；
- Compose 无障碍、字体缩放和主题检查；
- Manifest、权限、隐私和发布构建审核。

验收：无破坏性迁移、无 `INTERNET` 权限、发布构建和关键仪器测试通过。

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
