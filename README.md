# fuckMiGallery

把澎湃OS（HyperOS）相机的「拍完点左下角缩略图」从**小米相册**劫持到你指定的第三方相册。

已在 **Xiaomi 24129PN74C / Android 17 / HyperOS OS4.0.0.22.XOCCNXM / MiuiCamera 6.7.000180.0** 上实测通过。

**前置条件：root + Xposed 框架（Vector / LSPosed），并给本模块授予 root 权限。**

---

## 1. 问题是什么

小米相机打开刚拍的照片时，构造的是**写死包名**的 Intent：

```java
Intent intent = new Intent("com.android.camera.action.REVIEW", uri);
intent.setPackage("com.miui.gallery");     // ← 绕过系统默认应用解析
intent.putExtra("from_MiuiCamera", true);
startActivity(intent);
```

`setPackage()` 让 Intent 跳过 PackageManager 的默认应用解析，点对点投递给小米相册。所以**在系统设置里改默认相册对它无效**。

实测 logcat：

```
START u0 {act=com.android.camera.action.REVIEW dat=content://media/...
  pkg=com.miui.gallery cmp=com.miui.gallery/.activity.ExternalPhotoPageActivity}
  from uid 10135 callingPackage com.android.camera
```

### 关于 HyperOS 的「默认相册」设置

HyperOS 确实有「设置 → 預設應用程式 → 相簿」这一项，但它**不写入标准的 Intent 解析表**。实测：即使在那一项里选中 Google Photos，

```
$ adb shell am start -a android.intent.action.VIEW -d content://media/... -t image/*
→ 仍然弹出 com.android.intentresolver.ResolverActivity 选择器
$ adb shell cmd package resolve-activity -a android.intent.action.VIEW -t image/*
→ 仍然是 ResolverActivity
$ adb shell dumpsys package preferred-activities | grep -A5 'image/\*'
→ 没有 Google Photos 的记录
```

所以模块**不能**依赖这个设置，必须自己提供相册选择器。这也是「跟随系统」选项在本模块里的含义见 [§3.4](#34-跟随系统是怎么解析的)。

---

## 2. 关键实测结论

| 项 | 结论 |
|---|---|
| Intent 动作 | `com.android.camera.action.REVIEW`（不是 `ACTION_VIEW`） |
| 定位方式 | `setPackage("com.miui.gallery")`，`cmp` 由系统解析得出 |
| URI 形态 | `content://media/external_primary/images/media/<id>` —— **MediaStore**，不是相机私有 provider |
| 第三方相册能否读图 | **能**。直接把这个 Intent 投给 Google Photos，图片正常显示 |
| `FLAG_GRANT_READ_URI_PERMISSION` | 仍然要补，但实测 Google Photos 有 `READ_MEDIA_IMAGES`，不加也能读 |
| 多窗口 | 相机改用 `com.android.camera.action.SPILIT_SCREEN_REVIEW`（`SPILIT` 是小米自己的拼写错误，属协议的一部分） |

### 相机内部的两条路径（6.7.000180.0 反编译）

| 混淆类 | 方法 | 角色 |
|---|---|---|
| `bn.C10510g` | `m16483a()` | `GalleryOpenManager` —— 常规拍照走的路径 |
| `p187F1.C2412d3` | `m3629a()` | `GalleryHelper` —— 其他模式入口 |

两处逻辑一致。代码里还有一条走 Google Photos 的分支，但条件是 `ro.product.mod_device` 含 `_global` **且** `UserManager.isManagedProfile()` —— 国行普通用户永远走不到。

---

## 3. 实现

### 3.1 拦截层：hook 框架，不 hook 相机

模块 hook 的是 **Android 框架的 Intent 派发**：

- `android.app.ContextImpl.startActivity`
- `android.app.Activity.startActivity` / `startActivityForResult`
- `android.content.ContextWrapper.startActivity`
- `android.app.Instrumentation.execStartActivity`

共 9 个重载，实测全部安装成功。

为什么不 hook 相机自己的混淆类：

| | hook 框架 | hook 相机类 |
|---|---|---|
| 覆盖路径 | 所有入口，含以后新增的 | 只有已知两处 |
| 版本适配 | 系统 API 签名稳定 | HyperOS 每次更新类名都变 |

> 实测佐证：日志里 `com.android.camera.Camera#startActivity` 和 `#startActivityForResult` 都被命中，说明相机确实经过这些方法。

### 3.2 改写逻辑

命中条件（同时满足）：

1. `action` ∈ `{com.android.camera.action.REVIEW, com.android.camera.action.SPILIT_SCREEN_REVIEW}`
2. `intent.getPackage()` 或 `component.packageName` 是小米相册

处理：

```
setPackage(target) / setComponent(null)
addFlags(FLAG_GRANT_READ_URI_PERMISSION)
```

然后**校验目标能否接收**，不行就逐级降级：

1. 保留原 action（`REVIEW`）试投
2. 换成 `android.intent.action.VIEW` 再试
3. 都不行 → **原样放行**（保持小米相册，不崩溃）

第 2 步是必需的：`REVIEW` 是小米私有 action，多数相册不认。实测 Snapseed 就走这条：

```
redirected REVIEW (com.android.camera.action.REVIEW -> android.intent.action.VIEW) to com.niksoftware.snapseed
```

### 3.3 目标相册怎么选

三级：

1. **用户显式选择** —— 模块 App 里选
2. **系统默认** —— `resolveActivity(VIEW, content://media/... image/*)`；解析到小米系或 ResolverActivity 则视为无
3. **兜底** —— 从已安装相册里挑一个

第 3 步有个坑，实测踩过：**`com.miui.mediaviewer` 也声明了 `REVIEW` action**，如果只按「谁声明 REVIEW」来挑，会选中它——等于又回到小米应用。所以小米系应用（`com.miui.*` / `com.xiaomi.*`）一律排除。

### 3.4 「跟随系统」是怎么解析的

由于 HyperOS 的「相簿」设置不写标准解析表（见 §1），「跟随系统」实际走的是 §3.3 的第 2、3 级。在测试机上结果是 Google Photos：

```
follow-system fallback: com.google.android.apps.photos (declares REVIEW)
```

**注意**：这一项的含义是「优先用系统解析结果，解析不到就挑一个第三方相册」，而不是「精确读取 HyperOS 的那个设置项」。

### 3.5 配置怎么跨进程传给相机

这是整个项目最麻烦的部分。相机进程和模块 App 是两个 UID，而 Android 的 SELinux 隔离让常规方案全部失效——以下每一条都是实测结果：

| 方案 | 结果 |
|---|---|
| ContentProvider | 相机解析不到 authority：`Unknown authority com.fuckmigallery.config`（Android 11+ 包可见性） |
| `XSharedPreferences` + `xposedsharedprefs` | 读到陈旧值，**并且会静默吞掉 App 自己的写入**（UI 显示的值磁盘上根本没有） |
| 模块自己的 `files/` 目录 | 相机 EACCES。文件带 SELinux 类别 `s0:c104,c257,...`，跨 app 域读不到，改 Unix 权限无效 |
| `/data/local/tmp` | 相机**能读**（`shell_data_file`），但普通 App **写不了** |

最终方案：**模块 App 通过 root shell 把配置写到 `/data/local/tmp/fuckmigallery_config`**，hook 直接读这个文件。

```
UI 改设置
   ↓ ConfigStore.write()
本地副本 files/module_config（UI 自己读，无需 root）
   ↓ SuFile / root shell
/data/local/tmp/fuckmigallery_config   ← shell_data_file，任何 app 域可读
   ↓ File.readLines()
相机进程的 HookConfig
```

格式极简，三行文本：

```
1                              ← enabled
com.google.android.apps.photos ← target package
1                              ← verbose log
```

**root 授权失败时 UI 会明确提示**「⚠ 未能写入配置（需要 root 授权），改动不会生效」，不会假装成功。

### 3.6 健壮性

- ThreadLocal 重入保护，避免改写后重新派发时被自己再拦一次
- 全程 try/catch，任何异常都回退到原始 Intent
- 目标为小米系应用时不拦截
- 日志默认关闭，UI 里可开

---

## 4. 项目结构

```
app/src/main/java/com/fuckmigallery/
├── MainHook.kt              # IXposedHookLoadPackage 入口，按包名分发
├── IntentInterceptor.kt     # 命中判定 + 改写 + 降级
├── GalleryResolver.kt       # 目标解析、候选枚举
├── HookConfig.kt            # 相机进程侧读配置
├── ConfigStore.kt           # UI 侧写配置 + root 发布
├── ConfigContract.kt        # 键名常量
├── MiuiPackages.kt          # 小米包名/action 常量
├── ModuleLog.kt             # 日志
├── ModuleApp.kt             # 启动时申请 root
└── ui/MainActivity.kt       # 开关 + 相册选择器
app/src/main/assets/xposed_init   # → com.fuckmigallery.MainHook
```

作用域只有 `com.android.camera`。

---

## 5. 使用

1. 安装 APK
2. Xposed 管理器里启用模块，作用域勾选**相机（com.android.camera）**
3. **在 KernelSU / Magisk 里给本模块授予 root**
4. 打开模块 App → 选择目标相册（或保持「跟随系统」）
5. 强制停止相机
6. 拍照 → 点左下角缩略图

改设置后**不需要重启相机**，下次点缩略图就生效（配置带 1 秒缓存）。

### 为什么需要 root

配置要跨进程传给相机，而 SELinux 让所有非 root 通道都失效（见 §3.5）。模块本来就依赖 root + Xposed，这不是额外负担。

---

## 6. 构建

```bash
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

需要 Android SDK（`platforms;android-36` + `build-tools;36.0.0`），在 `local.properties` 里配 `sdk.dir`。

依赖：Kotlin、AndroidX、Material 3、libsu（root shell）、`compileOnly` 的 Xposed API stub（不打进 APK）。

---

## 7. 已知限制

| 项 | 说明 |
|---|---|
| **必须 root + Xposed** | 无 root 无解 |
| **HyperOS「默认相册」设置不被遵循** | 那个设置不写标准解析表，模块读不到（见 §1）。请在模块里直接选相册 |
| **多窗口** | 相机用 `SPILIT_SCREEN_REVIEW`，第三方相册不认，模块降级成 `ACTION_VIEW`，但分屏转场效果会丢失 |
| **小米相册专用 extras** | `SecureUri`、`anchor_*`、`switch_animation_*` 对第三方相册无效。功能不受影响，只是没有转场动画 |
| **视频** | 走同一条 REVIEW 路径，可以重定向；但相机在 `ActivityNotFoundException` 时的兜底会切到 `com.miui.mediaviewer`，那条路径不拦 |
| **只覆盖相机** | 作用域限定 `com.android.camera` |
| **HyperOS 版本差异** | 本方案 hook 框架层，理论上跨版本稳定。若小米某天改成 native 层直接 `am start`，Java hook 会失效 |

---

## 8. 排查

```bash
adb logcat -c
adb logcat -v threadtime | findstr "FuckMiGallery START u0"
```

然后拍照 → 点缩略图。关注：

- `START u0 {...}` 的 `act=` / `pkg=` / `cmp=` —— 相机这次走的哪条路径
- `FuckMiGallery` 前缀日志 —— 是否命中、改写成什么、是否降级
- `ActivityNotFoundException` / `Permission Denial`

常见日志含义：

| 日志 | 含义 |
|---|---|
| `config: Snapshot(enabled=..., targetPackage=...)` | 读到了配置 |
| `no config at ...` | 读不到配置文件，检查 root 授权 |
| `follow-system fallback: X` | 跟随系统解析到 X |
| `redirected REVIEW (... -> ...) to X` | 成功，注意 action 是否降级 |
| `X cannot handle ...` | 该相册不收这个 action，正在尝试下一个 |

确认相机版本：

```bash
adb shell dumpsys package com.android.camera | findstr versionName
```

---

## 9. 参考

- [Fxxk-MiBrowser](https://github.com/DuhMatt/Fxxk-MiBrowser) —— 同类问题（互传强制小米浏览器），架构参考
- [LuckyTool](https://github.com/luckyzyx/LuckyTool) —— ColorOS 同款功能
- [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler) —— 相机作用域参考
- [MiuiCamera 补丁 A](https://github.com/misakazip-dev/android_device_xiaomi_klimt/blob/master/patches/MiuiCamera/0002-use-installed-gallery.patch)
- [MiuiCamera 补丁 B](https://github.com/Saikrishna1504/device_redmi_begonia/blob/master/blob-patches/MiuiCamera/0002-Support-third-party-galleries.patch)
- [Vector](https://github.com/JingMatrix/Vector) —— 测试所用 Xposed 框架
