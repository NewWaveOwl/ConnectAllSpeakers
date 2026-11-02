# Original MP3 decoder fixtures

These short signals were authored for ConnectAllSpeakers and encoded without
playback by `tools/generate_mp3_test_fixtures.py`, using development-only
`lameenc==1.9.0` (LAME encoder). They contain no third-party music or recordings.
The generated fixture files may be used, copied and redistributed with this project.

The one-second source is at 10% peak amplitude with short boundary ramps:
0–0.3 s left/440 Hz, 0.3–0.6 s right/880 Hz, then both. Mono fixtures average
the two signals. MP3 encoder delay and final padding remain in decoded output.

- `stereo-cbr-44100.mp3`: MPEG-1, 44.1 kHz stereo, 128 kbps CBR.
- `stereo-vbr-48000.mp3`: MPEG-1, 48 kHz stereo, VBR quality 4.
- `mono-cbr-22050.mp3`: MPEG-2, 22.05 kHz mono, 32 kbps CBR.
- `mono-cbr-8000.mp3`: MPEG-2.5, 8 kHz mono, 32 kbps CBR.

Regenerate with the development-only encoder installed in the ignored folder:

```powershell
python -m pip install --no-deps --target .local-tmp/mp3-fixture-tools lameenc==1.9.0
python tools/generate_mp3_test_fixtures.py --encoder-directory .local-tmp/mp3-fixture-tools
```

The application does not bundle or invoke this encoder. Tests read/decode fixture
bytes and never open an audio device.
