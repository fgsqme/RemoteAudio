#!/usr/bin/env python3
"""协议冒烟测试：启动服务端，校验握手 JSON 与音频帧封包，随后 SIGINT 退出。

用法：python3 scripts/protocol_smoke.py [服务端可执行文件路径]
默认使用 target/debug/remote-audio-server。
"""
import json
import os
import signal
import socket
import struct
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SERVER = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "target/debug/remote-audio-server")

srv = subprocess.Popen([SERVER], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
time.sleep(1.5)
try:
    s = socket.create_connection(("127.0.0.1", 9876), timeout=5)
    s.settimeout(5)
    f = s.makefile("rb")
    hello = json.loads(f.readline())
    print("handshake:", hello)
    assert hello["magic"] == "RAUD" and hello["codec"] == "opus"
    assert hello["sample_rate"] == 48000 and hello["channels"] == 2 and hello["frame_ms"] == 20
    total = 0
    for i in range(5):
        seq, ln = struct.unpack(">IH", f.read(6))
        payload = f.read(ln)
        assert len(payload) == ln and ln > 0
        total += ln
        print(f"frame {i}: seq={seq} opus_bytes={ln} toc=0x{payload[0]:02x}")
    print(f"OK: 5 frames, {total} bytes opus")
    s.close()
finally:
    srv.send_signal(signal.SIGINT)
    srv.wait(timeout=5)
    print("server exited:", srv.returncode)