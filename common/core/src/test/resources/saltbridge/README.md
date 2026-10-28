# SaltBridge 协议样本（脱敏测试夹具）

来源：`saltbridge` 0.1.0 实现（`wire/SnapshotJson.kt`、`wire/ApiDtos.kt`）与 `docs/PROTOCOL.md` v1.0。
用途：DangoMusic 侧协议解析、歌词映射与假 HTTP 服务测试的固定输入。

脱敏规则（阶段 0 要求）：

- 歌词全部为自写文本，无真实歌曲歌词；
- 不含访问令牌、真实 instanceId、个人路径或本机信息；
- 时间戳为固定假值（基准 2026-10-28 前后），不随真实时间变化。

| 文件 | 覆盖场景 |
| --- | --- |
| `health.json` | 探活响应；`protocolVersion` 为字符串 `"1.0"` |
| `capabilities.json` | 能力与 limits 默认值；`control` 全 true 仅作形状参考（首版不实现控制） |
| `status.playing.json` | 正常播放：锚点 event、activity advancing、animation confirmed、歌词摘要 available |
| `status.unknown.json` | 启用后未收到事件：state unknown、isPlaying null、无锚点；**不是**暂停或停止 |
| `lyrics.word.json` | 真实逐字歌词（cells 有时间），timing=word |
| `lyrics.line.json` | 仅逐行（cells 空），timing=line，带 translation 字段（首版忽略翻译） |
| `lyrics.times-invalid.json` | endMs < startMs、startMs 逆序；timesValid=false、timingSanity=suspect、association=unconfirmed——不得用于同步定位 |

已知协议要点（解析时必须遵守）：

- `seq` 只在同 `instanceId` 内比较；身份是 `instanceId + generation`，同曲重播不保证 generation 递增。
- 位置只有上报锚点，服务端不外推；客户端按 `animation.mode/maxMs` 与 `positionStaleAfterMs` 外推。
- 歌词 `status` 为 `unknown/available/stale/unavailable`；`unavailable` 不等于「这首歌没歌词」，不可永久缓存。
- `timesValid=false` 的行不参与同步定位；cells 不可信时退回逐行显示。
