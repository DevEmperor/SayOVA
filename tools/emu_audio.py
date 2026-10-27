#!/usr/bin/env python3
"""Silent audio for the test emulator, over its gRPC bridge (see tools/emulator.sh).

    emu_audio.py inject <speech.wav>          speak a recording into the watch's microphone
    emu_audio.py record <out.wav> <seconds>   record what the watch plays for that long

injectAudio and streamAudio are the emulator's own RPCs (emulator/lib/emulator_controller.proto), the same
ones Android Studio's embedded emulator uses: nothing goes through the desktop's sound system, so nothing is
heard in the room and no device of the desktop is touched.
"""
import os
import sys
import time
import wave

sys.path.insert(0, os.path.expanduser("~/.cache/parley-emulator-venv/stubs"))
import grpc  # noqa: E402
import emulator_controller_pb2 as pb  # noqa: E402
import emulator_controller_pb2_grpc as rpc  # noqa: E402

GRPC = os.environ.get("PARLEY_EMU_GRPC", "localhost:8554")
RATE = 16_000
CHUNK_MS = 20


def stub():
    return rpc.EmulatorControllerStub(grpc.insecure_channel(GRPC))


def read_mono16(path: str) -> bytes:
    """Reads a 16-bit WAV, mixes it to mono and resamples it linearly to 16 kHz."""
    with wave.open(path, "rb") as w:
        if w.getsampwidth() != 2:
            raise SystemExit("only 16-bit WAV is supported")
        channels, rate = w.getnchannels(), w.getframerate()
        raw = w.readframes(w.getnframes())
    samples = [int.from_bytes(raw[i:i + 2], "little", signed=True) for i in range(0, len(raw), 2)]
    if channels > 1:
        samples = [sum(samples[i:i + channels]) // channels for i in range(0, len(samples), channels)]
    if rate != RATE:
        step = rate / RATE
        out_len = int(len(samples) / step)
        resampled = []
        for i in range(out_len):
            pos = i * step
            a = samples[min(int(pos), len(samples) - 1)]
            b = samples[min(int(pos) + 1, len(samples) - 1)]
            resampled.append(int(a + (b - a) * (pos - int(pos))))
        samples = resampled
    return b"".join(s.to_bytes(2, "little", signed=True) for s in samples)


def inject(path: str) -> None:
    pcm = read_mono16(path)
    # A short lead-in and tail of silence, so voice activity detection sees a clean start and end.
    pcm = bytes(RATE * 2 // 4) + pcm + bytes(RATE * 2)
    fmt = pb.AudioFormat(samplingRate=RATE, channels=pb.AudioFormat.Mono, format=pb.AudioFormat.AUD_FMT_S16)
    chunk = RATE * 2 * CHUNK_MS // 1000

    def packets():
        for i in range(0, len(pcm), chunk):
            yield pb.AudioPacket(format=fmt, timestamp=int(time.time() * 1e6), audio=pcm[i:i + chunk])

    stub().injectAudio(packets())
    print(f"injected {len(pcm) / (RATE * 2):.1f} s from {path}")


def record(path: str, seconds: float) -> None:
    fmt = pb.AudioFormat(samplingRate=RATE, channels=pb.AudioFormat.Mono, format=pb.AudioFormat.AUD_FMT_S16)
    deadline = time.time() + seconds
    frames = bytearray()
    try:
        for packet in stub().streamAudio(fmt, timeout=seconds + 2):
            frames += packet.audio
            if time.time() >= deadline:
                break
    except grpc.RpcError as e:
        # The stream ends with DEADLINE_EXCEEDED when the watch stayed silent; what arrived is kept.
        if e.code() != grpc.StatusCode.DEADLINE_EXCEEDED:
            raise
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(bytes(frames))
    peak = max((abs(int.from_bytes(frames[i:i + 2], "little", signed=True)) for i in range(0, len(frames), 2)), default=0)
    print(f"recorded {len(frames) / (RATE * 2):.1f} s to {path} (peak {peak})")


if __name__ == "__main__":
    if len(sys.argv) >= 3 and sys.argv[1] == "inject":
        inject(sys.argv[2])
    elif len(sys.argv) >= 4 and sys.argv[1] == "record":
        record(sys.argv[2], float(sys.argv[3]))
    else:
        print(__doc__)
        sys.exit(2)
