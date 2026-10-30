# 控制桥协议 v1

调度服务与“能把指令落到车上”的一方之间的唯一契约。桥可以是：

1. 一个只调用 TSC 公开接口（`org.mtr.core.servlet.OperationProcessor`）的小型服务端组件，
   自己起一个 HTTP 端口；
2. 未来的 TSC 写端点（若上游扩展）；
3. 任何能读写同一世界的中间层（例如一个脚本化的管理进程）。

服务端**不链接游戏类**，只认下列 JSON。版本不匹配时以 `/capabilities` 为准，不允许猜。

## 1. 能力探测

```
GET /dispatch/v1/capabilities
```

```json
{
  "protocol": 1,
  "features": ["set_platform", "reroute", "hold", "release", "return_to_depot", "refresh_depot_routes"],
  "telemetry": ["vehicles", "platforms", "rails"]
}
```

- `protocol` 必须 ≥ 1，否则服务退回只读模式。
- `features` 里的每一项是服务允许下发的指令种类；未列出的指令不会被尝试，直接降级为 advisory。
- 探测失败（连接拒绝/404/超时）等价于“没有桥”，不做重试风暴。

## 2. 状态快照

```
GET /dispatch/v1/state
```

```json
{
  "protocol": 1,
  "serverTime": 1715000000000,
  "stations":  [{ "id": "0000000000000001", "name": "Central", "color": 0 }],
  "platforms": [{ "id": "0000000000000011", "name": "Central 1", "stationId": "0000000000000001", "dwellMillis": 20000 }],
  "routes": [{
    "id": "1000000000000001", "name": "Airport Express", "number": "A", "color": 3368703,
    "type": "TRAIN", "circularState": "CLOCKWISE",
    "stops": [{ "stationId": "0000000000000001", "name": "Central", "x": 0, "y": 64, "z": 0, "dwellMillis": 20000 }],
    "durations": [60000, 90000, 120000],
    "depots": ["Central Depot"]
  }],
  "depots": [{ "id": "2000000000000001", "name": "Central Depot", "routeIds": ["1000000000000001"], "sidingIds": ["s1"] }],
  "vehicles": [{
    "id": "1001", "name": "airport-express", "routeId": "1000000000000001",
    "nextPlatformId": "0000000000000041", "stationId": "0000000000000004",
    "eta": 1715000040000, "departure": 1715000075000, "deviation": 0,
    "railProgress": 1234.5, "speed": 18.2, "dwellElapsed": 0,
    "passengers": 41, "controllable": true, "atPlatform": false, "inDepot": false,
    "depot": "Central Depot", "serviceStart": 1714990000000
  }]
}
```

字段约定：

- 所有 id 用 TSC 的 16 位零填充十六进制字符串；服务端会归一化，允许省略前导零。
- `eta` / `departure` / `serverTime` 是 **epoch 毫秒**。
- `durations[i]` 是 `stops[i] → stops[i+1]` 的计划运行时间（毫秒），不含停站。
- 环形线路 `circularState` 非 `NONE` 时，最后一段回到首站。
- `controllable=false` 的车辆只会产生 advisory，不会被下发指令。

## 3. 指令

```
POST /dispatch/v1/command
```

```json
{
  "protocol": 1,
  "seq": 42,
  "key": "platform|1002|0000000000000042|29183",
  "kind": "set_platform",
  "vehicleId": "1002",
  "args": { "stationId": "0000000000000004", "platformId": "0000000000000042", "previousPlatformId": "0000000000000041" },
  "precondition": { "vehicleId": "1002", "platformId": "0000000000000041", "observedAt": 1715000000000 },
  "issuedAt": 1715000000123,
  "expiresAt": 1715000045123,
  "reason": "current platform already claimed by an earlier train"
}
```

指令种类：

| kind | args | 语义 |
|---|---|---|
| `set_platform` | `stationId`, `platformId`, `previousPlatformId` | 列车进站前更换停靠站台 |
| `reroute` | `targetStationId`, `avoidStationId`, `via[]` | 在岔路口前改走替代径路 |
| `hold` / `release` | `seconds` | 压车 / 放行（站外等待、让行） |
| `return_to_depot` | `depot`, `stationId` | 结束载客回场 |
| `refresh_depot_routes` | `depot` | 让模拟器重新生成该车厂路径（粗粒度） |
| `instant_deploy` / `clear_depot` | `depot` | 立即投放 / 清空（运维用） |

响应：

```json
{ "ok": true, "applied": true, "retryable": false, "detail": "platform switched to Airport 2" }
```

- `ok=false, retryable=false` → 终止重试（语义拒绝）。
- `ok=false, retryable=true` → 服务按指数退避重试，最多 `dispatch.max-attempts` 次。
- `ok=true, applied=false` → 已受理但尚未生效；服务等待下一轮遥测确认，不会立即重发。

## 4. 必须由桥保证的语义

1. **幂等**：相同 `key` 只生效一次。服务重启后可能重放同一 key，桥必须去重（建议保留 ≥ 10 分钟）。
2. **前置条件**：`precondition.platformId` 与当前实际不符时**拒绝执行**（返回 `ok=false, retryable=false`），
   而不是盲目执行。服务自身也会在发送前用最新快照校验一次。
3. **过期**：`issuedAt > expiresAt` 的指令直接丢弃。
4. **顺序**：同一车辆的 `seq` 单调递增；桥可以对同一车辆的旧 seq 返回拒绝。
5. **单写者**：桥内部对同一车辆的指令串行化，不要并发修改同一车辆。

## 5. 安全

- 绑定回环地址，或放在反向代理后并启用 `admin.token` 同源的鉴权。
- 协议没有加密；跨主机部署时必须走 TLS 隧道。
- `instant_deploy` / `clear_depot` 会显著改变世界状态，建议仅在维护窗口开放。
