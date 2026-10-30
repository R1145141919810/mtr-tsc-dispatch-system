# 30 · 恢复窗口与 advisory 收敛（控制桥 v2.0.1）

对应报告：29-控制桥v2.0复测结果.md

产物：mtbridge/build/libs/mtbridge-2.0.1+1.20.1.jar（94,227 字节，reobf）
接口、协议、指令链路与 2.0.0 完全一致，仅修 advisory 时序。

---

## 1. ① 为什么没达成

报告说得很准：Depot.generateDepots 是异步的（实测 ~6 s 才产出路径）。v2.0 的补丁是同步的：

    recovered = autoRecover(...);
    if (recovered > 0) auditAll(...);      // 此刻 routeSegments 仍是 0 → 又发布了一次 broken

所以那次刷新只是把同样的结果重发一遍；手动 recover 更没有刷新。重建期间车厂在
“健康/不健康”之间抖动，也是同一个原因（采样恰好落在不同阶段）。

---

## 2. 改法：恢复窗口 + 窗口内逐 tick 重算（报告 §1 的 2+3）

### 2.1 恢复窗口

发起重建前登记 recoveringDepots[depotId] = now + 20 s。
判断 isRecovering(depot)：

    超过 20 s            → 解除（下次巡检按真实状态报告）
    该车厂所有侧线都有路径 → 立即解除（重建提前完成）
    其余                 → 视为“正在恢复”

窗口内的车厂**不再进入 pathRecoveryNeeded**——这正是抖动与残留的来源。

### 2.2 可见性（不是掩盖）

窗口内的车厂单独出现在 /health.recovering：[{ depotId, until }]。

语义区分：

| 字段 | 含义 | 外部该怎么用 |
|---|---|---|
| pathRecoveryNeeded | 需要人工/自动介入 | 告警看这里 |
| recovering | 正在重建（异步进行中） | 看板展示，不必告警 |

超时仍没修好 → 车厂重新出现在 pathRecoveryNeeded（不静默）。

### 2.3 收敛速度

hasRecoveringDepots() 为真时，平台层把巡检从“每 200 tick”改为“每 tick 重算”。
一次遍历只有几条侧线，成本可忽略，但 advisory 在路径就绪的那一帧就归零，
不再等一个采样周期。

### 2.4 手动恢复同帧发布

/mtbridge recover 执行后立即 auditAll(simulators)：
车厂此刻已进入恢复窗口，因此 advisory 当场摘除，而不是等下一次周期巡检。

---

## 3. 行为对比

    旧（2.0.0）：
      t+8.8s  lastRecovery 置位，pathRecoveryNeeded 仍非空 → 持续到下一个巡检周期
      手动恢复期间：need 0 → 1 → 0 来回抖动

    新（2.0.1）：
      恢复发起 → recovering 出现该车厂，pathRecoveryNeeded 当场为空
      路径就绪 → recovering 自动清空
      20 s 未就绪 → 该车厂重新出现在 pathRecoveryNeeded

---

## 4. 复测建议

1. 制造丢路径 → 打开 allowDepotRegeneration 重启：
   采样应看到 need 立刻为 0、recovering 出现该车厂；路径就绪后 recovering 清空；
2. 手动 /mtbridge recover 同理，且不应再出现 need 的 0→1→0 抖动；
3. 把重建窗口人为拉长（例如在窗口内再次制造丢路径）→ 超过 20 s 后该车厂应重新出现在
   pathRecoveryNeeded，确认“超时不掩盖”。

---

## 5. 定版

2.0.1 只修 advisory 时序，接口/协议/四条指令链路不变，可直接替换 2.0.0 的 jar。
仍开放：服务侧 P2⑤（bridge.mode: telemetry 只读模式），属独立改动。
