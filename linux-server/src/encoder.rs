//! libopus 编码：20ms PCM 帧 -> Opus 包。

use anyhow::Result;
use audiopus::{coder::Encoder, Application, Bitrate, Channels, SampleRate};

use crate::capture::{CHANNELS, FRAME_SAMPLES};

pub struct OpusEncoder {
    encoder: Encoder,
    pcm: Vec<i16>,
    out: Vec<u8>,
}

impl OpusEncoder {
    pub fn new(bitrate: i32) -> Result<Self> {
        // LowDelay 模式算法延迟最低（约 2.5ms），高码率下音质损失可忽略
        let mut encoder = Encoder::new(SampleRate::Hz48000, Channels::Stereo, Application::LowDelay)?;
        encoder.set_bitrate(Bitrate::BitsPerSecond(bitrate))?;
        Ok(Self {
            encoder,
            pcm: vec![0i16; FRAME_SAMPLES * CHANNELS as usize],
            out: vec![0u8; 4000],
        })
    }

    /// 输入一帧 S16LE 交错 PCM 字节，返回编码后的 Opus 包。
    pub fn encode(&mut self, frame_bytes: &[u8]) -> Result<&[u8]> {
        for (i, chunk) in frame_bytes.chunks_exact(2).enumerate() {
            self.pcm[i] = i16::from_le_bytes([chunk[0], chunk[1]]);
        }
        let len = self.encoder.encode(&self.pcm, &mut self.out)?;
        Ok(&self.out[..len])
    }
}