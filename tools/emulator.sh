#!/usr/bin/env bash
# Wear OS emulator shaped like a Galaxy Watch6 (round 432 × 432 px, 340 dpi, Wear OS 6), with a
# silent audio loop: speech goes into the watch's microphone and Talova's voice is recorded, both over the
# emulator's own gRPC bridge (tools/emu_audio.py), so a whole conversation can be tested without a sound in
# the room and without touching the desktop's audio devices.
#
#   tools/emulator.sh start [--headless|--no-audio]   create the AVD if needed and boot it, in a window by default
#                                      (--no-audio for UI tours: stable, but nothing to speak into)
#   tools/emulator.sh video <out.mp4> <seconds>   record the screen over gRPC (screenrecord crashes it)
#   tools/emulator.sh install          install the debug build and grant its permissions
#   tools/emulator.sh say "<text>" [voice]   speak into the watch (espeak-ng, default voice en-us)
#   tools/emulator.sh play <file.wav>  play a recording into the watch
#   tools/emulator.sh record <out.wav> <seconds>   record what the watch says
#   tools/emulator.sh shot <out.png>   screenshot
#   tools/emulator.sh log              follow Talova's conversation log (TalovaEvent, TalovaPair)
#   tools/emulator.sh stop             shut the emulator down
#   tools/emulator.sh remove           stop, then delete the AVD, the system image, the emulator and the venv
#
# emu_audio.py needs a venv with grpcio and stubs generated from emulator/lib/emulator_controller.proto in
# ~/.cache/talova-emulator-venv; `start` creates it the first time.
set -euo pipefail

SDK=${ANDROID_SDK_ROOT:-$HOME/.android/SDK}
ADB=$SDK/platform-tools/adb
AVD=Talova_Watch6
IMAGE="system-images;android-36;android-wear-signed;x86_64"
PORT=5560
SERIAL=emulator-$PORT
GRPC_PORT=8554
VENV=$HOME/.cache/talova-emulator-venv
TOOLS=$(cd "$(dirname "$0")" && pwd)
STATE=${XDG_RUNTIME_DIR:-/tmp}/talova-emulator
APK=$(cd "$(dirname "$0")/.." && pwd)/wear/build/outputs/apk/debug/wear-debug.apk
PKG=net.devemperor.talova.debug
mkdir -p "$STATE"

venv_up() {
  [ -f "$VENV/stubs/emulator_controller_pb2_grpc.py" ] && return
  python3 -m venv "$VENV"
  "$VENV/bin/pip" install -q grpcio grpcio-tools
  mkdir -p "$VENV/stubs"
  "$VENV/bin/python" -m grpc_tools.protoc -I "$SDK/emulator/lib" --python_out="$VENV/stubs" \
    --grpc_python_out="$VENV/stubs" "$SDK/emulator/lib/emulator_controller.proto"
}

boot_wait() {
  "$ADB" -s "$SERIAL" wait-for-device
  until [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
    sleep 2
  done
}

case "${1:-}" in
  start)
    if [ ! -d "$HOME/.android/avd/$AVD.avd" ]; then
      echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n "$AVD" -k "$IMAGE" -d wearos_large_round --force
      CONFIG=$HOME/.android/avd/$AVD.avd/config.ini
      # Galaxy Watch6 (40 mm): 432 × 432 px at 340 dpi.
      sed -i -e 's/^hw.lcd.width=.*/hw.lcd.width=432/' -e 's/^hw.lcd.height=.*/hw.lcd.height=432/' \
             -e 's/^hw.lcd.density=.*/hw.lcd.density=340/' -e 's/^hw.ramSize=.*/hw.ramSize=2048/' \
             -e 's/^hw.lcd.circular=.*/hw.lcd.circular=true/' "$CONFIG"
    fi
    venv_up
    # `-grpc <port>` opens the bridge on localhost without a token; tools/emu_audio.py speaks into the
    # microphone and records through it. The host microphone stays off (no -allow-host-audio).
    if [ "${2:-}" = --no-audio ]; then
      # No sound device in the guest at all: conversations then run without crashing the emulator
      # (its audio capture crashed it every few starts), with no way to speak into the watch.
      QEMU_AUDIO_DRV=none setsid nohup "$SDK/emulator/emulator" -avd "$AVD" -port "$PORT" -grpc "$GRPC_PORT" \
        -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048 -no-audio > "$STATE/emulator.log" 2>&1 &
    elif [ "${2:-}" = --headless ]; then
      # Headless qemu: no window at all, and no host audio driver either.
      setsid nohup "$SDK/emulator/emulator" -avd "$AVD" -port "$PORT" -grpc "$GRPC_PORT" -no-window -no-boot-anim \
        -no-snapshot -gpu swiftshader_indirect -memory 2048 > "$STATE/emulator.log" 2>&1 &
    else
      # In a window the emulator would open the desktop's sound devices. With the host audio driver
      # set to none it touches no desktop audio at all; gRPC still carries both directions.
      QEMU_AUDIO_DRV=none setsid nohup "$SDK/emulator/emulator" -avd "$AVD" -port "$PORT" -grpc "$GRPC_PORT" \
        -no-boot-anim -no-snapshot -gpu swiftshader_indirect -memory 2048 > "$STATE/emulator.log" 2>&1 &
    fi
    # setsid: a `timeout` wrapped around this script or a test kills its whole process group, and
    # the emulator has to outlive both.
    echo $! > "$STATE/emulator.pid"
    boot_wait
    "$ADB" -s "$SERIAL" shell dumpsys battery unplug   # otherwise the charging screen covers everything
    "$ADB" -s "$SERIAL" shell settings put system screen_off_timeout 600000
    "$ADB" -s "$SERIAL" shell svc power stayon true   # no ambient mode, where taps would not land
    echo "booted $SERIAL"
    ;;
  install)
    "$ADB" -s "$SERIAL" install -r "$APK"
    "$ADB" -s "$SERIAL" shell pm grant "$PKG" android.permission.RECORD_AUDIO
    "$ADB" -s "$SERIAL" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
    ;;
  say)
    wav=$STATE/say.wav
    espeak-ng -v "${3:-en-us}" -s 150 -w "$wav" "$2"
    "$VENV/bin/python" "$TOOLS/emu_audio.py" inject "$wav"
    ;;
  play)
    "$VENV/bin/python" "$TOOLS/emu_audio.py" inject "$2"
    ;;
  record)
    "$VENV/bin/python" "$TOOLS/emu_audio.py" record "$2" "${3:-15}"
    ;;
  shot)
    "$ADB" -s "$SERIAL" exec-out screencap -p > "$2"
    ;;
  video)
    "$VENV/bin/python" "$TOOLS/emu_video.py" "$2" "${3:-10}"
    ;;
  log)
    "$ADB" -s "$SERIAL" logcat -s TalovaEvent:V TalovaPair:V AndroidRuntime:E
    ;;
  stop)
    "$ADB" -s "$SERIAL" emu kill > /dev/null 2>&1 || true
    [ -s "$STATE/emulator.pid" ] && { kill "$(cat "$STATE/emulator.pid")" 2>/dev/null || true; rm -f "$STATE/emulator.pid"; }
    echo "stopped"
    ;;
  remove)
    "$0" stop
    "$SDK/cmdline-tools/latest/bin/avdmanager" delete avd -n "$AVD" || true
    "$SDK/cmdline-tools/latest/bin/sdkmanager" --uninstall "$IMAGE" "emulator"
    rm -rf "$VENV"
    ;;
  *)
    sed -n '2,20p' "$0"
    exit 2
    ;;
esac
