//! TCP 服务端：握手 JSON + 二进制音频帧 [u32 seq][u16 len][opus payload]（大端）。
//! 同一时刻只服务一个客户端，新连接顶替旧连接。

use std::io::Write;
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use anyhow::{Context, Result};
use tracing::{info, warn};

use crate::capture::Capture;
use crate::encoder::OpusEncoder;

const HANDSHAKE: &str = concat!(
    "{\"magic\":\"RAUD\",\"version\":1,\"sample_rate\":48000,",
    "\"channels\":2,\"frame_ms\":20,\"codec\":\"opus\"}\n"
);

struct Client {
    stream: TcpStream,
    handshake_done: bool,
}

type ClientSlot = Arc<Mutex<Option<Client>>>;

/// 接受连接线程：新连接直接替换槽位中的旧客户端（旧连接随之关闭）。
fn spawn_acceptor(listener: TcpListener, slot: ClientSlot, shutdown: Arc<AtomicBool>) {
    std::thread::spawn(move || {
        listener
            .set_nonblocking(true)
            .expect("设置 nonblocking 失败");
        while !shutdown.load(Ordering::Relaxed) {
            match listener.accept() {
                Ok((stream, addr)) => {
                    if let Err(e) = stream.set_nodelay(true) {
                        warn!("设置 TCP_NODELAY 失败: {e}");
                    }
                    // 写超时兜底：客户端僵死时及时踢掉
                    let _ = stream.set_write_timeout(Some(Duration::from_secs(2)));
                    info!("客户端已连接: {addr}");
                    *slot.lock().unwrap() = Some(Client {
                        stream,
                        handshake_done: false,
                    });
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    std::thread::sleep(Duration::from_millis(100));
                }
                Err(e) => {
                    warn!("accept 失败: {e}");
                    std::thread::sleep(Duration::from_millis(100));
                }
            }
        }
    });
}

/// 采集-编码-发送主循环，直到收到退出信号。
pub fn run(port: u16, sink_name: &str, bitrate: i32, shutdown: Arc<AtomicBool>) -> Result<()> {
    let listener = TcpListener::bind(("0.0.0.0", port))
        .with_context(|| format!("绑定端口 {port} 失败"))?;
    info!("TCP 服务端监听 0.0.0.0:{port}");

    let slot: ClientSlot = Arc::new(Mutex::new(None));
    spawn_acceptor(listener, slot.clone(), shutdown.clone());

    let mut capture = Capture::new(&format!("{sink_name}.monitor"))?;
    let mut encoder = OpusEncoder::new(bitrate)?;
    let mut seq: u32 = 0;
    let mut header = [0u8; 6];

    while !shutdown.load(Ordering::Relaxed) {
        let frame = capture.read_frame()?;
        let payload = encoder.encode(frame)?;
        seq = seq.wrapping_add(1);
        header[..4].copy_from_slice(&seq.to_be_bytes());
        header[4..].copy_from_slice(&(payload.len() as u16).to_be_bytes());

        let mut guard = slot.lock().unwrap();
        if let Some(client) = guard.as_mut() {
            let result = send_frame(client, &header, payload);
            if let Err(e) = result {
                warn!("客户端断开: {e}");
                *guard = None;
            }
        }
    }
    Ok(())
}

fn send_frame(client: &mut Client, header: &[u8; 6], payload: &[u8]) -> std::io::Result<()> {
    if !client.handshake_done {
        client.stream.write_all(HANDSHAKE.as_bytes())?;
        client.handshake_done = true;
    }
    client.stream.write_all(header)?;
    client.stream.write_all(payload)?;
    Ok(())
}