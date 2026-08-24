# DStationery 隐私说明 / Privacy Summary

## 离线版

默认 DStationery 不申请网络权限，不收集、上传或出售任何数据。任务、备注、积分、历史和设置仅保存在 Android 应用沙箱。DSTB1 是用户主动导出的未加密备份，可能包含完整任务和历史；分享图片、备份与诊断文件只在用户主动操作时创建。

离线版仅使用通知、设备重启和振动权限。文件访问通过 Android 系统文件选择器完成，不申请照片或广泛存储权限。卸载应用或通过 Android 系统设置清除数据会删除本地数据。

## 联网预发布版

联网版使用邮箱账号和空间成员关系。任务、不可变任务版本、分配、执行结果、通知和安全审计记录会保存到对应 Cloudflare 环境，并按管理员或执行者角色同步。主题等个人偏好仍只保存在当前设备。

联网版不提供端到端加密；运营服务技术上可以读取任务和结果内容。服务实施空间级授权、环境密钥隔离和最小化结构日志，不记录任务正文、完整邮箱、验证码、令牌、密钥或请求体，也不接入产品分析、行为遥测或崩溃正文上传。

管理员可导出整个空间的版本化 DSEXPORT JSON；执行者只能导出自己的账号、实际收到的任务版本、执行记录和通知。导出需要最近 10 分钟内的邮箱验证，文件不加密。

账号删除默认立即冻结并进入 30 天恢复期。重新验证邮箱后可以取消删除，也可以立即永久删除。执行者永久删除会移除可归属于个人的结果载荷，只留下随空间存在、且不含身份或业务内容的结构性 tombstone；唯一管理员删除账号会删除整个空间。客户端在确认待删除、永久删除或成员资格撤销后清除相关缓存、outbox 和会话，但无法瞬时擦除尚未联网的其他设备。

“立即永久删除”表示数据立即从活动系统清除且用户不可恢复。Cloudflare 灾备历史可能在供应商保留窗口内存在并在窗口届满后自然清除；任何灾备恢复都必须先重放独立删除账本，避免已删除数据重新可用。

## English summary

The default DStationery product is fully offline and does not upload user data. The separate connected preview stores account, task, assignment, result, notification, and audit data in its Cloudflare environment for role-scoped synchronization. It is not end-to-end encrypted and uses no product analytics or behavioral telemetry. Role-scoped JSON exports require recent email verification. Account deletion freezes access immediately, is recoverable for 30 days by default, and can be made immediately irreversible in the active system; provider disaster-recovery copies expire within the provider retention window.

问题反馈 / Questions: [GitHub Issues](https://github.com/wangweiyu666/DSlocal-task-manager/issues)。
