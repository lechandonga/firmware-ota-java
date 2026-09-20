# 设备侧固件升级（OTA）设计说明

本工程用**本地目录**模拟设备存储与升级包来源，实现一套可在中断、掉电、版本不兼容与
回滚场景下保持设备可用、状态可解释的固件升级流程。

- 升级包来源：宿主机上的任意目录
- 设备存储：`ota.storage-root` 指向的目录（默认 `build/device`）
- 双槽位 A/B + staging 分块暂存区 + 追加式升级历史

---

## 1. 升级包格式

一个升级包是一个目录，包含三个文件：

```
my-package/
├── manifest.json     # 清单（元数据 + 校验信息）
├── manifest.sha256   # manifest.json 自身的 SHA-256（十六进制文本）
└── payload.bin       # 固件负载（任意字节内容）
```

### 1.1 manifest.json 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `manifestVersion` | int | 清单格式版本，当前仅支持 `1` |
| `hardwareModel` | string | 目标设备型号，必须与 `ota.device-model` 完全一致 |
| `hardwareVersion` | string | 目标硬件版本，必须与 `ota.hardware-version` 完全一致 |
| `version` | string | 固件语义化版本 `x.y.z`，可带 `-rc1` 等后缀 |
| `payloadSize` | long | `payload.bin` 的精确字节数 |
| `payloadSha256` | string | 完整负载 SHA-256（小写十六进制） |
| `chunkSize` | int | 断点续传分块大小（字节） |
| `chunkSha256` | string[] | 每个分块的 SHA-256，按顺序排列；数量须与 `payloadSize/chunkSize` 一致 |
| `minCompatibleVersion` | string? | 本包自述的最低兼容版本（可省略） |
| `selfTest` | object | 启动自检规格，见下 |

`selfTest`：

| 字段 | 说明 |
|---|---|
| `mode` | `ALWAYS_OK`（恒通过）/ `MARKER`（负载必须包含 `requiredMarker`）/ `SLOW`（模拟卡死超时） |
| `timeoutMs` | 自检超时；超过则结论为 TIMEOUT 并自动回滚 |
| `requiredMarker` | `MARKER` 模式必填，负载 UTF-8 文本中必须包含该串 |

### 1.2 校验顺序与可区分的拒绝原因

`FirmwarePackageReader` / `FirmwarePackageValidator` 按下列顺序校验，任一失败立即拒绝：

| 拒绝原因（RejectReason） | 触发条件 |
|---|---|
| `MANIFEST_NOT_FOUND` | 没有 manifest.json |
| `MANIFEST_SIGNATURE_INVALID` | 没有 manifest.sha256，或清单实际 SHA-256 与签名不符（**清单被篡改**） |
| `MANIFEST_CORRUPT` | 清单 JSON 无法解析 |
| `UNSUPPORTED_MANIFEST_VERSION` | `manifestVersion` 不是受支持值 |
| `MANIFEST_INVALID` | 必填字段缺失、分块校验和数量与负载大小不符、MARKER 模式缺标记等 |
| `PAYLOAD_NOT_FOUND` | 没有 payload.bin |
| `PAYLOAD_SIZE_MISMATCH` | 负载实际字节数与 `payloadSize` 不符 |
| `PAYLOAD_CHECKSUM_MISMATCH` | 负载 SHA-256（整体或分块）不符（**内容被篡改/传输损坏**） |
| `HARDWARE_MODEL_MISMATCH` | 型号不匹配 |
| `HARDWARE_VERSION_MISMATCH` | 硬件版本不匹配 |
| `INVALID_VERSION` | 版本号不是合法语义化版本 |
| `DOWNGRADE_NOT_ALLOWED` | 目标版本低于在位版本且策略禁止降级 |
| `BELOW_MIN_COMPATIBLE_VERSION` | 目标版本低于生效的最低兼容版本 |
| `PACKAGE_TOO_LARGE` | 包大小超过 `ota.max-package-bytes` |
| `INSUFFICIENT_STORAGE` | 安装后预计占用超过 `ota.max-storage-bytes` |
| `ALREADY_INSTALLED` | 目标版本已在位 |

预检阶段（解析 + 设备/版本/大小策略）被拒绝时：**不写升级历史、不创建临时资源、不改在位版本**，
拒绝结论仅随本次响应返回；升级流程开始后（传输终验失败等）的拒绝会更新历史记录。

升级包可用 `FirmwarePackageBuilder`（测试与演示中大量使用）或参考其实现手工生成。

---

## 2. 设备存储布局

```
<storage-root>/
├── slots/
│   ├── slot-layout.json        # 槽位布局（唯一的“启动指针”，原子替换）
│   ├── a/
│   │   ├── payload.bin         # A 槽固件
│   │   ├── payload.incoming    # 安装临时文件（仅安装中短暂存在）
│   │   └── image.json          # A 槽镜像元数据（存在且校验通过 = 槽位可信）
│   └── b/  （同上）
├── staging/
│   └── <upgradeId>/
│       ├── staging-state.json  # 断点续传状态（每块 verified 标记，原子写）
│       └── chunks/
│           ├── chunk-0.bin     # 已通过校验的分块
│           ├── chunk-1.bin
│           └── ...
└── history/
    └── upgrade-history.json    # 追加式升级历史（条目永不删除）
```

### 2.1 slot-layout.json

| 字段 | 说明 |
|---|---|
| `activeSlot` | 当前启动槽位（`a`/`b`） |
| `committedSlot` | 自检通过的确认槽位 |
| `pendingSlot` | 待启动自检的槽位；`null` 表示无待确认升级 |
| `pendingUpgradeId` | 待确认升级的幂等键，用于关联历史 |
| `previousCommittedSlot` | 上一个好版本槽位，转正后仍保留，供手动回滚 |
| `bootCount` | 启动次数 |
| `selfTestAttempts` | 当前待确认槽位的自检尝试次数 |

---

## 3. 升级状态模型

```
                 预检拒绝（不落历史/不占资源）
   请求升级 ──► REJECTED（终态，携带 RejectReason）

   请求升级 ──► STAGING ──► STAGED ──► INSTALLING ──► PENDING_SELF_TEST
                ▲   中断可恢复   │                      │
                └───────────────┘              自检通过 │ 自检失败/超时/镜像损坏
                                                  COMMITTED（终态）   ROLLED_BACK（终态）
                                                                       携带 RollbackReason
```

- **STAGING**：分块传输中。任意中断（杀进程、故障注入）后，用同一 `upgradeId` 再次请求即续传。
- **STAGED**：所有分块落盘且整包 SHA-256 终验通过。
- **INSTALLING**：正在写入非活动槽。中断/掉电不触碰活动槽，半成品槽因缺少可信 `image.json`
  （或负载校验不通过）而不会被启动。
- **PENDING_SELF_TEST**：`slot-layout.json` 已原子切换，等待下一次启动做自检。
- **COMMITTED / ROLLED_BACK**：终态。

回滚原因（`RollbackReason`，与拒绝原因、普通失败文本相互区分）：

| 原因 | 触发条件 |
|---|---|
| `SELF_TEST_FAILED` | 新版本启动自检结论为失败 |
| `SELF_TEST_TIMEOUT` | 新版本自检在 `timeoutMs` 内未完成 |
| `IMAGE_CORRUPT_ON_BOOT` | 待确认槽位启动时校验发现镜像不完整 |
| `MANUAL` | 调用手动回滚接口 |

每条历史记录还带自由文本 `failureReason`，与枚举原因同时提供。

---

## 4. 断点续传

1. 负载按 `chunkSize` 切块，每块有独立 SHA-256（清单中预声明）。
2. 每块写入 `chunks/chunk-i.bin` 后**立即**算 SHA-256；通过才把
   `chunksVerified[i]=true` 原子写入 `staging-state.json`。
3. 写块中途失败：删除残片，该块保持 `false`。**已写入但未通过校验的数据绝不使用。**
4. 恢复时（同一 `upgradeId`）：
   - 对所有已存在的分块**重新复核哈希**（防止掉电导致的落盘损坏被误信）；
   - 只对 `false` 的分块重新从包负载对应偏移读取传输，已校验块直接跳过。
5. 所有块通过后，按顺序拼接计算整包 SHA-256 终验，通过才置 `complete=true`。

---

## 5. 原子切换与掉电安全

安装与切换严格分三步：

1. **写非活动槽**：staging 分块拼接到 `payload.incoming` → 整槽 SHA-256 校验
   → 原子 rename 为 `payload.bin` → 最后原子写 `image.json`。
   活动槽全程不动，设备继续运行在位版本。
2. **原子切换**：`image.json` 存在且校验通过后，用一次“临时文件 + `ATOMIC_MOVE`”
   替换 `slot-layout.json`（active→新槽，旧槽记为 committed/previousCommitted）。
3. **启动自检转正/回滚**：见第 6 节。

掉电后果只有两种确定情况：

- layout 未切换：仍是旧版本，旧槽完好；半成品槽无有效 `image.json`，永不启动。
- layout 已切换：新槽 payload + image.json 在此之前已完整落盘，新槽确定可启动，
  随后进入自检；自检不过即自动回滚。

所有 JSON 状态文件都采用“写临时文件 → fsync → 原子 rename”，不存在半文件被读到的情况。

---

## 6. 启动自检与自动回滚

应用启动时 `BootCoordinator` 执行一次决策（`OtaStartupRunner`）：

- 无 `pendingSlot`：正常启动在位版本（`NORMAL_BOOT`）。
- 有 `pendingSlot`：
  - 槽位镜像校验失败 → 回滚，原因 `IMAGE_CORRUPT_ON_BOOT`；
  - 否则执行清单声明的自检（独立工作线程，`timeoutMs` 超时保护，绝不无限阻塞）：
    - PASS → `markCommitted` 转正；
    - FAILED → 回滚，原因 `SELF_TEST_FAILED`；
    - TIMEOUT → 回滚，原因 `SELF_TEST_TIMEOUT`。
- 回滚把 `activeSlot` 原子切回确认好版本槽，清空 pending。回滚记录写入追加式历史，
  **后续升级不会删除它**。
- 转正后旧版本槽被记为 `previousCommittedSlot`，在它被下一次安装覆盖前，
  可通过手动回滚接口回到“最近一次可正常启动的版本”。

---

## 7. 版本兼容策略

- `ota.allow-downgrade=false`（默认）：目标低于在位版本 → `DOWNGRADE_NOT_ALLOWED`。
- 最低兼容版本取**设备配置 `ota.min-compatible-version`** 与**包内
  `minCompatibleVersion`** 中的较高者；目标低于它 → `BELOW_MIN_COMPATIBLE_VERSION`。
- 策略拒绝在预检阶段完成：在位版本不变、历史不变、无临时资源。

---

## 8. 并发与幂等

- `UpgradeService` 全部公开方法 `synchronized`：同一时刻设备上只有一个升级流程，
  升级/状态查询/回滚交叉调用不会读到半完成状态。
- `upgradeId` 是调用方提供的幂等键：
  - 终态记录（REJECTED 预检记录除外，因其不落历史）重复请求直接返回同一结果，不重复写入；
  - 中间态记录重复请求 = 断点恢复，只处理未完成部分；
  - 并发提交同一个 `upgradeId` 只会产生一条历史记录。
- 存在 `pendingSlot`（待自检）时拒绝发起新升级，必须先重启完成自检或回滚。

---

## 9. 空间策略

- 包级上限：`ota.max-package-bytes`，超限 `PACKAGE_TOO_LARGE`。
- 设备级上限：`ota.max-storage-bytes`，按“当前占用 + 一份新镜像”预估，
  超限 `INSUFFICIENT_STORAGE`。
- 两种拒绝都发生在写入前；任何中途失败/拒绝都会删除对应 staging 目录和
  `payload.incoming`，不残留垃圾、不无界占用。

---

## 10. 配置项（`application.properties`，前缀 `ota`）

| 配置 | 默认值 | 说明 |
|---|---|---|
| `storage-root` | `build/device` | 设备存储根目录 |
| `device-model` | `DEMO-DEVICE` | 本设备型号 |
| `hardware-version` | `1.0` | 本设备硬件版本 |
| `factory-version` | `1.0.0` | 出厂固件版本（空存储预置） |
| `factory-payload` | `factory firmware 1.0.0\n` | 出厂固件内容 |
| `max-package-bytes` | 64 MiB | 单个升级包上限 |
| `max-storage-bytes` | 256 MiB | 设备存储占用上限 |
| `allow-downgrade` | `false` | 是否允许降级 |
| `min-compatible-version` | （空） | 设备侧最低兼容版本 |
| `chunk-size-bytes` | 65536 | 断点续传分块大小 |

---

## 11. HTTP 接口

| 方法与路径 | 说明 |
|---|---|
| `POST /api/ota/upgrades` | 发起/恢复升级，body：`{"upgradeId":"...","packageDir":"/path/to/pkg"}` |
| `GET  /api/ota/upgrades/{id}` | 查询单条升级记录（含阶段、拒绝/回滚原因） |
| `GET  /api/ota/status` | 当前槽位/版本/pending/存储占用 |
| `GET  /api/ota/history` | 追加式升级历史（含拒绝、回滚，永不删除） |
| `POST /api/ota/rollback` | 手动回滚到上一好版本，body：`{"upgradeId":"..."}`（可空） |
| `POST /api/ota/faults` | 【本地验证】注入故障，见下 |
| `GET  /api/ota/faults` | 查看故障开关与上次启动决策 |
| `POST /api/ota/faults/reboot` | 【本地验证】模拟重新上电，重跑启动决策 |

故障注入 body 示例：

```json
{"failAfterBytes": 50}
{"failInstallAfterBytes": 100}
{"forceSelfTestFailure": true}
{"forceSelfTestTimeout": true}
{"reset": true}
```

关键决策与状态迁移均有 INFO/WARN/ERROR 日志（槽位切换、自检结论、回滚、拒绝原因等）。

---

## 12. 本地验证指引

```bash
mvn test                      # 26 个测试：非法包、续传、掉电、自检失败/超时、回滚、并发、空间
mvn spring-boot:run           # 启动设备（空存储自动预置出厂 1.0.0 到 A 槽）
```

手工演示（另开终端，先用 `FirmwarePackageBuilder` 或测试工具准备一个包目录）：

```bash
curl -s localhost:8080/api/ota/status
curl -s -XPOST localhost:8080/api/ota/faults -d '{"failAfterBytes":50}' -H 'Content-Type: application/json'
curl -s -XPOST localhost:8080/api/ota/upgrades \
     -H 'Content-Type: application/json' \
     -d '{"upgradeId":"u1","packageDir":"/tmp/packages/p2"}'   # 停在 STAGING
curl -s -XPOST localhost:8080/api/ota/faults -d '{"reset":true}' -H 'Content-Type: application/json'
curl -s -XPOST localhost:8080/api/ota/upgrades \
     -H 'Content-Type: application/json' \
     -d '{"upgradeId":"u1","packageDir":"/tmp/packages/p2"}'   # 续传 -> 自检 -> COMMITTED
curl -s localhost:8080/api/ota/history
```
