# 设备侧固件升级（OTA）本地可验证实现

用本地目录模拟**设备存储**与**升级包来源**，实现一套在传输中断、掉电/强杀、
版本不兼容、自检失败与回滚等场景下，**设备始终可启动、状态可解释、无脏数据残留**
的固件升级流程。基于 Spring Boot 3 / Java 21，无外部数据库依赖。

---

## 1. 目录模型（本地模拟设备 flash）

```
{firmware.ota.storage-dir}/                     设备存储根（默认 data/device-storage）
├── state.json                                  设备持久状态（临时文件 + 原子 rename）
├── history.json                                append-only 升级/回滚历史（原子写）
├── current -> slots/A（或 slots/B）             当前运行槽指针（symlink，原子切换）
├── slots/
│   ├── A/                                      A 槽
│   │   ├── payload.bin                         固件载荷
│   │   ├── manifest.json                       随包清单副本
│   │   └── VERSION                             “完整可用”提交标记（最后落盘）
│   └── B/                                      B 槽（结构同 A）
└── staging/{upgradeId}/
    ├── chunks/000000.part ...                  已通过逐块哈希校验的分块
    ├── chunks/*.wr                             正在写、尚未校验的块（不被承认）
    ├── verified.json                           已校验块位图（原子写）
    └── payload.assembled                       全部块组装并整体复核后的载荷

{firmware.ota.package-source-dir}/              升级包来源（默认 data/packages）
└── {packageName}/
    ├── manifest.json
    └── payload.bin
```

### A/B 双槽与原子切换

* 升级**只写非活动槽**，活动槽（当前运行版本）在整个安装过程中不被打开写入，
  因此升级写入不可能破坏当前可运行版本。
* 槽位内按 `payload → manifest → VERSION` 顺序提交，**VERSION 最后原子落盘**，
  它的存在表示该槽完整可用；缺它（或带 `INSTALLING` 标记、哈希不符）的槽位
  在任何情况下都不被承认为可启动版本。
* 版本切换通过 `current` 符号链接的 **rename 原子替换**完成。任意时刻掉电，
  重启后看到的链接要么指向旧槽、要么指向新槽，二者都完整，因此
  **重启总能确定地落在某一份完整可用版本上**。

---

## 2. 升级包格式

每个升级包是来源目录下的一个子目录，包含两个文件：

### 2.1 `manifest.json`

| 字段 | 含义 |
| --- | --- |
| `upgradeId` | 升级唯一标识；**重复请求幂等**与断点续传据此识别 |
| `version` | 语义化版本 `major.minor.patch` |
| `model` | 适用设备型号 |
| `hardwareVersion` | 适用硬件版本 |
| `payloadSize` | 载荷字节数（须与实际一致） |
| `payloadSha256` | 载荷整体 SHA-256（小写十六进制），防篡改 |
| `chunkSize` | 分块大小（字节），断点续传/逐块校验粒度 |
| `chunkSha256` | 每个分块的 SHA-256 列表，数量须与大小自洽 |
| `selftestResult` | 首启自检模拟结果：`SUCCESS` / `FAIL` / `TIMEOUT` |
| `selftestDurationMillis` | 自检耗时/卡顿时长（模拟用） |

### 2.2 `payload.bin`

固件原始字节。整体哈希、大小、逐块哈希三者均与清单一致才视为内容可信。

### 2.3 校验顺序与可区分的拒绝原因（`RejectReason`）

| 拒绝原因 | 触发条件 |
| --- | --- |
| `SOURCE_NOT_FOUND` | 来源目录不存在 |
| `MANIFEST_MISSING` | 缺 `manifest.json` |
| `MANIFEST_MALFORMED` | 清单 JSON 损坏或必填字段非法/分块哈希数量不自洽 |
| `PAYLOAD_MISSING` | 缺 `payload.bin` |
| `PACKAGE_TOO_LARGE` | 包大小超过 `max-package-bytes` |
| `PAYLOAD_TAMPERED` | 载荷大小不符或整体/分块哈希不符（内容被篡改） |
| `MODEL_MISMATCH` | 型号不匹配 |
| `HARDWARE_MISMATCH` | 硬件版本不匹配 |
| `DOWNGRADE_FORBIDDEN` | 策略禁止降级（见 §5） |
| `VERSION_BELOW_MIN_COMPATIBLE` | 低于最低兼容版本 |
| `STORAGE_LIMIT_EXCEEDED` | 安装后存储占用将超过 `max-storage-bytes` |

被拒绝后：**继续运行在位版本**，释放为该包产生的临时资源；非策略类拒绝写入
一条 `REJECTED` 历史。状态查询接口给出 `lastRejectReason` /
`lastRejectedUpgradeId`，各原因彼此可区分。

---

## 3. 状态模型（`UpdatePhase`）

| 阶段 | 含义 |
| --- | --- |
| `IDLE` | 稳态，无升级 |
| `DOWNLOADING` | 分块传输/写入中 |
| `VERIFYING` | 分块已齐，组装与整体复核 |
| `INSTALLING` | 写入非活动槽（尚未原子切换） |
| `INTERRUPTED` | 传输/写入/安装中断，已校验块保留、可续传，运行版本不变 |
| `INSTALLED_PENDING_TEST` | 已原子切换到新版本，等待首启自检 |
| `TESTING` | 新版本首启自检中（未确认） |
| `CONFIRMED` | 自检通过、新版本确认为 lastGood（随后回到 IDLE） |
| `ROLLING_BACK` | 正在回滚 |
| `ROLLED_BACK` | 已回滚到最近一次可正常启动版本 |

每次迁移都**先把状态原子落盘再做动作**，`state.json` 只会呈现某个完整迁移点。

### 掉电/强杀后的启动恢复（`BootRecovery`）

重启时依据 `state.json` + `current` 指针 + 槽位提交标记裁决：

* 无状态：安装出厂固件（`factory-version`，默认 1.0.0）。
* 死在 `DOWNLOADING/VERIFYING/INSTALLING`：切换尚未发生，继续运行旧版本，
  状态置 `INTERRUPTED`，擦除非活动槽上的半安装残留，等待续传。
* 死在 `INSTALLED_PENDING_TEST/TESTING/ROLLING_BACK`：新版本**未经确认**，
  保守判定启动失败，自动切回完整的旧槽（lastGood），写 `ROLLED_BACK` 历史。
* `IDLE/CONFIRMED/ROLLED_BACK`：校验当前槽完整后继续运行。
* 极端情况下 `current` 指向的槽损坏：切回另一完整槽；两槽皆坏则出厂兜底。

---

## 4. 断点续传

* 每个块：写 `NNNNNN.wr` → 计算 SHA-256 与清单比对 → 通过才原子 rename 为
  `NNNNNN.part` 并写入 `verified.json` 位图。
* **只有位图中存在的块才算已通过校验**。写到一半的 `.wr`、或人为放入但未经
  校验的文件，都不会被当作有效数据，恢复后该块重新传输。
* 重新升级同一 `upgradeId` 时，从位图边界继续，只处理尚未通过校验的块；
* 成功完成的升级在记录中标记 `resumed=true`，与一次传完可区分。

### 4.1 已暂存数据损坏的自动重处理（设备存储不可靠兜底）

设备掉电/强杀后，**已写入暂存区并被位图承认的数据本身也可能损坏**
（闪存老化、坏块、半截写入、内容翻位）。旧行为下这种损坏要到组装时才暴露，
且无法定位坏块，表现为“进度看着快满却永远推不动，重启也没用”。

现在的处理：

* **续传前审计**：恢复同一升级时，逐个复核位图中块的实际内容
  （文件是否存在、实际哈希与位图/清单是否一致），把不可用的块**从位图剔除
  并删除文件**，只重传这些块；复核通过的块保留，绝不重复处理。
* **组装失败兜底**：传输完成后组装复核（逐块 + 整体哈希）仍失败时，
  同样审计剔除坏块并回到传输继续，而不是卡死。
* **进度真实**：剔除坏块后 `verifiedChunks`/进度百分比回退到真实可用数据，
  不会出现“显示快完成、实际推不动”。
* **可观测**：状态查询暴露 `repairAttempts`（重处理轮次）与
  `repairedChunks`（累计重处理块数）；升级记录（含 `SUCCEEDED`/`FAILED`/
  `ROLLED_BACK`）带 `repairedChunks` 字段，能看出本次续传是否发生过
  “数据不可用被重处理”以及处理了多少。
* **有边界**：同一升级的重处理轮次计入持久化状态（跨重启有效），超过
  `max-repair-attempts`（默认 3）即按 `FAILED` +
  `STAGED_DATA_REPAIR_EXHAUSTED` 收场：保留在位可运行版本、释放全部暂存空间、
  状态回到 `IDLE`，同一升级可稍后作为全新尝试重新发起。
  `FAILED`（失败）与 `REJECTED`（拒绝）是两种可区分的结果。

**能自动恢复**：部分暂存块损坏/丢失/位图条目损坏，且重处理轮次未超限。
**只能拒绝或失败**：升级包来源本身被篡改（`PAYLOAD_TAMPERED`，拒绝）；
重处理次数超限（`STAGED_DATA_REPAIR_EXHAUSTED`，失败）。

**本地复现/验证**：

1. 自动化：`mvn test -Dtest=CorruptStagingRecoveryTest`（含重启视角）。
2. 手动：启动服务后 `POST /api/ota/upgrades` 发起一个升级并在传输中途
   杀掉进程；修改或删除 `data/device-storage/staging/<upgradeId>/chunks/`
   下某个 `NNNNNN.part`（模拟坏块/翻位）；重新启动并再次
   `POST /api/ota/upgrades` 同一包。`GET /api/ota/state` 可见
   `repairAttempts/repairedChunks` 增加、进度先回退再推进；
   `GET /api/ota/history` 中成功记录带 `repairedChunks>0`。
   反复破坏暂存数据超过 `max-repair-attempts` 次，则历史出现
   `FAILED` / `STAGED_DATA_REPAIR_EXHAUSTED`，暂存目录被清空。

---

## 5. 版本策略与回滚规则

### 5.1 版本策略（可配置）

* `allow-downgrade=false`（默认）：目标版本低于当前版本 →
  `DOWNGRADE_FORBIDDEN`。
* `minimum-compatible-version` 非空时：目标版本低于它 →
  `VERSION_BELOW_MIN_COMPATIBLE`（即使允许降级也拦截）。
* **策略拒绝不改变在位版本，也不写入升级历史。**

### 5.2 首启自检与自动回滚

* 原子切换后进入 `TESTING`，在 `selftest-timeout-millis` 内执行首启自检。
* 自检失败 → `SELFTEST_FAILED`；超时 → `SELFTEST_TIMEOUT`。二者相互可区分。
* 任一不通过：原子切回最近一次可正常启动版本（lastGood），擦除坏候选槽，
  释放暂存，写一条 `ROLLED_BACK` 记录（含 `failureReason` 与 `rolledBackTo`）。
* 历史是 **append-only**：回滚记录一旦写入，后续任何升级都不会覆盖或删除它。
* 也支持 `POST /api/ota/rollback` 手动回滚。

### 5.3 并发与重复请求

* 单设备升级/回滚由一把锁串行化，升级、状态查询与回滚并发时不会出现
  半完成状态；状态查询直接读原子快照，升级进行中也能读到一致的中间态。
* 同一 `upgradeId`：已成功 → 幂等返回原记录、不重复写入；中断中 → 断点续传。
  因此同一目标不可能被两个升级同时写入。

### 5.4 资源上限

* 单包大小 `max-package-bytes`、存储总占用 `max-storage-bytes` 均可配置；
  超限拒绝并清理临时文件，不产生无界占用。

---

## 6. 配置项（`application.properties`，前缀 `firmware.ota`）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `storage-dir` | `data/device-storage` | 设备存储根目录 |
| `package-source-dir` | `data/packages` | 升级包来源目录 |
| `device-model` | `DEVICE-X1` | 设备型号 |
| `hardware-version` | `hw-1.0` | 硬件版本 |
| `factory-version` | `1.0.0` | 出厂固件版本 |
| `allow-downgrade` | `false` | 是否允许降级 |
| `minimum-compatible-version` | 空 | 最低兼容版本（空=不限） |
| `max-package-bytes` | `67108864` (64 MiB) | 单包大小上限 |
| `max-storage-bytes` | `268435456` (256 MiB) | 存储总占用上限 |
| `chunk-size` | `4096` | 分块大小（续传粒度） |
| `selftest-timeout-millis` | `2000` | 首启自检超时 |
| `max-repair-attempts` | `3` | 同一升级内已暂存数据损坏自动重处理的次数上限，超限按 `STAGED_DATA_REPAIR_EXHAUSTED` 失败收场 |

---

## 7. HTTP 接口

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/ota/upgrades` | body `{"packageName":"v2"}`：发起/续传升级（幂等） |
| `GET  /api/ota/state` | 当前阶段、运行/候选版本、槽位、进度、最近拒绝原因、待回滚原因 |
| `GET  /api/ota/history` | 升级与回滚历史（append-only） |
| `POST /api/ota/rollback` | 手动回滚到最近一次可正常启动版本 |

启动：`mvn spring-boot:run`（首次启动自动安装出厂固件 1.0.0）。

---

## 8. 测试

以中断、掉电、回滚为主，全部使用临时目录、无需外部依赖：

```
mvn test
```

覆盖场景（见 `src/test/.../tests`）：

* `HappyPathUpgradeTest`：正常升级、原子切换、重启后仍运行新版本、出厂安装。
* `InvalidPackageRejectionTest`：清单缺失/损坏、载荷缺失、内容篡改、
  型号/硬件错配、包过大；拒绝原因可区分且在位版本不变。
* `ResumableTransferTest`：写入中断后续传只处理未校验块、脏块不被采用、
  `.wr` 不残留、重复请求不重置进度。
* `CorruptStagingRecoveryTest`：中断后已暂存数据被破坏（翻位/丢失），
  重启后续传自动剔除坏块、只重传损坏部分并正常完成；进度与真实可用数据一致；
  重处理超限时按 `STAGED_DATA_REPAIR_EXHAUSTED` 失败、保留在位版本并释放暂存，
  之后可重新发起成功。
* `RollbackTest`：自检失败/超时自动回滚且原因可区分、自检中强杀后重启回滚、
  回滚记录不被后续成功升级覆盖、回滚后重启稳定。
* `PowerLossInstallTest`：槽位提交前掉电，旧版本持续可启动、候选槽不被承认、
  半安装残留被清理、重试可成功。
* `VersionPolicyTest`：禁止降级、允许降级、最低兼容版本、策略拒绝不落历史。
* `ConcurrencyAndLimitsTest`：并发重复请求串行化且只产生一次升级、
  成功后重复请求幂等、存储上限拒绝并释放临时资源、中断态可查询。

关键决策与状态迁移均通过 SLF4J 输出（校验拒绝、分块校验、原子切换、
自检裁决、回滚、启动恢复等）。
