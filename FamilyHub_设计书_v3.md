# SyncTune · 共享音乐系统设计书

> **核心原则**：最小可用系统优先上线，架构预留扩展口，功能分阶段叠加。能复用开源方案的坚决不自己造。

---

## 一、系统架构概览

```
┌─────────────────────────────────────────────────┐
│               Android APP（客户端）               │
│   播放器 · 歌单管理 · 跟随系统 · 上传/导入入口   │
└───────────────────┬─────────────────────────────┘
                    │ REST API / WebSocket
┌───────────────────▼─────────────────────────────┐
│                 云端服务器（VPS）                  │
│  ┌──────────┐  ┌──────────────┐  ┌────────────┐ │
│  │ 音乐文件  │  │  同步引擎    │  │ 下载转换   │ │
│  │ 存储服务  │  │ (WebSocket) │  │ 后台服务   │ │
│  └──────────┘  └──────────────┘  └────────────┘ │
│  ┌─────────────────────────────────────────────┐ │
│  │        SQLite 数据库（曲目/歌单/状态）        │ │
│  └─────────────────────────────────────────────┘ │
└───────────────────┬─────────────────────────────┘
                    │ 可选 · 进阶阶段
┌───────────────────▼─────────────────────────────┐
│             本地 AI 节点（按需开机）               │
│           本地大模型 · FRP 暴露端口               │
└─────────────────────────────────────────────────┘
```

---

## 二、好友系统

好友功能**优先复用所选开源 APP 的现有实现**，本节仅描述业务层需求，具体 UI 和通知机制以 APP 实现为准。

### 2.1 好友关系定义

- 好友关系为**双向确认制**：A 向 B 发申请，B 确认后双方成为好友
- 只有互为好友的用户之间才能发起跟随
- 在线状态（APP 是否打开）只对好友可见

### 2.2 在线状态

- 判定规则：APP 处于前台或后台 → 在线；APP 完全退出 → 离线
- 好友列表中实时显示每位好友的在线/离线状态

---

## 三、跟随系统

### 3.1 核心概念

| 概念 | 说明 |
|------|------|
| **房主** | 开启跟随房间的用户，其播放状态实时广播给所有房客 |
| **房客** | 加入跟随房间的用户，播放状态被房主强制同步 |
| **跟随房间** | 以房主为中心、房客自动同步播放状态的会话单元 |

**互斥规则（强制）**：一个用户在同一时刻只能是房主或房客，不能同时兼任。
- 房主若想跟随他人，必须先解散自己的房间
- 房客若想成为房主，必须先退出当前房间

### 3.2 房间状态机

```
用户状态：SOLO ──────────────────────────────────────────┐
              │                                          │
              │ 有人加入（自动成为房主）                   │
              ▼                                          │
           HOSTING ──── 主动解散 / APP退出 / 后台超时 ───►│
              │                                          │
              │（无法直接跳转，必须先解散）                │
                                                         │
用户状态：SOLO ──────────────────────────────────────────┤
              │                                          │
              │ 点击加入好友的房间                        │
              ▼                                          │
           FOLLOWING ── 主动退出 / 自行换歌 ─────────────►│
```

### 3.3 房间的创建与解散

**房间创建**：房间不需要手动创建。当第一个房客发起跟随请求时，房主身份自动生效，房间隐式建立。

**房间解散**，仅以下三种情形触发，无其他情形：

| 触发条件 | 说明 |
|----------|------|
| 房主主动解散 | 点击悬浮按钮 → 管理面板 → 解散房间 |
| 房主 APP 完全退出 | 非后台化，是真正退出进程 |
| 房主 APP 后台化超过 1 小时且期间无任何有效指令发出 | 每次 APP 回到前台重置计时 |

房间解散时：
- 服务器向所有房客推送通知：「[房主昵称] 解散了房间」
- 所有房客自动回到 `SOLO` 状态，停在当前歌曲当前位置继续独立播放
- 房主回到 `SOLO` 状态，可立刻被他人重新发起跟随（即隐式建立新房间）

### 3.4 房客加入流程

**前提**：双方互为好友，且房主在线。

**加入流程**：

```
房客点击好友 → 点击「跟随」
        │
        ▼
房主收到通知弹窗（同时只显示一条，不堆叠）
        │
        ▼（自动加入，无需房主确认）
服务器返回房主当前完整播放状态：
{ current_track_id, position_ms, is_playing }
        │
        ├─── 房主正在播放 → 客户端切到该曲目，定位到该进度，同步播放/暂停状态
        │
        └─── 房主未在播放（浏览中）→ 客户端显示「正在跟随 [昵称]」标识，
                                     用户可自由操作，
                                     一旦房主开始播放立即强制同步
```

**特殊情形：房主本身是另一个房间的房客**：
- 若发起者与该房主的上级房主也是好友 → 发起者自动加入上级房主的房间
- 若发起者与上级房主不是好友 → 提示「[好友昵称] 正在跟随其他人，你无法加入」，流程终止

### 3.5 同步规则

**触发同步的操作**（房主执行以下任一操作，立即推送给所有房客）：

| 操作 | 同步内容 |
|------|----------|
| 切歌 | 新曲目 ID + 进度归零 + 当前播放状态 |
| 拖动进度条（seek） | 当前曲目 ID + 新进度 + 当前播放状态 |
| 暂停 | 当前曲目 ID + 当前进度 + `is_playing: false` |
| 继续播放 | 当前曲目 ID + 当前进度 + `is_playing: true` |

**同步执行逻辑（客户端）**：

收到同步消息时：
- 若曲目 ID 与当前播放曲目相同 → 仅 seek 到新进度，同步播放/暂停状态
- 若曲目 ID 不同 → 切换曲目，定位到指定进度，同步播放/暂停状态

**防死循环**：客户端收到同步消息执行操作时，设置内部标志 `isSyncing = true`，操作完成后清除。`isSyncing` 为 `true` 期间，播放器事件回调不向服务器上报任何状态变更。

### 3.6 房客的操作权限

| 操作 | 跟随状态下行为 |
|------|--------------|
| 调整音量 | ✅ 正常，本地操作 |
| 查看歌词/封面 | ✅ 正常，本地操作 |
| 暂停 / 继续 | ⚠️ 允许临时操作，但房主下一次有效指令来时立即覆盖 |
| 拖动进度条 | ⚠️ 允许临时操作，但房主下一次有效指令来时立即覆盖 |
| 切换歌曲 | ❌ 视为主动退出跟随，自动回到 `SOLO` 状态 |

### 3.7 断线重连

- 断线后 **30 秒内**自动重连，重连成功后恢复跟随状态，从房主当前位置同步
- 断线超过 **30 秒**：重连后弹窗提示「你与 [昵称] 的跟随因网络中断，是否重新跟随？」，用户手动确认

> 30 秒为建议值，可在配置中调整。

---

## 四、悬浮跟随按钮

### 4.1 显示时机

- 默认不显示
- 触发条件（满足任一）：
  - 用户本次 APP 启动后，曾经跟随过任何人
  - 用户本次 APP 启动后，曾经有人跟随过自己
- 触发后直到本次 APP 完全退出前持续显示，位置可拖动

### 4.2 按钮状态与点击行为

按钮根据当前用户角色显示不同内容：

**当前为 `SOLO` 状态**

点击展开面板，显示：
- 最近跟随过的最多 5 位好友（按最近跟随时间降序）
- 在线的好友：彩色显示，点击触发加入流程
- 离线的好友：灰色显示，点击无效
- 若 5 位中某人已不再是好友，不显示该条目，空位不补全

**当前为 `FOLLOWING`（房客）状态**

点击展开面板，显示：
- 当前跟随的房主昵称与状态
- 「追上」按钮：点击后立即从服务器拉取房主最新播放状态并同步
  - 若房主未在播放，提示「[昵称] 目前没有在播放」，无其他操作
- 「退出跟随」按钮

**当前为 `HOSTING`（房主）状态**

点击展开面板，显示：
- 当前跟随人数及房客昵称列表
- 「解散房间」按钮（预留扩展：未来可在此添加踢人功能）

---

## 五、播放状态数据结构

服务器为每个在线用户维护以下状态对象（内存存储，连接断开时清除）：

```json
{
  "user_id": "string",
  "role": "SOLO | HOSTING | FOLLOWING",
  "room_host_id": "string | null",
  "followers": ["user_id", "..."],
  "current_track_id": "string | null",
  "position_ms": 0,
  "is_playing": false,
  "last_active_at": "timestamp"
}
```

---

## 六、WebSocket 接口

### 连接

```
WS /ws?token={user_token}
```

### 上行消息（客户端 → 服务器）

```jsonc
// 房主：上报播放状态变更
{ "type": "STATE_UPDATE", "track_id": "xxx", "position_ms": 12000, "is_playing": true }

// 房客：发起跟随请求
{ "type": "FOLLOW_REQUEST", "target_user_id": "yyy" }

// 房客：退出跟随
{ "type": "UNFOLLOW" }

// 房客：点击「追上」
{ "type": "CATCH_UP" }

// 房主：解散房间
{ "type": "DISBAND_ROOM" }

// 房主：APP 后台化（客户端检测到 onPause）
{ "type": "APP_BACKGROUND" }

// 房主：APP 回到前台（客户端检测到 onResume）
{ "type": "APP_FOREGROUND" }
```

### 下行消息（服务器 → 客户端）

```jsonc
// 推送给房客：同步播放状态
{ "type": "SYNC", "track_id": "xxx", "position_ms": 12000, "is_playing": true }

// 推送给房主：有新房客加入（弹窗提示，同时只显示一条）
{ "type": "FOLLOWER_JOINED", "user_id": "zzz", "nickname": "名字", "total_followers": 3 }

// 推送给所有房客：房间解散
{ "type": "ROOM_DISBANDED", "host_nickname": "名字" }

// 推送给发起者：目标用户正在跟随他人且不可加入
{ "type": "FOLLOW_REJECTED", "reason": "host_is_follower" }

// 推送给发起者：追上响应
{ "type": "CATCH_UP_RESPONSE", "track_id": "xxx", "position_ms": 12000, "is_playing": true }
// 若房主未在播放：
{ "type": "CATCH_UP_RESPONSE", "track_id": null }

// 好友在线状态变化
{ "type": "PRESENCE_UPDATE", "user_id": "yyy", "online": true }
```

---

## 七、REST API 接口

### 7.1 音乐库

```
GET    /tracks                        # 获取全部曲目列表
GET    /tracks/{id}                   # 获取单曲信息
GET    /stream/{id}                   # 流式播放（支持 HTTP Range）
POST   /upload                        # 上传本地音频文件
POST   /import/url                    # 提交 YouTube/B 站链接，异步处理
GET    /task/{task_id}                # 查询导入任务状态
```

### 7.2 歌单

```
GET    /playlists                     # 获取当前用户的歌单列表
POST   /playlists                     # 创建歌单
GET    /playlists/{id}                # 获取歌单详情（含曲目）
PATCH  /playlists/{id}                # 修改歌单名
POST   /playlists/{id}/tracks         # 向歌单添加曲目
DELETE /playlists/{id}/tracks/{tid}   # 从歌单移除曲目
```

### 7.3 用户与好友

```
GET    /users/friends                 # 获取好友列表（含在线状态）
GET    /users/{id}/state              # 获取指定好友的当前播放角色状态
```

---

## 八、数据库表结构

```sql
-- 用户
CREATE TABLE users (
    id       TEXT PRIMARY KEY,
    nickname TEXT NOT NULL,
    token    TEXT UNIQUE NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 好友关系（双向：A-B 和 B-A 各存一条）
CREATE TABLE friendships (
    user_id   TEXT NOT NULL,
    friend_id TEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, friend_id)
);

-- 曲目
CREATE TABLE tracks (
    id          TEXT PRIMARY KEY,
    title       TEXT NOT NULL,
    artist      TEXT,
    duration_ms INTEGER,
    file_path   TEXT NOT NULL,
    cover_path  TEXT,
    source      TEXT DEFAULT 'upload',  -- upload | ytdlp | ncm | ai
    uploaded_by TEXT,
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 歌单
CREATE TABLE playlists (
    id         TEXT PRIMARY KEY,
    name       TEXT NOT NULL,
    owner_id   TEXT NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 歌单曲目关联
CREATE TABLE playlist_tracks (
    playlist_id TEXT NOT NULL,
    track_id    TEXT NOT NULL,
    position    INTEGER NOT NULL,
    PRIMARY KEY (playlist_id, track_id)
);

-- 跟随历史（用于悬浮按钮最近 5 位）
CREATE TABLE follow_history (
    user_id        TEXT NOT NULL,
    followed_id    TEXT NOT NULL,
    last_followed_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, followed_id)
);
```

> 用户播放状态（role / position_ms 等）存于服务器内存，不持久化到数据库。

---

## 九、分阶段开发步骤

### Phase 0 · 基础设施

**目标**：服务器跑通，APP 能播放服务器上的一首歌。

**后端**
1. 初始化 FastAPI 项目，建立目录结构（见第十一节）
2. 实现 `POST /upload`：接收 MP3/FLAC/WAV，存入 `storage/uploads/`，写入数据库
3. 实现 `GET /tracks`：返回曲目列表 JSON
4. 实现 `GET /stream/{id}`：正确处理 HTTP Range 请求头
5. Nginx 配置：HTTPS、反向代理到 FastAPI、`/storage/` 目录静态文件直接 serve

**客户端**
1. 从候选开源播放器中选定一个，评估标准：Repository 层独立、数据源可单独替换、以 ExoPlayer 为播放内核
2. 找到数据源层（本地文件扫描处），新建 `RemoteTrackRepository`，调用 `GET /tracks`
3. 将 stream URL 传入 ExoPlayer，支持 seek
4. 联调：APP 展示服务器曲目列表，点击播放，拖进度条正常

**完成标志**：APP 能播放服务器上的音频文件，拖动进度条正常。

---

### Phase 1 · 歌单功能

**目标**：支持创建歌单、管理曲目、顺序播放。

**后端**
1. 实现歌单全部接口（见 7.2）
2. 实现轻量用户系统：初期硬编码若干用户 + token，接口预留扩展为注册流程的空间

**客户端**
1. 歌单列表页：展示、创建、删除
2. 歌单详情页：曲目列表、拖拽排序、进入播放
3. 播放器支持队列模式：上一首/下一首、循环/随机

**完成标志**：可创建歌单并连续播放。

---

### Phase 2 · 好友系统

**目标**：双向好友关系建立，好友在线状态可见。

**实现策略**：优先复用所选开源 APP 的好友/社交功能实现，按业务需求裁剪或改造。

**后端**
1. 实现好友申请、确认、删除接口（具体接口设计依 APP 复用情况确定）
2. 实现 `GET /users/friends`，返回好友列表及在线状态
3. WebSocket 连接建立/断开时，向该用户所有在线好友推送 `PRESENCE_UPDATE`

**客户端**
1. 好友列表页：显示在线/离线状态
2. 好友申请流程：发送、接收通知、确认

**完成标志**：双方互加好友后，可在好友列表看到对方在线状态实时变化。

---

### Phase 3 · 跟随系统

**目标**：跟随系统完整上线，这是本系统的核心差异化功能。

**后端**
1. 完整实现 WebSocket 接口（见第六节所有消息类型）
2. 实现服务器端用户状态管理（内存 Map）：
   - 连接映射：`user_id → websocket`
   - 状态映射：`user_id → UserState`
   - 房间映射：`host_id → Set<follower_id>`
3. 实现后台超时逻辑：收到 `APP_BACKGROUND` 后启动 1 小时计时器，收到 `APP_FOREGROUND` 或任何 `STATE_UPDATE` 后重置；超时后服务器主动触发解散流程
4. 实现 `GET /users/{id}/state`：返回该用户当前角色和播放状态

**客户端**
1. 好友详情页新增「跟随」按钮（仅对方在线时可点击）
2. 实现跟随加入流程（见 3.4 节）
3. 实现播放器内跟随状态同步逻辑：
   - 收到 `SYNC` 消息时设置 `isSyncing = true`，执行操作，完成后清除
   - `isSyncing` 期间屏蔽播放器事件回调的上报
4. 实现房客操作权限拦截（切歌触发退出跟随，其余操作允许但会被覆盖）
5. 实现悬浮跟随按钮：
   - 显示时机：本次启动后有任何跟随相关操作后出现，可拖动，退出前持续显示
   - `SOLO` 状态面板：最近 5 位好友列表，在线彩色可点，离线灰色不可点
   - `FOLLOWING` 状态面板：「追上」按钮 + 「退出跟随」按钮 + 房主信息
   - `HOSTING` 状态面板：当前房客列表 + 「解散房间」按钮（预留踢人接口位置）
6. 实现弹窗通知逻辑：
   - 房主侧：有人加入时弹窗，同时只显示一条（新通知替换旧通知，不堆叠）
7. 实现断线重连逻辑：
   - 30 秒内静默重连，成功后自动恢复跟随状态
   - 超过 30 秒后弹窗询问是否重新跟随

**完成标志**：用户 A 加入用户 B，B 切歌、拖进度、暂停，A 全部自动同步；A 自行换歌后退出跟随；A 点击悬浮按钮「追上」后重新对齐 B 的位置。

---

### Phase 4 · 在线音源导入

**目标**：输入链接自动下载转音频入库。

**后端**
1. 安装 yt-dlp 和 FFmpeg
2. 实现 `POST /import/url`：验证域名白名单（YouTube / B 站），创建后台任务，返回 `task_id`
3. 后台任务流程：yt-dlp 下载 → FFmpeg 转 MP3 → 写入数据库
4. 实现 `GET /task/{task_id}`：返回任务状态（`pending / processing / done / failed`）和结果
5. B 站地区限制处理：先测试 VPS 所在 IP 可访问范围；如受限，通过 FRP 路由到国内机器执行，文件回传后入库

**客户端**
1. 上传页面新增「输入链接」Tab
2. 提交后轮询任务状态，完成后曲目自动出现在音乐库

**完成标志**：输入 YouTube 链接，处理完成后曲目出现在 APP 中并可正常播放。

---

### Phase 5 · .ncm 本地导入

**目标**：将网易云客户端已下载的加密歌曲导入系统。

**后端**
1. 集成 ncmdump 或 unlock-music，测试 .ncm → MP3 转换流程
2. 新增 `POST /import/ncm` 接口：接收 .ncm 文件，服务器端解密转换，入库

**客户端**
1. 申请 `READ_EXTERNAL_STORAGE` 权限
2. 扫描设备上网易云常见下载目录（`/netease/cloudmusic/Music/`）
3. 展示扫描结果，用户勾选后上传，显示上传进度

**完成标志**：从设备上选择 .ncm 文件，上传后在 APP 中正常播放。

---

### Phase 6 · 体验完善

以下功能无强制顺序，按需实现：

- [ ] 专辑封面显示（上传时提取 ID3 tag 封面，或允许手动上传图片）
- [ ] 歌词支持（`.lrc` 文件上传，播放时同步滚动）
- [ ] 学习用连续歌单：FFmpeg 将多首曲目拼接为单一音频文件，生成章节索引
- [ ] 播放历史记录
- [ ] 搜索功能（按曲名、歌手名过滤）
- [ ] 跟随管理面板新增踢人功能（接口位置已在 Phase 3 预留）

---

### Phase 7 · AI 歌单（进阶可选）

**目标**：用自然语言描述心情，自动生成歌单。

**架构**：本地机器部署 Ollama，加载指令理解类开源模型；通过 FRP 暴露推理接口到公网，云端服务器转发请求。

**后端**
1. 新增 `POST /ai/playlist` 接口
2. 接收自然语言描述，转发到本地 AI 节点
3. AI 返回搜索关键词列表（JSON），服务器逐条调用 yt-dlp 下载处理
4. AI 来源音乐存入 `storage/ai/`，`source` 字段标记为 `ai`，支持一键清空接口

**客户端**
1. 新增「AI 生成歌单」入口（对话输入框）
2. 提交后展示处理进度
3. 生成完成后展示歌单，用户确认后保存

**完成标志**：输入心情描述，系统自动生成可播放歌单。

---

## 十、难点清单

### 🔴 需要单独攻破

**1. 网易云直接鉴权下载**
- 难点：VIP 曲目接口有签名鉴权，格式加密为 .ncm，服务器代理模式有被封风险
- 建议：Phase 5 优先推进「本地 .ncm 上传」绕过鉴权；直接下载方案参考 [NeteaseCloudMusicApi](https://github.com/Binaryify/NeteaseCloudMusicApi)

**2. B 站地区访问限制**
- 难点：部分音乐区内容仅对国内 IP 开放
- 建议：通过 FRP 将下载任务路由到国内机器执行，完成后文件回传

### 🟡 需要仔细处理

**3. WebSocket 同步死循环防御**
- 见 3.5 节「防死循环」，客户端必须实现 `isSyncing` 标志

**4. 后台超时房间解散逻辑**
- 服务器需要为每个处于 `HOSTING` 状态且 APP 已后台化的用户维护独立计时器
- 收到 `APP_FOREGROUND` 或任何 `STATE_UPDATE` 时重置计时器
- 注意：计时器在服务器内存中，服务器重启需考虑状态恢复策略

**5. HTTP Range 请求**
- 使用 Nginx 直接 serve 静态文件是最简方案，开启 `sendfile` 和 `tcp_nopush`，无需在 FastAPI 层处理

**6. 开源 APP 改造的代码熟悉成本**
- 优先找到 Repository / DataSource 层，只动这一层
- 播放器内核（ExoPlayer）不动，stream URL 直接传入

### 🟢 有现成方案，难度可控

- .ncm 解密 → ncmdump / unlock-music，命令行一键转换
- yt-dlp 集成 → Python API 直接调用
- FFmpeg 音频处理 → 命令行，文档完善
- FastAPI WebSocket → 官方文档有完整示例

---

## 十一、后端目录结构

```
server/
├── main.py
├── config.py                  # 环境变量、路径、超时时长等配置
├── database.py                # SQLite 连接与初始化
├── models.py                  # 数据模型（SQLAlchemy）
├── routers/
│   ├── tracks.py              # 音乐库接口
│   ├── playlists.py           # 歌单接口
│   ├── import_jobs.py         # 导入任务接口
│   ├── users.py               # 用户与好友接口
│   └── websocket.py           # WebSocket 入口
├── services/
│   ├── sync_manager.py        # 连接管理、状态维护、广播逻辑、超时计时
│   ├── room_manager.py        # 房间创建/解散/跟随关系管理
│   ├── downloader.py          # yt-dlp 调用封装
│   ├── converter.py           # FFmpeg / ncmdump 调用封装
│   └── ai_client.py           # AI 节点通信（Phase 7）
├── storage/
│   ├── uploads/               # 用户上传和导入的音乐
│   └── ai/                    # AI 自动生成的音乐（可一键清空）
└── requirements.txt
```

---

*文档版本：v0.3*
