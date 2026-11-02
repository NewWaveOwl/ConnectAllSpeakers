"""Create quiet, deterministic WAV fixtures under build/manual-tests; never play audio."""

import argparse
import math
from pathlib import Path
import struct
import wave


def write_wave(path: Path, rate: int, channels: int, seconds: int) -> None:
    """Write PCM16 with a -20 dBFS peak and 10 ms ramps at each tone boundary."""
    with wave.open(str(path), "wb") as output:
        output.setnchannels(channels)
        output.setsampwidth(2)
        output.setframerate(rate)
        for second in range(seconds):
            samples = bytearray()
            for offset in range(rate):
                time = second + offset / rate
                cycle = time % 12
                left_on = cycle < 2 or 8 <= cycle < 10
                right_on = 4 <= cycle < 6 or 8 <= cycle < 10
                local = cycle % 2
                ramp = min(1.0, local / .01, (2 - local) / .01)
                left = .1 * ramp * math.sin(2 * math.pi * 440 * time) if left_on else 0
                right = .1 * ramp * math.sin(2 * math.pi * 880 * time) if right_on else 0
                if channels == 1:
                    samples.extend(struct.pack("<h", round(left * 32767)))
                else:
                    samples.extend(struct.pack("<hh", round(left * 32767), round(right * 32767)))
            output.writeframes(samples)


def main() -> None:
    """Generate fixtures only; the user chooses when and where to listen."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--long", action="store_true", help="Also create a 180-second timing-observation file")
    args = parser.parse_args()
    destination = Path(__file__).resolve().parents[1] / "build" / "manual-tests"
    destination.mkdir(parents=True, exist_ok=True)
    for name, rate, channels in [
        ("stereo-identification-48000.wav", 48000, 2),
        ("stereo-identification-44100.wav", 44100, 2),
        ("mono-identification-48000.wav", 48000, 1),
    ]:
        write_wave(destination / name, rate, channels, 12)
        print(destination / name)
    (destination / "invalid-header.wav").write_bytes(b"This deliberately is not a WAV file.\n")
    if args.long:
        write_wave(destination / "stereo-timing-180s.wav", 48000, 2, 180)
        print(destination / "stereo-timing-180s.wav")
    print("Generated files only. No audio device was opened and nothing was played.")


if __name__ == "__main__":
    main()
