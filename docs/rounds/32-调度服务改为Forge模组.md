# 32 · 为什么裸 jar 不会加载，以及把它做成真正的 Forge 模组

产物：`tsc-dispatch-mod/build/libs/tsc-dispatch-mod-1.0.0+1.20.1.jar`（258,183 B，reobf）
已安装到客户端：`E:\minecraft\versions\1.20.1-Forge_47.4.28\mods\`

---

## 1. 现象

客户端 `mods/` 里放了 `tsc-dispatch-1.0.0.jar`（223,572 B），但在游戏里看不到它：
模组列表没有条目、没有报错、`latest.log` 里连一行提示都没有。

## 2. 原因：它不是 Forge 模组，Forge 会**静默**跳过它

看了 jar 的内部结构就能确认：

```text
META-INF/MANIFEST.MF     ← 只有这一项，内容是 Main-Class: org.tsc.dispatch.Main
org/tsc/dispatch/…       ← 99 个条目，全是普通 Java 类
META-INF/mods.toml       ← 【不存在】
@Mod 注解的入口类        ← 【不存在】
```

Forge 的模组发现流程是按 `mods.toml` 判定候选文件的：没有这个文件就直接不算模组，
**既不进列表也不报警告**（我翻了客户端 `debug.log` 压缩包，全文没有提到这个 jar）。
所以"没加载"不是配置错、不是版本错，而是这个文件从来就不是给加载器看的。

它原本的设计就是**外部进程**：

```text
java -jar tsc-dispatch-1.0.0.jar run --config config/dispatch.yml
  ├─ 读遥测   → http://127.0.0.1:8888  （模组自带的只读 Web API）
  ├─ 下指令   → http://127.0.0.1:8889  （控制桥，可选）
  └─ 仪表盘   → http://127.0.0.1:8787
```

放不进 `mods/` 是设计使然——它不依赖任何模组内部类，只讲 HTTP。

---

## 3. 解决：把它包成一个真正的模组

新增工程 `tsc-dispatch-mod/`，产物 `tsc-dispatch-mod-1.0.0+1.20.1.jar`：

| 组成 | 说明 |
|---|---|
| `META-INF/mods.toml` | `modId=tscdispatch`，`displayTest="IGNORE_ALL_VERSION"`（无网络协议，客户端可进没有该模组的服务器） |
| `@Mod("tscdispatch")` 入口 | 随**集成服务器/专用服务器**启动与停止 |
| 服务本体 | 直接引用 `../tsc-dispatch/src/main/java` 一起编译，83 个类打进同一个 jar，零第三方依赖 |

`build.gradle` 的关键一行——**同一份源码**，不复制、不漂移：

```gradle
sourceSets.main.java.srcDir '../tsc-dispatch/src/main/java'
```

### 3.1 生命周期

```text
ServerStartedEvent（延迟 1.5 s） → 读/生成 config/tsc-dispatch.yml → 端口守卫 → Service.start()
ServerStoppingEvent              → Service.close()（取消待启动、关调度、关仪表盘、落盘状态）
```

延迟 1.5 s 是有原因的：控制桥在自己的 `ServerStartedEvent`（`LOWEST` 优先级）里才绑定 8889，
服务如果当场启动，第一次探桥必然落空，然后整整一个探针周期（15 s）都显示 "observe"。
实测日志顺序（延迟后）：

```text
03:04:00.965 [mtbridge/]: bridge listening on 127.0.0.1:8889
03:04:01.822 [tsc-dispatch/]: adopted the control bridge token from …\config\mtbridge.json
03:04:02.103 [tsc-dispatch/]: running in-process (dashboard http://127.0.0.1:8787/)
```

### 3.2 三个"不添麻烦"的设计

1. **控制桥 token 自动对齐**：服务配置里 `bridge.auth_token` 为空、而实例里有 `config/mtbridge.json`
   时，自动取它的 token。否则桥回 401，服务静默降级成只读遥测（第一次实测就踩到了）。
2. **端口守卫**：启动前先试绑 `admin.port`（默认 8787）。被占用就记 WARN 并保持 `standby`，
   绝不抢端口、不半初始化、不崩溃——因为另一个调度器可能已经在跑（例如另一个模组自带的集成）。
3. **`/tscdispatch` 命令**：服务不是模组，模组列表里永远看不到它；这条命令是唯一"它到底跑没跑"的答案。

```text
/tscdispatch  →  tsc-dispatch: running | dashboard http://127.0.0.1:8787/ | config config/tsc-dispatch.yml
```

### 3.3 关闭方式

```text
config/tsc-dispatch.disabled  文件存在   → 不启动
-Dtscdispatch.disabled=true             → 不启动
```

---

## 4. 实测（专用服务器，与客户端同一套事件路径）

```text
[tsc-dispatch/]: loaded; the dispatch service starts with the (integrated) server
[tsc-dispatch/]: wrote the default configuration to …\config\tsc-dispatch.yml
[tsc-dispatch/]: running in-process (dashboard http://127.0.0.1:8787/, config …\config\tsc-dispatch.yml)
```

`GET http://127.0.0.1:8787/v1/status`：

```json
{ "service": "tsc-dispatch", "health": { "state": "OK", "actuator": "bridge", "bridgeOnline": true },
  "channels": { "bridge": { "ready": true, "protocol": 1,
    "features": ["dwell_control","deploy_train","set_platform","return_to_depot"] } },
  "dimensions": [{ "world": { "source": "tsc-control-bridge", "vehicleTelemetry": true,
    "stations": 3, "platforms": 12, "routes": 4, "depots": 2, "vehicles": 4 } }] }
```

停服：`Stopping server` → `[tsc-dispatch/]: stopped with the server`，端口全部释放，进程退出码 0。

端口守卫实测（先占用 8787 再启动）：

```text
[tsc-dispatch/]: 127.0.0.1:8787 is already in use by another dispatch service; this instance stays idle
/tscdispatch  →  tsc-dispatch: standby | config config/tsc-dispatch.yml
```

---

## 5. 客户端怎么用（已经替你放好）

```text
mods/tsc-dispatch-mod-1.0.0+1.20.1.jar      ← 新增（真模组）
mods-disabled/tsc-dispatch-1.0.0.jar        ← 原来的裸 jar，移到 mods 外面（它永远不该进 mods）
```

进单人世界后：

1. 聊天栏 `/tscdispatch` → 看到 `running` 就说明服务在游戏进程内跑起来了；
2. 浏览器开 `http://127.0.0.1:8787/` 看仪表盘（`/v1/status`、`/v1/metrics`）；
3. 要调参就编辑 `config/tsc-dispatch.yml`（首次启动自动生成，带完整注释）。

### 如果看到 `standby`

说明 8787 已被别人占用——最常见的是 **MTR RealRail 自带的调度集成**（它的日志里有
`已启动 tsc-dispatch 集成（http://127.0.0.1:8787，轮询 2 秒）`）。两者只能留一个：

- 想用本模组：把 RealRail 的集成关掉（或用它的配置项停用），再进世界；
- 想两个并存：改 `config/tsc-dispatch.yml` 里 `admin.port`（例如 8788），但要接受
  "两个调度器读同一座桥、都可能下指令"的风险——不建议。

---

## 6. 边界

1. **同一 JVM**：服务线程与游戏共享进程。游戏卡住时服务也卡住，反之服务出问题也只影响自己
   （所有异常都被吞并记录，绝不拖垮 tick）。
2. **单机暂停到菜单不退服**，服务继续跑；退出世界才停。
3. 只对 **1.20.1 / Forge 47.x** 编译；MTR 侧只依赖公开 HTTP API，没有编译期依赖。
4. 大线网或需要 7×24 运行时，仍建议外部进程形态（`java -jar`）。两种形态可以共存，
   只要端口不冲突（冲突时本模组会自动 standby，不抢）。

---

## 7. 文件

```text
tsc-dispatch-mod/build.gradle                            ForgeGradle 6，引用服务源码目录
tsc-dispatch-mod/src/main/resources/META-INF/mods.toml   modId=tscdispatch
tsc-dispatch-mod/src/main/java/org/tsc/dispatch/mod/TscDispatchMod.java
tsc-dispatch-mod/build/libs/tsc-dispatch-mod-1.0.0+1.20.1.jar   258,183 B
```
