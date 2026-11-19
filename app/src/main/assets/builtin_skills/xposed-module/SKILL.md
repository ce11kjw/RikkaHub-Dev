---
name: xposed-module
description: >-
  Develop an Xposed module (hook plugin): build.gradle setup, entry registration, hooking methods,
  fixing "module not working", choosing between legacy XposedBridge API and modern libxposed API.
  Targets the maintained framework, now named Vector (formerly LSPosed by JingMatrix).
  Use when writing a hook module, or when a module compiles but does nothing in the target app.
  开发 Xposed 模块（hook 插件）：Gradle 配置、入口注册、hook 方法、排查「模块装了不生效」、
  选 XposedBridge 还是 libxposed API。针对仍在维护的框架，现已改名 Vector（原 JingMatrix 版 LSPosed）。
  当要写 hook 模块，或模块编译通过但目标 App 里毫无反应时使用。
  不含框架层面的故障：管理器打不开、装完开不了机、框架加载不了、Zygisk 环境缺失，
  这些是 android-root 的范围，不要用本技能。
---

# Xposed 模块开发

## 先对齐坐标系：现在该叫 Vector

这件事必须先讲清楚，否则你会照着一份 2023 年的教程写出跑不起来的模块。

| 时间 | 发生了什么 |
|---|---|
| 2023-10 | 原始 `LSPosed/LSPosed` 发布最后一版 v1.9.2，之后归档，README 只写到 Android 14 |
| 2024-07 起 | JingMatrix 接手维护，以 LSPosed 之名发布 v1.10.x，补上 Android 15/16 |
| 2026-03 | 项目**正式改名 Vector**，仓库变为 `JingMatrix/Vector` |
| 2026-08 | Vector v2.2，支持到 Android 17，libxposed API 102 |

**关键结论**：

- 现在说「LSPosed」在技术上指的是 **Vector**（原名）。官方原版已停更。
- 仓库地址：`github.com/JingMatrix/Vector`。旧的 `JingMatrix/LSPosed` 会重定向过来。
- 框架本身是 **Zygisk 模块**，不是 APK。管理器在模块 zip 里，开机后从通知栏打开。
- 安装依赖 Zygisk 环境。Magisk 在设置里开；KernelSU / APatch 要单独装 NeoZygisk 或 ZygiskNext。

**别做的事**：不要从网盘下载「LSPosed 安装包」。这个框架要 hook 全机所有 App，
装来路不明的二次打包版等于把手机交出去。只从 GitHub Releases 拿。

## 两套 API，先选一套再动手

Vector 同时支持两套。**选错了要么编译不过，要么白写**。

| | 传统 XposedBridge | 现代 libxposed |
|---|---|---|
| 包名 | `de.robv.android.xposed.*` | `io.github.libxposed.api.*` |
| 入口 | `assets/xposed_init` 列类名 | `META-INF/xposed/java_init.list` |
| 元数据 | `assets/xposed_init` + `AndroidManifest` 里的 meta-data | `META-INF/xposed/module.prop` |
| 写法 | `XC_MethodHook` 的 before/after | OkHttp 风格的 `intercept(chain)` |
| 辅助类 | `XposedHelpers` | 无，需用 `libxposed:helper` |
| 资源 hook | 支持 | **不支持**（已移除） |

### 怎么选

- 改一个老模块、要复刻现有代码 → **XposedBridge**
- 新项目、要热重载 / 和框架双向通信 / 更好的异常隔离 → **libxposed**
- **别用 libxposed API 100**。它从未正式发布，且已被 v2.0.1 起移除支持。用 101 或 102。

## 现代 API：最小可用模块

### 依赖

```kotlin
dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
```

**必须是 `compileOnly`**。API 由框架在运行时注入，打进 APK 会导致类冲突。

### 入口注册

`src/main/resources/META-INF/xposed/java_init.list`，一行一个全限定类名：

```
com.example.mymodule.MainEntry
```

### 模块元数据

`src/main/resources/META-INF/xposed/module.prop`（Java Properties 格式）：

```properties
minApiVersion=101
targetApiVersion=102
staticScope=true
```

`minApiVersion` / `targetApiVersion` 必填。`staticScope=true` 表示用户不该把这个模块
套到 scope.list 以外的 App 上。

作用域：`META-INF/xposed/scope.list`，一行一个包名。

### Gradle 打包规则（漏了会「装上了但框架认不出」）

```kotlin
android {
    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}
```

### 入口类

```java
public class MainEntry extends XposedModule {
    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        // 框架会自动调 attachFramework()，不要自己调，
        // 也不要在构造函数里做任何初始化 —— 那时框架还没接上
        if (param.isSystemServer()) {
            return;
        }
        log(Log.INFO, "MainEntry", "loaded in " + param.getProcessName());
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!"com.example.target".equals(param.getPackageName())) return;
        try {
            Class<?> cls = Class.forName("com.example.target.MainActivity", true, param.getClassLoader());
            hook(cls.getDeclaredMethod("onCreate", Bundle.class))
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    // proceed() 之前 = 原来的 beforeHookedMethod
                    Object result = chain.proceed();
                    // 之后 = afterHookedMethod
                    return result;
                });
        } catch (Throwable t) {
            log(Log.ERROR, "MainEntry", "hook failed", t);
        }
    }
}
```

### 改参数 / 改返回值 / 拦截

```java
// 改参数：用 proceed(args)
.intercept(chain -> {
    Object[] args = chain.getArgs().toArray();
    args[0] = "hacked";
    return chain.proceed(args);
});

// 改返回值
.intercept(chain -> {
    chain.proceed();          // 先让它跑（或跳过）
    return true;              // 直接返回自己的值，原返回值丢掉
});

// 完全不执行原方法
.intercept(chain -> "fake");
```

### 绕过内联（hook 系统服务时必踩）

ART 会把短方法内联进调用方，hook 挂上去却不触发。这时要 deoptimize：

```java
boolean ok = deoptimize(targetMethod);
if (!ok) log(Log.WARN, "MyEntry", "deoptimize failed");
```

只对确实不触发的场景用，全量 deoptimize 会拖慢启动。

### 调私有方法 / 私有构造

`libxposed` 没有 `XposedHelpers`，用 Invoker：

```java
Invoker<?, Method> inv = getInvoker(cls.getDeclaredMethod("hiddenMethod"));
inv.invoke(instance, arg);   // 绕过访问检查，不用 setAccessible

CtorInvoker<Foo> ctor = getInvoker(Foo.class.getDeclaredConstructor(int.class));
Foo f = ctor.newInstance(42);
```

## 传统 API：最小可用模块

```kotlin
dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
```

`assets/xposed_init`（一行一个类名）：

```
com.example.mymodule.XposedEntry
```

```java
public class XposedEntry implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!lpparam.packageName.equals("com.example.target")) return;
        XposedHelpers.findAndHookMethod(
            "com.example.target.MainActivity", lpparam.classLoader,
            "onCreate", Bundle.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // 调 param.setResult(x) 可以跳过原方法并直接返回
                }
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // param.getResult() / param.setResult()
                }
            }
        );
    }
}
```

还要在 `AndroidManifest.xml` 里声明：

```xml
<meta-data android:name="xposedmodule" android:value="true" />
<meta-data android:name="xposeddescription" android:value="模块说明" />
<meta-data android:name="xposedminversion" android:value="93" />
```

## 「装上了但没反应」按这个顺序查

这是最高频的问题。**九成不是代码问题**。

1. **模块开关开了吗** —— 管理器里模块列表的开关
2. **作用域勾了吗** —— 只勾了模块自己没用，要勾目标 App。这是第一大坑
3. **目标 App 重启了吗** —— 模块在进程启动时注入，改完必须 force-stop 目标 App
4. **scope.list 的包名对吗** —— 后装的版本、双开、分身，包名可能不同
5. **进程名对不上** —— 模块被注入到该包的所有进程，`onPackageLoaded` 里
   要按 `param.getPackageName()` 和进程名过滤，否则会在子进程里做无意义的事
6. **是不是声明成 `system` 作用域** —— 想 hook `system_server` 里的类，
   光把包名加进 scope 无效，要用虚拟包名 `system`
7. **方法被内联了** —— 见上面的 deoptimize
8. **R8 把入口类删了** —— 开了 minify 却没加 keep 规则

### R8 / ProGuard 必加规则（现代 API）

```proguard
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
```

`-adaptresourcefilecontents` 这行很关键：混淆后类名变了，它负责把
`java_init.list` 里的类名同步改掉。漏了就是「构建成功但模块加载不到」。

### 抓日志

模块的日志要看目标进程的 `logcat`，不是管理器里的：

```
logcat | grep -iE "Xposed|LSPosed|Vector|MyEntry"
```

框架自身的详细日志在 `/data/adb/lspd/log/verbose_*`。

## 排查用的判别式

| 现象 | 指向 |
|---|---|
| 管理器里看不到模块 | `java_init.list` 没打进 APK，或 `module.prop` 缺 minApiVersion |
| 模块列表有，开关打不开 | 作用域里有非法包名，或 `staticScope` 与 scope.list 冲突 |
| 目录里没出现日志 | 作用域没勾目标 App，或目标进程没重启 |
| 日志出现了，hook 没效果 | 方法签名不对（重载）、被内联、或类加载器不对 |
| 目标 App 闪退 | hook 里抛异常且用了 PASSTHROUGH；改成 PROTECTIVE 看是否恢复 |
| 一装就开不了机 | 模块 hook 了 system_server 且出错；用 `magisk --remove-modules` 救 |

## 异常模式：默认别用 PASSTHROUGH

- `PROTECTIVE`（默认）：hook 抛异常被捕获记录，调用链照常走，App 不崩。
  **发布版必须用这个**，否则你的 bug 会变成用户的闪退。
- `PASSTHROUGH`：异常直接抛给目标 App。只用来调试，找出「为什么我的 hook 不生效」。

## 模块和 App 通信（现代 API）

传统做法是 `XSharedPreferences`，跨进程读模块自己的 SharedPreferences，但有个硬伤：
**没有变更监听**，且新版已被推迟支持。

现代做法：

| 需求 | 用什么 |
|---|---|
| 小配置、要变更通知 | Remote Preferences（`getRemotePreferences`） |
| 大文件（图片、数据库） | Remote Files（`openRemoteFile` / `listRemoteFiles`） |
| 查看框架名和版本 | `getFrameworkName()` / `getFrameworkVersionCode()` |

能力探测别写死：

```java
long props = getFrameworkProperties();
if ((props & XposedInterface.PROP_CAP_REMOTE) != 0) {
    // 可以用 remote prefs / files
} else {
    // 退回本地存储
}
```

## 调试提效

1. **先确认 hook 点能触发**，再写业务逻辑。用一行 `log()` 验证，别一上来写一堆。
2. **用 hot reload**（API 102+）：只有**恰好一个** Java 入口类时才支持。
   `onHotReloading` 里要主动停掉旧代码的线程和资源，返回 false 可以拒绝这次重载。
3. **看目标 App 的堆栈**：`logcat -b crash` 抓你自己的 hook 抛出的异常。
4. **别在 hook 里做重活**：`onCreate` 里读大文件、发起网络请求，会直接把启动拖慢。

## 边界

这个技能只管**写模块**。

- root 框架本身坏了（模块不生效、管理器打不开、开不了机）→ `android-root`
- 要把 root 从 App 检测里藏起来 → `device-compat`
- 框架装上后和某个 App 冲突 → 先看本技能的排查顺序，再判断是不是 `device-compat`

**不要**写用于绕过付费、破解他人 App 授权的模块。Hook 别人的 App 修改其商业逻辑，
和「给自己的设备补功能」是两件事。
