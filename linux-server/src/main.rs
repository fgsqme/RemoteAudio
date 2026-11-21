mod capture;
mod encoder;
mod server;
mod sink;

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

use anyhow::Result;
use clap::Parser;
use tracing::info;

#[derive(Parser)]
#[command(
    name = "remote-audio-server",
    about = "采集 Linux 虚拟声卡音频，经 TCP/Opus 串流到 Android 播放"
)]
struct Args {
    /// TCP 监听端口
    #[arg(long, default_value_t = 9876)]
    port: u16,

    /// Opus 编码码率（bps）
    #[arg(long, default_value_t = 192_000)]
    bitrate: i32,

    /// 虚拟 sink 名称
    #[arg(long, default_value = "remote_audio")]
    sink_name: String,
}

fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info".into()),
        )
        .init();
    let args = Args::parse();

    let shutdown = Arc::new(AtomicBool::new(false));
    {
        let flag = shutdown.clone();
        ctrlc::set_handler(move || flag.store(true, Ordering::Relaxed))?;
    }

    // 守卫对象持有 PulseAudio 连接，退出时自动卸载虚拟 sink
    let _sink = sink::VirtualSink::create(&args.sink_name)?;
    info!(
        "虚拟声卡 '{}' 已创建，请把应用/系统输出切换到 RemoteAudio 设备",
        args.sink_name
    );

    server::run(args.port, &args.sink_name, args.bitrate, shutdown)?;

    info!("正在退出，清理虚拟声卡");
    Ok(())
}