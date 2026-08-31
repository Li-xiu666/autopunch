# 自动打卡助手 (AutoPunch)

在「备用机放在公司」的无人值守场景下，按设定时间自动亮屏 → 打开指定考勤 App → 模拟点击打卡按钮，并把结果通过 QQ 邮箱发给你的个人工具。

- 技术栈：Kotlin / Android 8.0+ (minSdk 26) / 无障碍服务 (AccessibilityService) / AlarmManager
- 打卡关键词可配置，对钉钉 / 企业微信 / 飞书等任何 App 均适用（按控件文字匹配）

---

## 一、架构总览

```
┌─────────────── 调度层 ───────────────┐
│ MainActivity(配置) → PunchScheduler  │  设定 HH:mm + ±3min 随机抖动，生成精确时刻戳
│   ↓ AlarmManager.setExactAndAllowWhileIdle
│   ↓ (PendingIntent → UnlockActivity)
│ BootReceiver 开机重建闹钟 / 补打卡    │  BOOT_COMPLETED 时若仍在打卡窗口内则立即执行
└─────────────────────────────────────┘
┌─────────────── 执行层 ───────────────┐
│ UnlockActivity  亮屏 + 解除非安全锁屏  │  请求 Keyguard dismiss，拉起考勤App
│   ↓ PunchService.start(action=TRIGGER)
│ PunchService(无障碍) 轮询前台控件树     │  通过 text/contentDescription 匹配关键词
│   ↓ NodeFinder.collect → closestClickable → performAction(ACTION_CLICK)
│   链式兜底: 候选逐个点击 → 已打卡校验 → 超时重拉App(最多3次) → 判失败
└─────────────────────────────────────┘
┌─────────────── 反馈/保活层 ──────────┐
│ MailSender   JavaMail SMTP 465 (QQ)  │  成功/失败均发邮件，含页面控件采样
│ KeepAliveService 前台服务            │  常驻通知 + 降低被杀概率
│ PunchLog     应用内运行日志           │  filesDir/punch_log.txt
└─────────────────────────────────────┘
```

## 二、AndroidManifest.xml 中的无障碍服务配置（核心）

界面上能看到、能被系统受托管理的关键是 `BIND_ACCESSIBILITY_SERVICE` 权限和 `xml/accessibility_service_config.xml`：

```xml
<!-- 申明无障碍服务（必须带 BIND_ACCESSIBILITY_SERVICE 权限） -->
<service
    android:name=".service.PunchService"
    android:description="@string/accessibility_service_description"
    android:exported="false"
    android:label="@string/accessibility_service_label"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

```xml
<!-- app/src/main/res/xml/accessibility_service_config.xml -->
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeWindowsChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagRetrieveInteractiveWindows|flagIncludeNotImportantViews|flagReportViewIds"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:notificationTimeout="100" />
```

字段说明：

| 属性 | 作用 |
|---|---|
| `canRetrieveWindowContent` | 允许读取前台窗口控件树，这是拿节点的前提 |
| `accessibilityFlags` | `flagRetrieveInteractiveWindows` 取「交互窗口」；`flagIncludeNotImportantViews` 连无意义控件也拿（钉钉很多按钮的 text 藏在非重要视图里）；`flagReportViewIds` 返回 viewId 便于按 id 匹配 |
| `canPerformGestures` | 可执行手势（备用能力，本项目用 performAction(ACTION_CLICK) 已足够） |
| `notificationTimeout` | 事件去抖时间，100ms 足够灵敏 |

开启入口：`MainActivity` 的「无障碍设置」按钮 → 系统设置 → 无障碍 → 自动打卡助手 → 开启。此后应用才有资格接收 `onAccessibilityEvent`。

## 三、如何在代码中获取 UI 控件节点

核心在 `service/NodeFinder.kt` 与 `service/PunchService.kt`：

```kotlin
// 1) 取当前前台窗口的根节点
val root: AccessibilityNodeInfo? = rootInActiveWindow
// 2) 从根节点按关键词 BFS 收集候选（text / contentDescription / viewId 任一命中）
val candidates = NodeFinder.collect(root, keywords)   // keywords = ["下班打卡","打卡","签到",...]
// 3) 对每个候选向上找一个可点击的祖先
val button = NodeFinder.closestClickable(candidate)
// 4) 用 ACTION_CLICK 模拟点击（等价于手指点按，不注入系统 input 事件，更隐蔽且不依赖 root 权限）
button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
```

遍历与点击都在 `PunchService` 里由两个驱动源触发：

- **轮询驱动**：`PunchService.startPunch()` 启动一个 75s 的超时轮询 Runnable，每 1.5~2.2s 取一次 `rootInActiveWindow` 判断。命中「已打卡/打卡成功/考勤完成」文本即判定成功并停手。
- **事件驱动**：收到目标 App 的 `TYPE_WINDOW_STATE_CHANGED / TYPE_WINDOW_CONTENT_CHANGED` 事件时立即尝试一次点击，加快反应速度。

窗口不在目标 App 时：连拉 3 次 `packageManager.getLaunchIntentForPackage()`，仍无则报失败。每次点击后留冷却期等待页面刷新，再校验/点下一个候选，避免误触或重复点击。

## 四、关键设计说明

1. **时间抖动**：`PunchScheduler.computeExactTime()` 在 `HH:mm` 基础上加 `[-3min, +3min]` 的随机偏移，每次调度都用 `Random.nextLong` 重新生成，避免固定节奏。抖动范围可在 `JITTER_MS` 常量调整。
2. **亮屏解锁**：调度用的是 `PendingIntent.getActivity(... UnlockActivity)` + `AlarmManager.setExactAndAllowWhileIdle`，即使灭屏/Doze，闹钟一到系统会把 `UnlockActivity` 拉起来（自己对 targetSdk 34 的「后台启动 Activity」限制免疫）；`UnlockActivity` 里 `setShowWhenLocked + setTurnScreenOn` 亮屏，再 `requestDismissKeyguard` 解除非安全锁屏。
3. **保活**：
   - `KeepAliveService` 前台服务常驻（通知渠道 `autopunch_keepalive`），`MainActivity` 启动时与 `BootReceiver` 开机时都会拉起；
   - `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 白名单引导（「电池白名单」按钮）；
   - **国产 ROM 额外提示**见下文。
4. **精确闹钟权限 (Android 12+)**：`SCHEDULE_EXACT_ALARM` 需要用户在系统设置单独授权，`MainActivity` 会自动引导跳转到 `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`；若被拒绝，代码降级为 `setAndAllowWhileIdle`（会有分钟级偏差，README 建议务必授权）。Android 14 下该权限会在卸载/重装后被回收，需重新授权。
5. **异常处理**：
   - 超时（75s）→ 按失败上报；
   - 目标 App 未安装 / 前台窗口一直不是目标 → 失败上报；
   - 找不到打卡节点（页面加载失败、布局大改）→ 轮询 4 次仍无 → 失败上报；
   - 安全锁屏（PIN/图案）→ 立即中止并在邮件中说明原因；
   - 邮件发送结果会写回运行日志；忘记配置邮箱时结果会存到应用内日志并弹出系统通知。

## 五、编译与使用

1. 用 **Android Studio** 打开本目录（要求 JDK 17+，AGP 8.2.2 会自动下载 Gradle 8.4）。首次 Sync 后即可 Build APK。
   - `local.properties` 里写好 `sdk.dir`，或让 Android Studio 自动配置。
2. 安装到备用机。**首次使用必须完成 4 件事**：
   1. 打开 App → 填「打卡时间 / 目标考勤应用（填应用名称如“钉钉”，也可直接填包名如 `com.alibaba.android.rimet`）/ 关键词 / 邮箱信息」→ 点「保存并调度」——保存后底部会显示识别到的包名，未识别会提示你核对；
   2. 点「无障碍设置」开启「自动打卡助手」；
   3. 点「电池白名单」放行（MIUI 还要在系统「省电策略」里选「无限制」；华为请把 App 加入应用市场「自启动」，并在「电量-应用启动管理」关闭自动管理）；
   4. Android 12+ 按弹窗授予「闹钟和提醒」权限。
3. 先点「立即测试打卡流程」端到端验证：亮屏→解锁→拉起App→点击→收到邮件。
4. 建议先在办公室做 1~2 次测试（上班/下班各一次），确认布局匹配后再投入使用。

## 六、真机注意事项 / 已知边界

- **备用机不要设置 PIN / 图案 / 指纹锁**，否则无法自动解锁，只能保持最简「无锁」或「滑动」锁屏模式。这不是技术缺陷，是 Android 安全设计。
- 锁屏状态若 App 有独立登录态过期（token 失效），需要 App 自愈或人工介入，本工具不处理登录。
- 国产 ROM（小米/华为/vivo/OPPO）后台策略严格，即使本应用是系统辅助服务也可能被「自动清理」：建议放入后台锁定 + 系统「自启动」白名单 + 电池无限制，前台服务通知保持显示。
- 关键词匹配为「包含」关系（如 `打卡` 同时命中「上班打卡」「下班打卡」）。建议按顺序填 `下班打卡,上班打卡` 优先精确项——`PunchService` 会按收集顺序逐个尝试，避免上班时段误点「下班打卡」。
- 打卡后页面校验词固定为 `打卡成功/已打卡/打卡完成/考勤完成`，若你的考勤 App 文案不同，请修改 `PunchService` 里的 `doneKeywords`。
- 应用签名后每次升级包名不变即可直接覆盖安装；卸载会同时回收无障碍与精确闹钟授权，需重新完成「首次使用」流程。

## 七、代码索引

| 文件 | 职责 |
|---|---|
| `Schedule/PunchScheduler.kt` | 抖动计算、AlarmManager 调度 |
| `Schedule/BootReceiver.kt` | 开机重建闹钟、窗口期内补打卡 |
| `config/TargetResolver.kt` | 应用名称→包名解析（支持按名称或包名匹配目标 App） |
| `UnlockActivity.kt` | 亮屏解锁、拉起目标 App |
| `service/PunchService.kt` | 无障碍执行引擎（点击 / 重试 / 判定成功） |
| `service/NodeFinder.kt` | 控件树遍历、可点击最近祖先查找 |
| `service/KeepAliveService.kt` | 前台常驻服务 |
| `service/WakeUpUtils.kt` | WakeLock 保持屏幕 |
| `mail/MailSender.kt` | QQ SMTP(465/SSL) 输出结果 |
| `log/PunchLog.kt` | 应用内运行日志 |
| `MainActivity.kt` | 配置 / 权限 / 测试入口 |

## 八、QQ 邮箱 SMTP 授权码

1. 登录 QQ 邮箱网页版 → 设置 → 账户；
2. 「POP3/IMAP/SMTP/Exchange/CardDAV/CalDAV 服务」→ 开启 SMTP；
3. 按引导生成「授权码」（16 位字母），填入 App 的「授权码」字段——**不是 QQ 登录密码**。

本配置固定连接 `smtp.qq.com:465`，如需其他邮箱服务商，改 `mail/MailSender.kt` 里的 `HOST/PORT` 即可。