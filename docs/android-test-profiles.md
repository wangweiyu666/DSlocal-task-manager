# Android 测试集合

2026-09-09：日常和发布减少默认执行数量，完整覆盖仍可显式运行。测试方法合并不能省略边界或削弱断言；静态条数不代表运行通过。

| 场景 | 命令（仓库根目录） | 当前静态方法数 |
| --- | --- | --- |
| 日常冒烟 | `.\gradlew.bat testProductionDebugUnitTest -PandroidTestProfile=daily --no-daemon` | 37 |
| 发布核心 | `.\gradlew.bat testProductionDebugUnitTest -PandroidTestProfile=release --no-daemon` | 148 |
| 所有独立单元测试 | `.\gradlew.bat testProductionDebugUnitTest --no-daemon` | 174 |
| 已验证源码打包 | `.\gradlew.bat assembleOfflineDebug assembleProductionDebug --no-daemon` | 不附加单元测试 |

实际选择由 `scripts/testing/android-daily.txt` 和 `android-release.txt` 定义；`python scripts/testing/inspect-android-profiles.py` 校验模式能匹配真实测试并报告源代码方法数。参数化测试以 JUnit XML 运行数量为准。未知 profile 会直接报错。

定向验证使用不带 profile 的 `--tests 完整类名`；不要将 `--tests` 和缩小后的 profile 混用，Gradle 会取交集，可能漏掉希望增加的测试。

## 选择与补测

日常集合覆盖日期边界、导入事务、步骤执行、编辑保存、提交回调、网络错误和后台恢复。发布核心增加全部领域规则、协议向量、数据库迁移、备份、积分执行、提醒、诊断与联网测试。数据迁移、备份冲突、撤销、每日实例隔离及同步身份隔离不因节省数量而删除。

发布核心不包括历史查询、统计、非执行页展示、设置与分享渲染单元测试。修改对应功能时必须另跑其测试类；统计查询或索引改变时包括大数据性能测试。修改公共依赖、主题或构建配置时扩大至完整独立套件。CI 对这些路径恢复完整集合；手动 `full_unit_suite` 可显式运行完整一次，`full_android_matrix` 运行两个变体的完整套件。

两个变体继续构建与 Lint，离线网络边界和截图检查保留。设备 UI、读屏及后台同步按受影响场景验证，单元测试不能替代真机证据。发布脚本默认 `-TestProfile release`，可传 `-TestProfile full`；发布证据记录实际 profile，设备验收仍按原发布门禁执行。

两个发布准备脚本都使用 production 单元任务；相同源码、属性和测试输入可复用 Gradle 已通过结果。仅打包已验证版本应直接 assemble，不为生成 APK 再运行完整套件。不得复用不同源码、签名或环境的验证结果。

## 用例审查进度

已完成[231 个入口的必要性审查](android-test-audit-2026-09-09.md)，逐项记录保留理由与已知断言局限。TaskDay 两个 04:00 边界方法合并为一张数据表；DeadlineFormatter 两个方法合并为一张数据表，原有三个时间输入全部保留。合并后实际运行 2 条，通过 2，失败/错误/跳过均为 0。新 `daily` profile 实际运行 36 条，通过 36，失败/错误/跳过均为 0。

已审查的同步与执行保存测试继续保留：UI 回调和数据库提交回调验证不同入口；500ms 防抖与阻塞写入竞态验证不同故障；HTTP 取消、断网、服务端限流、持久化重试截止以及前后台互斥不能互相替代。WorkManager 请求配置测试只能证明配置和登记调用，不能证明厂商系统会在后台调度。

随后增加一条前后台轮询回归并审查其必要性，入口总数为 232，当前静态集合条数见上表；前述 36 条通过是新增前的执行记录。生命周期修复定向运行 `ConnectedSyncEngineTest`、`ConnectedRuntimeTest`、`ConnectedSyncCoordinatorTest`，共 23 条通过，失败/错误/跳过均为 0，未重跑完整集合。

大数据性能测试移出常规集合，按统计/查询改动触发；其 10000 实例与 100000 积分记录的负载断言保留。`release` 清单已静态校验，尚未在本轮完整执行；不能把定向测试或日常集合通过称为发布集合全部通过。
