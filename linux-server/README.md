# RemoteAudio Linux 服务端

采集 Linux 虚拟声卡（PulseAudio / PipeWire null-sink）的音频，Opus 编码后经 TCP 串流到 Android 端播放。

## 系统依赖

- Rust 工具链（cargo / rustc）
- `libpulse-dev`、`libopus-dev`、`pkg-config`
- 运行中的 PulseAudio 或 PipeWire（pipewire-pulse 兼容层）

```bash
sudo apt install libpulse-dev libopus-dev pkg-config
```

## 构建与运行

```bash
cargo build --release
./target/release/remote-audio-server            # 默认端口 9876，码率 192kbps
./target/release/remote-audio-server --port 9876 --bitrate 192000 --sink-name remote_audio
```

启动后会创建名为 `remote_audio` 的虚拟输出设备（显示名 RemoteAudio），并监听 TCP 端口。

## 把声音路由到虚拟设备

- 图形界面：在系统声音设置 / `pavucontrol` 中把应用输出设备切换为 RemoteAudio；
- 命令行测试：`paplay -d remote_audio test.wav`；
- 临时全局默认：`pactl set-default-sink remote_audio`（退出前记得改回）。

Android 端输入本机局域网 IP 与端口即可连接播放。Ctrl+C 退出时自动卸载虚拟声卡。

## 协议

- 握手：服务端发送一行 JSON `{"magic":"RAUD","version":1,"sample_rate":48000,"channels":2,"frame_ms":20,"codec":"opus"}`
- 音频帧：`[u32 seq][u16 payload_len][opus payload]`，大端序，20ms/帧。