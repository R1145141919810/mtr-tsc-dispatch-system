# MTR / TSC 动态调度系统（tsc-dispatch-system）

面向 **Minecraft Transit Railway 4.0.x + Transport Simulation Core** 的外部动态调度系统：
按实时客流/占用/时刻表决定“哪台车、去哪个站台、什么时候走”，再通过控制桥把决策落到游戏里。

> 目标环境：Minecraft **1.20.1** · Forge **47.x** · MTR **4.0.0+**（TSC）
> 不含任何第三方依赖，服务本体是纯 JDK 17 的单个 jar。

---

## ⚠️ 依赖：必须与另外两个模组一起使用

**本仓库只是“调度决策端”。** 单独安装虽然游戏能启动、面板也能打开，但**功能是不完整的**（没有前端判定、也没有执行通道，调度器只能算不能做）。完整功能需要三件套：

| 角色 | 仓库 | 说明 |
|---|---|---|
| 🖥 **前端 / 判定** | [**R1145141919810/mtr-realrail**](https://github.com/R1145141919810/mtr-realrail) | 列车限速与信号判定、车厂优化配置，把判定结果与站台信息提供给调度器；同时是这套系统的可视化入口 |
| ⚙️ **执行端 / 桥** | [**R1145141919810/mtr-control-execute-brige**](https://github.com/R1145141919810/mtr-control-execute-brige) | 控制桥：把调度决策翻译成游戏内动作（`set_platform` / `deploy_train` / `return_to_depot` / `dwell_control`），并为调度器提供车辆级遥测 |
| 🧠 **决策端（本仓库）** | `mtr-tsc-dispatch-system` | 采样世界状态 → 计算决策 → 通过桥下发 → 记录并暴露指标与面板 |

> **一句话：RealRail 是“眼睛和规则”，控制桥是“手”，本仓库是“大脑”。三者缺一，系统只能空转。**

---

## 架构

```text
┌──────────────────────────── 游戏进程（服务端 / 客户端单机） ────────────────────────────┐
│                                                                                       │
│   MTR / TSC  ◄── 只读 HTTP API (:8888) ──┐                                            │
│                                          │                                            │
│   mtr-realrail（前端/判定）───────────────┼──► 站台策略、车厂优化、信号判定              │
│                                          │                                            │
│   mtr-control-execute-brige（桥）◄───────┼──┐  写通道 + 车辆遥测 (:8889)               │
│                                          │  │                                         │
└──────────────────────────────────────────┼──┼─────────────────────────────────────────┘
                                           │  │
                            ┌──────────────▼──┴───────────────┐
                            │  tsc-dispatch（本仓库）          │
                            │  · 轮询遥测（桥优先，退回原版）   │
                            │  · 时间依赖最短路 / 占用窗口     │
                            │  · 决策 → 幂等命令 → 桥 / RCON   │
                            │  · http://127.0.0.1:8787 面板     │
                            └──────────────────────────────────┘
```

数据流：`遥测（桥 / 原版 HTTP） → 世界快照 → 决策器 → 命令总线（幂等 + 租约 + TTL） → 控制桥 → 游戏`。

---

## 两种运行形态

| 形态 | 产物 | 适用 |
|---|---|---|
| **A. 独立进程** | `dist/tsc-dispatch-1.1.0.jar` | 专用服务器、需要与游戏进程隔离、7×24 运行 |
| **B. Forge 模组（进程内）** | `dist/tsc-dispatch-mod-1.0.0+1.20.1.jar` | 客户端单机 / 不想再开一个 JVM；随（集成）服务器启停 |

两种形态功能一致，**二选一**即可（同时跑会争用 8787 端口，B 会自动退让为 `standby`）。

---

## 安装

### 1. 先装依赖

```text
mods/
  ├─ mtr-realrail-*.jar                     ← 前端 / 判定（必需）
  ├─ mtr-control-execute-brige-*.jar        ← 控制桥（必需）
  └─ [我的世界铁路] MTR-forge-4.0.5+1.20.1.jar
```

依赖装好后进游戏自检一次（桥 ≥ 1.8 才有 `/mtbridge` 命令）：

```text
/mtbridge status      # 期望 bridge=ready，并能看到维度列表
/mtbridge audit       # 车厂路径体检（可选）
```

### 2. 装调度器 · 形态 A（独立进程）

```bash
java -jar tsc-dispatch-1.1.0.jar validate --config config/dispatch.yml   # 只校验，不联网
java -jar tsc-dispatch-1.1.0.jar run      --config config/dispatch.yml   # 正式启动
```

仓库里的 `tsc-dispatch/config/dispatch.yml` 是带完整注释的示例配置，复制过去改即可。

### 3. 装调度器 · 形态 B（Forge 模组）

```text
mods/tsc-dispatch-mod-1.0.0+1.20.1.jar
```

进世界后：

```text
/tscdispatch        # tsc-dispatch: running | dashboard http://127.0.0.1:8787/ | config config/tsc-dispatch.yml
```

面板：`http://127.0.0.1:8787/`（`/v1/status`、`/v1/metrics` 同端口）。

> 端口被占用时模组会**每 5 秒重试、最多 12 次**再退让，避免重启瞬间的误判；持续被占则进入 `standby` 并明确打日志。
> 容器里跑服务端时面板默认只绑 `127.0.0.1`，从宿主机/局域网访问需把 `admin.host` 改成 `0.0.0.0`。

---

## 配置

服务读取一份 YAML（默认 `config/dispatch.yml`；模组形态是 `config/tsc-dispatch.yml`）。关键项：

| 键 | 说明 |
|---|---|
| `tsc.base_url` | 原版只读遥测地址，默认 `http://127.0.0.1:8888`（MTR 的地图 Web 服务） |
| `tsc.port_discovery` | 端口被占时在 8888–8898 内探测实际端口 |
| `bridge.base_url` / `auth_token` | 控制桥地址与令牌；模组形态下若留空会**自动对齐** `config/mtbridge.json` 的 token |
| `bridge.mode` | `auto` / `bridge` / `rcon` / `observe` |
| `dimensions[]` | 维度 id → index 映射；索引每次启动可能变化，服务会**按 id 重映射** |
| `dispatch.strategy` | `balanced` / `on_time` / `min_waiting` |
| `dispatch.constraints` | 车厂容量上限、最长压站时间、线路优先级 |
| `trigger.*` | 提前量、站台间隔、冷却、定时回场 |
| `platform_allow` | 站台允许集合（allow / deny 规则） |
| `admin.host` / `admin.port` / `admin.token` | 管理面板绑定与鉴权（默认仅回环、无 token） |

---

## REST API

| 端点 | 说明 |
|---|---|
| `GET /` | 自带面板（无外部资源，2 秒轮询） |
| `GET /healthz` · `/readyz` | 存活 / 就绪 |
| `GET /v1/status` | 健康、世界快照摘要、`dispatch.total`（累计决策数）、桥状态 |
| `GET /v1/decisions?limit=N` | 最近决策（`total` 为累计，`returned` 为本页条数） |
| `GET /v1/vehicles` · `/v1/platforms` · `/v1/dimensions` · `/v1/channels` | 遥测明细 |
| `GET /v1/metrics` | Prometheus 文本指标 |
| `POST /v1/plan` | 单次路径 / 方案查询 |
| `POST /v1/config` | 运行期覆盖配置（内存生效，不落盘） |

面板上的“决策（累计）”是**自启动以来**的真实总数，不受 200 条环形缓冲限制；“最近缓冲”显示缓冲区当前条数。

---

## 版本与产物

| 产物 | 大小 | 说明 |
|---|---|---|
| `dist/tsc-dispatch-1.1.0.jar` | 223,994 B | 独立进程服务（内部版本 `1.1.0`） |
| `dist/tsc-dispatch-mod-1.0.0+1.20.1.jar` | 259,163 B | Forge 1.20.1 包装模组（内含同一份服务代码） |

### 版本历史

| 版本 | 变更 |
|---|---|
| 服务 1.0.0 | 首个可用版本：时间依赖最短路、占用窗口、租约表、幂等命令总线、桥 / RCON / 观察三级降级 |
| 服务 1.1.0 | 维度 id 归一化（`minecraft:overworld` ↔ `minecraft/overworld`）、决策累计计数、面板计数修正 |
| 模组 1.0.0 | Forge 包装：随服启停、配置自动生成、桥 token 自动对齐、端口守卫 + 重试、`/tscdispatch` |

> 逐轮变更与真机验证记录在 [`docs/rounds/`](docs/rounds)（27 份，含每次故障的复现配方与修复依据）。
> 早期构建产物未保留，仓库只提供当前版本二进制；历史版本可依 `docs/rounds/` 的记录重建。

---

## 从源码构建

### 服务（独立进程）

```powershell
cd tsc-dispatch
.\build.ps1                 # javac + jar + 78 条自检断言
java -jar build/tsc-dispatch-1.1.0.jar validate --config config/dispatch.yml
```

### Forge 模组

```bash
cd tsc-dispatch-mod
gradle build                # → build/libs/tsc-dispatch-mod-1.0.0+1.20.1.jar
```

`tsc-dispatch-mod/build.gradle` 里有一行是刻意为之——**同一份源码，不复制**：

```gradle
sourceSets.main.java.srcDir '../tsc-dispatch/src/main/java'
```

模组 jar 因此内嵌整份服务代码，不需要额外的 jar 依赖。

---

## 目录结构

```text
tsc-dispatch/          服务源码（56 个类）+ build.ps1 + 示例配置
  src/main/java/org/tsc/dispatch/…
  config/dispatch.yml          示例配置（带完整注释）
  docs/bridge-protocol-v1.md   服务 ↔ 桥 的接口契约
tsc-dispatch-mod/      Forge 包装（模组入口 3 个文件）
dist/                  发布产物（jar）
docs/rounds/           逐轮开发 / 联调 / 复测报告（27 份）
```

---

## 兼容性

| 组件 | 版本 |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.4.x（构建用 47.4.23） |
| MTR / TSC | 4.0.x（实测 4.0.5） |
| Java | 17（服务与模组均编译到 17） |
| 控制桥 | 协议 v1、语义 2；建议 ≥ 1.8（带 `/mtbridge audit`） |
| RealRail | 1.4.x 及以上（[仓库](https://github.com/R1145141919810/mtr-realrail) 最新 v1.5.0；提供判定与前端） |

---

## 已知限制

1. **决策计数在内存中**，重启归零；“最近决策”只保留 200 条（累计 `total` 不受影响）。
2. **维度索引不可假设**：每次启动世界加载顺序可能不同，服务按 id 重映射；配置里的 index 只是初值。
3. **路径由宿主重算**：MTR 不把车厂路径写进存档，重启后需体检 / 重建（`/mtbridge audit`、`/mtbridge recover`）。
4. **端口冲突**：8787 被别的调度器占用时模组退让为 `standby`（先重试 60 秒）。
5. **容器部署**：面板默认绑回环，容器发布端口要配合 `admin.host: 0.0.0.0`；对外暴露建议配 `admin.token`。

---

## 文档索引

| 文件 | 内容 |
|---|---|
| `docs/rounds/08` | 外部调度服务的接口边界与总体设计 |
| `docs/rounds/09` | 七段式配置体系与降级矩阵 |
| `docs/rounds/10` ~ `31` | 控制桥逐轮整改与真机复测（v1.0 → v2.0.1） |
| `docs/rounds/32` | 把调度服务包装成 Forge 模组的说明 |
| `tsc-dispatch/docs/bridge-protocol-v1.md` | 服务 ↔ 桥 的 HTTP 协议契约 |

---

## 许可

[MIT](LICENSE)。

本项目为社区项目，与 Minecraft Transit Railway / Transport Simulation Core 官方**无隶属关系**；
不修改、不反编译上游模组，只通过其公开 HTTP 接口交互。

---

## English summary

**MTR/TSC dynamic dispatch system** — a decision engine for Minecraft Transit Railway 4.0.x on MC 1.20.1 / Forge 47.x.

* **Mandatory dependencies:** [mtr-realrail](https://github.com/R1145141919810/mtr-realrail) (front-end / signalling rules) and [mtr-control-execute-brige](https://github.com/R1145141919810/mtr-control-execute-brige) (control bridge / actuator + vehicle telemetry). Installing this system alone starts the game, but without decisions input or an execution channel it can compute and can not act.
* Two runtime forms: a standalone jar (`java -jar tsc-dispatch-1.1.0.jar run`) or a Forge mod (`tsc-dispatch-mod-1.0.0+1.20.1.jar`, starts with the integrated / dedicated server).
* Dashboard and JSON API on `http://127.0.0.1:8787/`; Prometheus metrics on `/v1/metrics`.
* No third-party dependencies, Java 17, MIT licensed.

```bash
java -jar tsc-dispatch-1.1.0.jar validate --config config/dispatch.yml
java -jar tsc-dispatch-1.1.0.jar run      --config config/dispatch.yml
```
