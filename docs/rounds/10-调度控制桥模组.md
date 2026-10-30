# 10 · 调度控制桥模组（独立附属）

> **v1.1 修订**：本文描述的是 v1.0 设计。真机联调后的修复（启动竞态、多维 tick、意图 TTL、遥测字段、ETag、常量时间鉴权等）见 「11-联调整改记录（控制桥 v1.1）.md」，以那份为准。

外部调度服务的执行端。独立模组，不依赖任何现有附属模组；同时对外提供 Java API，供其它模组直接调用。

- 产物：`mtbridge/build/libs/mtbridge-1.0.0+1.20.1.jar`（64,335 字节，已 reobf）
- 目标：MC 1.20.1 + Forge 47.x，MTR 4.0.0+
- 工程：`mtbridge/`，15 个源文件，零第三方依赖

---

## 1. 工程结构

```text
mtbridge/
├── build.gradle / settings.gradle / gradle.properties
├── src/main/resources/META-INF/mods.toml     # modId=mtbridge, 依赖 mtr [4.0.0,)
└── src/main/java/org/mtbridge/
    ├── api/                    # 对外 Java API（其它模组只依赖这一层）
    │   ├── DispatchBridgeAPI   静态门面：getInstance()
    │   ├── BridgeApi           接口：选台/发车/回场/停站/监听
    │   ├── BridgeResult        结果（APPLIED/DEFERRED/REJECTED/FAILED/UNAVAILABLE）
    │   ├── BridgeEvent         事件（HTTP/COMMAND/API 三种来源）
    │   ├── BridgeListener      监听器
    │   ├── BridgeStatus        /mtbridge status 的数据源
    │   └── TrainState          只读列车视图
    ├── core/                   # 与加载器无关的执行核心（只依赖 TSC + JDK）
    │   ├── BridgeService       幂等、意图队列、遥测、事件派发
    │   ├── GameActions         四个原语（真实 TSC API 调用）
    │   ├── BridgeCommand       指令值对象
    │   ├── BridgeHost          平台层契约（simulator 列表 + 主线程调度）
    │   ├── Reflect             字段访问（无 mixin）
    │   └── Json                极简 JSON
    ├── http/
    │   └── BridgeHttpServer    /dispatch/v1/*，JDK HttpServer
    ├── config/
    │   └── BridgeConfig        config/mtbridge.json
    └── forge/                  # 唯一的加载器相关部分
        ├── MtBridgeMod         @Mod，生命周期、事件总线
        ├── ForgeHost           定位 Simulator + MinecraftServer.execute
        ├── ForgeBridgeApi      BridgeApi 实现
        └── BridgeCommands      /mtbridge ...
```

分层原则：**`api` 与 `core` 不 import 任何 Minecraft/Forge 类**，只有 `forge` 与 `config` 依赖加载器。
Fabric 版只需要再写一个 `fabric` 包（约 4 个类），核心与协议完全不变。

---

## 2. 通信层

### 2.1 HTTP 服务器选型

选 **JDK 自带 `com.sun.net.httpserver.HttpServer`**，理由按重要性排序：

1. **零依赖**：模组 jar 里不 shade Netty/Undertow/Jetty。模组环境本来就加载了多个 HTTP 栈，
   再带一个极易引发类加载冲突，且体积会从 64 KB 涨到数 MB。
2. **够用**：四个端点、每秒个位数请求，固定 2 线程池即可；不需要 WebSocket（协议是请求-响应）。
3. **生命周期清晰**：随服务器启动/停止创建销毁，不会在单机切换世界时残留端口。

### 2.2 端点（协议 v1，与外部服务的 `BridgeSource` 一一对应）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/dispatch/v1/capabilities` | `{protocol:1, features:[...], telemetry:[...], dimensions, ready}` |
| GET | `/dispatch/v1/state?dimension=N` | 完整世界快照（车站/站台/线路/车厂/车辆） |
| POST | `/dispatch/v1/command?dimension=N` | 单条指令 |
| GET | `/dispatch/v1/health` | 桥自身状态（执行数、失败数、挂起意图数） |

鉴权：配置了 `token` 时要求 `Authorization: Bearer <token>`，否则 401。
**空 token 等于无鉴权**，配置默认绑定 `127.0.0.1`，跨机部署必须配 token 并走 TLS 隧道。

### 2.3 请求处理链路（核心伪代码，实际实现见 `BridgeHttpServer`）

```text
onRequest(exchange):
    if token set and Authorization != Bearer token: 401
    switch path:
      /capabilities -> 静态能力表
      /state        -> onServer(() -> service.telemetry(simulator(dimension)))
      /command      -> parse -> command
                       onServer(() -> service.execute(simulator, command, HTTP))
                       wait 2.5s -> 200 {ok, outcome, applied, retryable, detail}
                       timeout  -> 202 {ok:true, applied:false, retryable:true}   # 不阻塞连接
```

**为什么所有读写都要过 `onServer`**：TSC 的 `Simulator` 不是线程安全的，它的所有状态变更都发生在
服务器主线程的 tick 里。HTTP 线程只做解析与鉴权，任何触碰模拟器的操作都通过
`MinecraftServer.execute()` 投递过去，并用 `CompletableFuture` 把结果带回来。

### 2.4 幂等

```text
key = body.key ?: (kind + "|" + trainId + "|" + target)
cache: key -> (result, timestamp), TTL 10 分钟
命中 -> 直接返回原结果（不重复移动列车）
```

外部服务在网络超时会重试；桥必须让“重试”等价于“查结果”，而不是“再开一次车”。

### 2.5 Fail-safe

| 场景 | 行为 |
|---|---|
| 世界未加载 / 服务器停止中 | HTTP 服务器不运行（随 ServerStarted/ServerStopping 启停），连接直接拒绝 |
| `Simulator` 定位失败 | `ready=false`，`/capabilities` 仍返回但 `ready=false`，所有指令 `UNAVAILABLE` |
| 某维度未加载 | `/state?dimension=N` 返回 503，外部服务对该维度降级 |
| 桥进程内异常 | 捕获后返回 500 + `lastError`，不中断 tick |
| 外部服务长时间不在线 | 桥不做任何事：所有覆盖都是**一次性意图**，没有“持续压制”状态，铁路自然回到原生行为 |
| 服务器关闭 | `close()` 立即停止 HTTP，`host.detach()` 清空引用；MTR 原生逻辑不受任何影响 |

关键点：**桥不持有任何会改变列车行为的长期状态**。指令要么当次生效，要么进意图队列并在
过期后自动丢弃。这就是“通信中断 = 自动回退原生行为”的实现方式，而不是靠一个超时开关。

---

## 3. 指令执行层（难点）

### 3.1 先看真实 API：MTR 4.0.5 到底给了什么

用 `javap` 对发布版 jar 取证（不是凭记忆）：

| 成员 | 可见性 | 结论 |
|---|---|---|
| `Siding.generateRoute(Platform first, Platform last, int stopIndex, long altitude)` | public | **唯一**的路径注入入口 |
| `Siding.tick()` | public | 路径生成是增量的，需逐 tick 推进 |
| `Siding.clearVehicles()` / `addDeparture(long)` | public | 清空/排班 |
| `Siding.vehicles` | **private** | 枚举车辆必须反射 |
| `Vehicle.startUp(long departureIndex, long sidingDepartureTime)` | public | 强制发车 |
| `Vehicle.getIsOnRoute()` / `getDepartureIndex()` | public | 状态判定 |
| `Vehicle.vehicleExtraData` | public final | 站台/线路信息 |
| `Vehicle.speed / railProgress / elapsedDwellTime / departureIndex / sidingDepartureTime` | **protected** | 反射读写 |
| `VehicleExtraData.immutablePath` | public final | **不可变路径**——这是所有限制的根源 |

**核心事实**：列车的路径由车厂（Siding）生成后，对其整个运行周期是不可变的。
所以“改下一站台”本质上不是改字段，而是**重新生成路径**，而重新生成只能在车辆全部回库时进行
（`generateRoute` 会清空该侧线的车辆集合）。

### 3.2 四个原语怎么落地

| 指令 | 车辆在库（`!getIsOnRoute()`） | 车辆在途 |
|---|---|---|
| `set_platform` | `generateRoute(platform, null, -1, altitude)` → `DEFERRED` 等路径收敛 | 无法立即生效：登记意图，等回库时执行，返回 `DEFERRED` |
| `deploy_train` | 先按目标站台重建路径，再 `startUp(idx, now)` 发车 | `REJECTED`（已在跑） |
| `return_to_depot` | `departureIndex = -1`（不再排班）→ `APPLIED` | 登记意图，跑完回库后置 `-1` → `DEFERRED` |
| `dwell_control` | — | 停站时每 tick 把 `elapsedDwellTime` 压回 0，直到释放 |

```java
// 发车（真实调用）
final long departureIndex = Math.max(0L, Reflect.getLong(vehicle, "departureIndex"));
Reflect.setLong(vehicle, "departureIndex", departureIndex);
Reflect.setLong(vehicle, "sidingDepartureTime", now);
vehicle.startUp(departureIndex, now);

// 改台（仅当该侧线无在途车，否则 generateRoute 会把它们清掉）
if (GameActions.activeVehicles(siding) > 0) return REJECTED("siding still has active trains");
siding.generateRoute(platform, null, -1, depot.getCruisingAltitude());
```

### 3.3 主线程调度

```java
public <T> CompletableFuture<T> onServer(Supplier<T> task) {
    CompletableFuture<T> future = new CompletableFuture<>();
    MinecraftServer server = this.server;
    if (server == null) { future.completeExceptionally(...); return future; }
    server.execute(() -> {           // 投递到 MC 主线程
        try { future.complete(task.get()); }
        catch (Throwable t) { future.completeExceptionally(t); }
    });
    return future;
}
```

- HTTP 线程永不触碰 `Simulator`；
- `service.tick(simulator)` 挂在 `TickEvent.ServerTickEvent(END)`，本身就在主线程，因此意图队列的
  推进不需要额外调度；
- 每个意图都有 deadline（选台 30 分钟、发车 10 秒、回库 60 分钟），过期即丢弃并上报事件，
  避免“半小时后突然执行一条旧指令”。

### 3.4 边界情况

| 情况 | 处理 |
|---|---|
| 列车正在进站/停站 | `deploy` 直接 `startUp` 会被 `doorCooldown` 挡住 → 返回 `FAILED` 并说明；意图重试到 deadline |
| 列车在途想改台 | `DEFERRED` + 意图，回库时生效；同时在 `message` 里说明原因 |
| 侧线还有别的在途车 | `REJECTED`（重建会清空车辆集合），不冒险 |
| 目标站台/列车 id 不存在 | `REJECTED`，不会创建任何状态 |
| 停站控制与原生计时打架 | 只在“停住”时写 `elapsedDwellTime`，释放后不再触碰；超时自动释放 |
| 桥重启 | 意图不持久化（内存态），重启即清空——宁可不执行，也不执行过期意图 |

---

## 4. 开放 API

```java
DispatchBridgeAPI.getInstance().setTrainPlatform("1002", "0000000000000042");
```

```java
public interface BridgeApi {
    boolean isAvailable();
    BridgeStatus status();
    List<TrainState> trains();
    Optional<TrainState> findTrain(String trainId);
    CompletableFuture<BridgeResult> setTrainPlatform(String trainId, String platformId);
    CompletableFuture<BridgeResult> setTrainPlatform(UUID ridingEntity, String platformId);
    CompletableFuture<BridgeResult> deployTrain(String trainId, String platformId);
    CompletableFuture<BridgeResult> returnToDepot(String trainId, String sidingId);
    CompletableFuture<BridgeResult> setDwell(String trainId, int seconds); // -1 保持, 0 释放
    void addListener(BridgeListener listener);
    void removeListener(BridgeListener listener);
}
```

```java
DispatchBridgeAPI.getInstance().addListener(event ->
    LOGGER.info("{} {} -> {}", event.kind(), event.trainId(), event.result().outcome()));
```

设计取舍：

- **门面永不为 null**：未安装时返回一个全部 `UNAVAILABLE` 的实现，调用方不需要判空。
- **结果而非布尔**：`DEFERRED` 是显式结果，避免调用方把“排队中”误当成“已生效”。
- **`UUID` 重载**：MTR 的列车用 long id，没有 UUID；这个重载实际是“玩家所乘列车”的解析，
  通过 `iterateRidingEntities` 找到乘客所在车。
- **事件在服务器线程派发**，监听器异常被吞掉并记录，不让第三方代码拖垮调度。

---

## 5. 兼容性与稳定性

### 5.1 Forge / Fabric

- `api` + `core` + `http` **不依赖任何 Minecraft 类**（只依赖 TSC 与 JDK），已通过编译验证；
- `forge` 包只做三件事：定位 `Simulator`、把任务投递到主线程、注册指令与事件；
- Fabric 版只需替换这三件事（`ServerLifecycleEvents`、`CommandRegistrationCallback`、同样的反射定位），
  协议层与执行层零改动。

### 5.2 版本漂移

不用 mixin，全部通过 `Reflect` 的缓存 `Field` 访问。字段改名/改类型时：

- 抛 `IllegalStateException("no field 'x' on ...")`，被 `BridgeService` 捕获 → `FAILED` + `lastError`；
- `/mtbridge status` 与 `/dispatch/v1/health` 立即可见，不会静默失效；
- `ForgeHost.locate()` 采用“字段 → 方法 → 集合字段”三级探测，宿主启动流程变化时降级为
  `ready=false` 而不是崩溃。

### 5.3 未验证项（如实说明）

- 已在本地完成 **编译 + reobf + 打包**（`build/libs/mtbridge-1.0.0+1.20.1.jar`，64 KB，含 `mods.toml`）；
- **未**在真实 Forge 服务器 + MTR 世界中联调：本环境没有可运行的 Forge 服务端实例。
  首次实机验证建议按此顺序：`/mtbridge status` → `capabilities` → `state` → 在库列车上试 `deploy` →
  在途列车上试 `set_platform`（应返回 `DEFERRED`）。

---

## 6. 构建

```powershell
cd mtbridge
$env:JAVA_HOME='...\jdk-17'
$env:GRADLE_USER_HOME='...\gradle-home'     # 已有 Forge 依赖缓存时可离线
.\gradlew.bat --offline build
# -> build/libs/mtbridge-1.0.0+1.20.1.jar
```

实测：`BUILD SUCCESSFUL in 15s`，产物含 25 个 class、`mods.toml`、`pack.mcmeta`。
