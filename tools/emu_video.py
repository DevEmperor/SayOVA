"""Records the emulator's screen over its gRPC bridge (screenrecord in the guest crashed the emulator).

emu_video.py <out.mp4> <seconds> [fps]  - real-time video; the latest frame is repeated at each tick.
Run with the venv of emulator.sh (grpcio and the generated stubs).
"""
import subprocess
import sys
import threading
import time

sys.path.insert(0, f"{__import__('os').path.expanduser('~')}/.cache/parley-emulator-venv/stubs")
import grpc  # noqa: E402
import emulator_controller_pb2 as pb  # noqa: E402
import emulator_controller_pb2_grpc as rpc  # noqa: E402

out, seconds = sys.argv[1], float(sys.argv[2])
fps = int(sys.argv[3]) if len(sys.argv) > 3 else 30
channel = grpc.insecure_channel("localhost:8554", options=[("grpc.max_receive_message_length", 64 << 20)])
stub = rpc.EmulatorControllerStub(channel)
fmt = pb.ImageFormat(format=pb.ImageFormat.RGB888)
latest = {"img": None}
stop = False


def reader():
    for image in stub.streamScreenshot(fmt):
        latest["img"] = image
        if stop:
            break


threading.Thread(target=reader, daemon=True).start()
while latest["img"] is None:
    time.sleep(0.01)
w, h = latest["img"].format.width, latest["img"].format.height
ff = subprocess.Popen(
    ["ffmpeg", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{w}x{h}", "-r", str(fps),
     "-i", "-", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "18", out],
    stdin=subprocess.PIPE,
)
start = time.monotonic()
frame = 0
while time.monotonic() - start < seconds:
    target = start + frame / fps
    now = time.monotonic()
    if target > now:
        time.sleep(target - now)
    ff.stdin.write(latest["img"].image)
    frame += 1
stop = True
ff.stdin.close()
ff.wait()
print(f"{frame} frames, {w}x{h}")
