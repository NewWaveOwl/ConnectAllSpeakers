"""Regenerate original short MP3 unit-test signals; never open audio devices.

Requires development-only lameenc==1.9.0. The application neither bundles nor
invokes this encoder. Fixture signals were authored for this repository.
"""

import argparse
import math
from pathlib import Path
import struct
import sys


def main() -> None:
    """Write deterministic CBR/VBR MPEG-1/2/2.5 fixtures for offline decoding tests."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--encoder-directory", type=Path, help="Directory containing development-only lameenc")
    args = parser.parse_args()
    if args.encoder_directory:
        sys.path.insert(0, str(args.encoder_directory.resolve()))
    import lameenc

    root = Path(__file__).resolve().parents[1]
    destination = root / "desktopApp/src/test/resources/audio"
    destination.mkdir(parents=True, exist_ok=True)
    for name, rate, channels, vbr in [
        ("stereo-cbr-44100.mp3", 44100, 2, False),
        ("stereo-vbr-48000.mp3", 48000, 2, True),
        ("mono-cbr-22050.mp3", 22050, 1, False),
        ("mono-cbr-8000.mp3", 8000, 1, False),
    ]:
        pcm = bytearray()
        for frame in range(rate):
            time = frame / rate
            # 0-.3 s left/mono; .3-.6 s right/mono; .6-1 s both.
            # Sufficient distance from boundaries lets tests tolerate encoder delay.
            local = time % .3
            ramp = min(1.0, local / .01, (.3 - local) / .01)
            left = .1 * ramp * math.sin(2 * math.pi * 440 * time) if time < .3 or time >= .6 else 0
            right = .1 * ramp * math.sin(2 * math.pi * 880 * time) if time >= .3 else 0
            if channels == 1:
                pcm.extend(struct.pack("<h", round((left + right) * .5 * 32767)))
            else:
                pcm.extend(struct.pack("<hh", round(left * 32767), round(right * 32767)))
        encoder = lameenc.Encoder()
        encoder.silence()
        encoder.set_in_sample_rate(rate)
        encoder.set_out_sample_rate(rate)
        encoder.set_channels(channels)
        encoder.set_bit_rate(128 if channels == 2 else 32)
        encoder.set_quality(2)
        if vbr:
            encoder.set_vbr(lameenc.VBR_MTRH)
            encoder.set_vbr_quality(4)
        path = destination / name
        path.write_bytes(encoder.encode(bytes(pcm)) + encoder.flush())
        print(path.name, path.stat().st_size, "bytes")
    print("Encoded fixture bytes only; nothing was played.")


if __name__ == "__main__":
    main()
