# RemoteAudio

把 Linux 电脑的声音实时串流到 Android 手机播放——手机变成电脑的无线音箱。

Linux 端创建一个虚拟声卡，任何应用把声音输出到它，音频就会经 Opus 编码后通过局域网 TCP 推送到手机；Android 端解码播放，支持后台常驻、息屏播放、断线自动重连。

```
Linux (Rust)                                Android (Kotlin + Compose)
┌──────────────────────────┐                ┌───────────────────────────┐
│ 应用音频 → 虚拟声卡        │                │ TCP 客户端（手动输 IP）     │
│ (PipeWire/PulseAudio     │   Opus / TCP   │ ↓ 抖动缓冲（60ms 预滚）     │
│  null-sink)              │ ─────────────→ │ NDK libopus 解码           │
│ monitor 采集 48kHz PCM   │   局域网/WiFi   │ ↓                         │
│ Opus LowDelay 编码       │                │ AudioTrack 低延迟播放       │
│ TCP 服务端 :9876         │                │ 前台服务保活 + 自动重连      │
└──────────────────────────┘                └───────────────────────────┘
```

## 特性

- **虚拟声卡**：基于 PulseAudio 协议 `module-null-sink`，兼容原生 PulseAudio 与 PipeWire（pipewire-pulse），无需内核驱动
- **低延迟**：Opus LowDelay 模式 + 紧凑缓冲，局域网稳态延迟约 **130–160ms**
- **高音质**：48kHz 立体声，Opus 192kbps VBR（可调）
- **后台常驻**：mediaPlayback 前台服务 + WakeLock/WifiLock，息屏、划掉任务继续播放
- **断线自愈**：网络抖动/服务端重启后每 3 秒自动重连；进程被系统杀死后 START_STICKY 自动恢复上次会话
- **易用**：IP/端口自动缓存，下次打开直接连；通知栏显示播放状态，点击回到 App

## 目录结构

```
├── app/                    Android 客户端（Kotlin + Jetpack Compose，minSdk 24）
│   └── src/main/
│       ├── cpp/            NDK：CMake + libopus JNI 解码
│       └── java/.../
│           ├── net/        TCP 接收与协议拆帧
│           ├── audio/      Opus 解码、AudioTrack 播放
│           └── service/    前台服务（重连/保活/会话持久化）
├── linux-server/           Linux 服务端（Rust）
│   ├── src/                sink 管理 / 采集 / 编码 / TCP 服务
│   └── scripts/            协议冒烟测试
└── gradle/                 版本目录与 wrapper
```

## 快速开始

### 1. Linux 服务端

依赖：Rust 工具链、PulseAudio 或 PipeWire（pipewire-pulse）正在运行。

```bash
# Ubuntu/Debian
sudo apt install libpulse-dev libopus-dev pkg-config

cd linux-server
cargo build --release
./target/release/remote-audio-server
```

启动后自动创建名为 `remote_audio` 的虚拟输出设备（显示名 **RemoteAudio**），监听 TCP **9876** 端口。Ctrl+C 退出时自动清理虚拟声卡。

可选参数：

| 参数 | 默认 | 说明 |
|---|---|---|
| `--port` | 9876 | TCP 监听端口 |
| `--bitrate` | 192000 | Opus 码率（bps） |
| `--sink-name` | remote_audio | 虚拟 sink 名称 |

把声音路由到虚拟设备（三选一）：

- 系统声音设置 / `pavucontrol` 中把应用输出切换为 **RemoteAudio**
- 命令行测试：`paplay -d remote_audio test.wav`
- 临时全局默认：`pactl set-default-sink remote_audio`（用完记得改回）

### 2. Android 客户端

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 首次构建需联网：Gradle 自动下载 NDK/CMake，CMake FetchContent 拉取 opus-1.5.2 源码。

安装 APK 后，输入 Linux 主机的**局域网 IP** 和端口（默认 9876），点「连接并播放」。IP/端口会被缓存，下次无需再输。

两端需处于同一局域网；如有防火墙，放行 TCP 9876。

## 传输协议

- Android 作为 TCP 客户端连接服务端；服务端同一时刻只服务一个客户端，新连接顶替旧连接
- 握手：服务端发送一行 JSON

  ```json
  {"magic":"RAUD","version":1,"sample_rate":48000,"channels":2,"frame_ms":20,"codec":"opus"}
  ```

- 音频帧：`[u32 seq][u16 payload_len][opus payload]`，大端序，20ms/帧；静音时持续发送（Opus 静音包仅 3 字节）

冒烟测试（自动启动服务端、校验收帧、优雅退出）：

```bash
cd linux-server
python3 scripts/protocol_smoke.py
```

## 延迟说明

稳态延迟预算（约 130–160ms）：

| 环节 | 延迟 | 调整位置 |
|---|---|---|
| 采集分片 | ~20ms | `capture.rs` fragsize |
| Opus 算法延迟 | ~2.5ms | `encoder.rs` Application::LowDelay |
| 局域网传输 | 1–5ms | — |
| AudioTrack 缓冲 | ~80ms | `PcmPlayer.kt` TRACK_BUFFER_MS |
| 抖动余量 | ~40ms | `PcmPlayer.kt` MAX_BUFFER_MS |

缓冲越小延迟越低，但 WiFi 抖动时越容易触发短暂「缓冲中」重新起播，属正常权衡。如需 <100ms（如游戏音画同步），需改用 UDP + 自适应抖动缓冲 + AAudio 独占模式，当前 TCP 架构调参无法达到。

## 常见问题

**编译报 `audiopus_sys` / `libpulse-sys` 构建失败**
缺少系统开发包：`sudo apt install libpulse-dev libopus-dev pkg-config`。

**编译报 E0425 `pa_check_version` not found**
上游 semver 不兼容：`libpulse-binding 2.28.x` 需搭配 `libpulse-sys 1.22.x`。本仓库 `Cargo.lock` 已锁定；若自行升级依赖后出现，执行 `cargo update -p libpulse-sys --precise 1.22.0`。

**服务端报「连接 PulseAudio 服务失败」**
确认 PulseAudio / pipewire-pulse 正在运行（`systemctl --user status pipewire-pulse`）。

**手机连不上**
确认同一局域网、IP 正确、防火墙放行 9876；服务端日志会打印客户端连接/断开事件。

**息屏后播放停止（部分国产 ROM）**
激进省电策略可能杀前台服务：在系统设置中给本应用加入电池优化白名单 / 允许后台运行。

**Android 端 Opus 解码兼容性**
不使用 MediaCodec 硬解（API 29 以下设备不保证支持 Opus），而是 NDK 编译 libopus 软解，minSdk 24 全设备行为一致，CPU 开销极低。

## 技术栈

- **Linux**：Rust、libpulse-binding / libpulse-simple-binding、audiopus（libopus）、clap、tracing
- **Android**：Kotlin、Jetpack Compose (Material 3)、NDK + CMake（opus-1.5.2）、AudioTrack、前台服务、kotlinx.coroutines StateFlow

## 许可证

[Apache License 2.0](LICENSE)