#!/usr/bin/env python3
"""
HandArm PC test sender - drives the ESP32 WITHOUT the phone/camera, using the same wire format as the app.
Use it to separate "ESP32 + servos + wiring" problems from "phone / camera / tracking" problems.

Setup (pair the ESP32 "HandArm" with this computer first, then find its serial port):
  Windows : Settings > Bluetooth > More Bluetooth options > COM Ports > the OUTGOING port for HandArm (e.g. COM7)
  macOS   : /dev/cu.HandArm
  Linux   : sudo rfcomm bind /dev/rfcomm0 <ESP32-MAC>      then use /dev/rfcomm0

Install : pip install pyserial
Examples:
  python send_test.py --port COM7 --pattern wave
  python send_test.py --port /dev/rfcomm0 --pattern sweep --channel 7     # only the thumb swing
  python send_test.py --dry-run --pattern fist --seconds 2                 # print packets, no hardware

Patterns: wave (all fingers ripple + wrist moves), fist (open/close together), sweep (one channel at a time).
Ctrl-C returns the arm to neutral. Values are 0..180: rot, lift, pinky, ring, middle, index, thumbCurl, thumbSwing.
(--channel numbers are these 8 FIELDS: 0 rot, 1 lift, 2 pinky, 3 ring, 4 middle, 5 index, 6 thumbCurl, 7 thumbSwing -
 not the servo channels; pinky and ring both drive the one shared motor.)
"""
import argparse
import math
import sys
import time

NAMES = ["rot", "lift", "pinky", "ring", "middle", "index", "thumbCurl", "thumbSwing"]
NEUTRAL = [90, 90, 0, 0, 0, 0, 0, 0]


def encode(v):
    """Identical to the app's Protocol.encode: $H,<7 ints>*<XOR hex>\\n"""
    body = "H," + ",".join(str(int(max(0, min(180, round(x))))) for x in v)
    cs = 0
    for ch in body:
        cs ^= ord(ch)
    return ("$%s*%02X\n" % (body, cs)).encode("ascii")


def pattern_values(name, t, channel):
    v = list(NEUTRAL)
    if name == "wave":
        v[0] = 90 + 45 * math.sin(t * 0.8)                       # wrist rotation
        v[1] = 90 + 45 * math.cos(t * 0.6)                       # wrist lift
        for i in range(6):                                       # fingers + thumb ripple
            v[2 + i] = 90 + 90 * math.sin(t * 1.5 - i * 0.7)
    elif name == "fist":
        c = 90 + 90 * math.sin(t * 1.2)
        for i in range(6):
            v[2 + i] = c
    elif name == "sweep":
        ch = channel if channel is not None else int(t // 4) % 8   # 4 s per field
        ph = (t % 4) / 4
        tri = ph * 2 if ph < 0.5 else (1 - ph) * 2
        v[ch] = 180 * tri
    return v


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port")
    ap.add_argument("--pattern", choices=["wave", "fist", "sweep"], default="wave")
    ap.add_argument("--channel", type=int, choices=range(8), help="sweep only this field (0 rot,1 lift,2 pinky,3 ring,4 middle,5 index,6 thumbCurl,7 thumbSwing)")
    ap.add_argument("--rate", type=float, default=30.0, help="packets per second (default 30)")
    ap.add_argument("--seconds", type=float, default=0, help="stop after N seconds (default: run until Ctrl-C)")
    ap.add_argument("--dry-run", action="store_true", help="print packets instead of sending")
    a = ap.parse_args()

    ser = None
    if not a.dry_run:
        if not a.port:
            sys.exit("--port is required unless --dry-run")
        import serial  # pip install pyserial
        ser = serial.Serial(a.port, 115200, timeout=0.1, write_timeout=1.0)
        print("opened", a.port)

    def emit(v):
        pkt = encode(v)
        if ser:
            ser.write(pkt)
        else:
            sys.stdout.write(",".join(str(int(round(x))) for x in v) + "\t" + pkt.decode())

    period = 1.0 / a.rate
    t0 = time.time()
    nxt = t0
    try:
        while a.seconds <= 0 or time.time() - t0 < a.seconds:
            emit(pattern_values(a.pattern, time.time() - t0, a.channel))
            nxt += period
            time.sleep(max(0.0, nxt - time.time()))
    except KeyboardInterrupt:
        pass
    finally:
        for _ in range(10):                                      # leave the arm at neutral
            emit(NEUTRAL)
            time.sleep(period)
        if ser:
            ser.close()
        print("done", file=sys.stderr)


if __name__ == "__main__":
    main()
