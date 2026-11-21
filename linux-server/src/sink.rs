//! 通过 PulseAudio 协议创建/销毁 module-null-sink 虚拟声卡。
//! 兼容原生 PulseAudio 与 PipeWire 的 pipewire-pulse 兼容层。

use std::cell::RefCell;
use std::rc::Rc;

use anyhow::{bail, Context as _, Result};
use libpulse_binding as pulse;
use pulse::context::Context;
use pulse::mainloop::standard::{IterateResult, Mainloop};
use pulse::operation::State;
use pulse::proplist::Proplist;
use tracing::debug;

pub struct VirtualSink {
    mainloop: Mainloop,
    context: Context,
    module_index: Option<u32>,
}

impl VirtualSink {
    /// 连接 PulseAudio 服务并加载 null-sink，返回持有连接的守卫对象。
    pub fn create(sink_name: &str) -> Result<Self> {
        let mut proplist = Proplist::new().context("创建 proplist 失败")?;
        proplist
            .set_str(pulse::proplist::properties::APPLICATION_NAME, "RemoteAudioServer")
            .map_err(|_| anyhow::anyhow!("设置应用名失败"))?;

        let mut mainloop = Mainloop::new().context("创建 PulseAudio mainloop 失败")?;
        let mut context =
            Context::new_with_proplist(&mainloop, "RemoteAudioServerContext", &proplist)
                .context("创建 PulseAudio context 失败")?;
        context
            .connect(None, pulse::context::FlagSet::NOFLAGS, None)
            .map_err(|e| anyhow::anyhow!("连接 PulseAudio 服务失败: {e:?}"))?;

        // 等待连接就绪
        loop {
            match mainloop.iterate(true) {
                IterateResult::Success(_) => {}
                IterateResult::Quit(_) | IterateResult::Err(_) => {
                    bail!("PulseAudio mainloop 迭代失败");
                }
            }
            match context.get_state() {
                pulse::context::State::Ready => break,
                pulse::context::State::Failed | pulse::context::State::Terminated => {
                    bail!("PulseAudio 连接失败，请确认 pulseaudio/pipewire-pulse 正在运行");
                }
                _ => {}
            }
        }

        // 加载虚拟 sink
        let index: Rc<RefCell<Option<u32>>> = Rc::new(RefCell::new(None));
        let index_cb = index.clone();
        let args = format!(
            "sink_name={sink_name} sink_properties=device.description=RemoteAudio"
        );
        let op = context.introspect().load_module(
            "module-null-sink",
            &args,
            move |idx| {
                // PA_INVALID_INDEX (u32::MAX) 表示加载失败
                if idx != u32::MAX {
                    *index_cb.borrow_mut() = Some(idx);
                }
            },
        );
        while op.get_state() == State::Running {
            if !matches!(mainloop.iterate(true), IterateResult::Success(_)) {
                bail!("PulseAudio mainloop 迭代失败");
            }
        }
        drop(op);
        let module_index = (*index.borrow())
            .context("加载 module-null-sink 失败")?;
        debug!("module-null-sink loaded, index={module_index}");

        Ok(Self {
            mainloop,
            context,
            module_index: Some(module_index),
        })
    }
}

impl Drop for VirtualSink {
    fn drop(&mut self) {
        // 主动卸载模块；即使失败，断开连接后服务端也会自动回收客户端持有的模块
        if let Some(idx) = self.module_index.take() {
            let done = Rc::new(RefCell::new(false));
            let done_cb = done.clone();
            let op = self
                .context
                .introspect()
                .unload_module(idx, move |_ok| {
                    *done_cb.borrow_mut() = true;
                });
            for _ in 0..100 {
                if *done.borrow() {
                    break;
                }
                if !matches!(self.mainloop.iterate(true), IterateResult::Success(_)) {
                    break;
                }
            }
            drop(op);
        }
        self.context.disconnect();
    }
}