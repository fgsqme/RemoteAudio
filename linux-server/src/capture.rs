//! 从虚拟 sink 的 monitor 源采集 PCM（pulse-simple 阻塞读取）。

use anyhow::{bail, Result};
use libpulse_binding as pulse;
use pulse::def::BufferAttr;
use pulse::sample::{Format, Spec};
use pulse::stream::Direction;
use libpulse_simple_binding::Simple;

pub const SAMPLE_RATE: u32 = 48_000;
pub const CHANNELS: u8 = 2;
/// 每声道 20ms 的样本数
pub const FRAME_SAMPLES: usize = 960;
/// 一帧 PCM 的字节数（S16LE 交错）
pub const FRAME_BYTES: usize = FRAME_SAMPLES * CHANNELS as usize * 2;

pub struct Capture {
    simple: Simple,
    buf: Vec<u8>,
}

impl Capture {
    pub fn new(monitor_source: &str) -> Result<Self> {
        let spec = Spec {
            format: Format::S16le,
            channels: CHANNELS,
            rate: SAMPLE_RATE,
        };
        if !spec.is_valid() {
            bail!("非法的采样规格");
        }
        // 限制服务端缓冲，避免采集端积压引入额外延迟（20ms 分片，最多 4 帧）
        let attr = BufferAttr {
            maxlength: (FRAME_BYTES * 4) as u32,
            tlength: 0,
            prebuf: 0,
            minreq: 0,
            fragsize: FRAME_BYTES as u32,
        };
        let simple = Simple::new(
            None,
            "RemoteAudioServer",
            Direction::Record,
            Some(monitor_source),
            "capture",
            &spec,
            None,
            Some(&attr),
        )
        .map_err(|e| {
            anyhow::anyhow!("打开 monitor 源 {monitor_source} 失败: {e:?}")
        })?;
        Ok(Self {
            simple,
            buf: vec![0u8; FRAME_BYTES],
        })
    }

    /// 阻塞读取一帧 20ms PCM，返回 S16LE 交错字节切片。
    pub fn read_frame(&mut self) -> Result<&[u8]> {
        self.simple
            .read(&mut self.buf)
            .map_err(|e| anyhow::anyhow!("采集读取失败: {e:?}"))?;
        Ok(&self.buf)
    }
}