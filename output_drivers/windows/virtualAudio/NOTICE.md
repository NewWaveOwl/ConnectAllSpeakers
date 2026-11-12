# Native source attribution

`vendor/sysvad` contains an unmodified subset of Microsoft's Windows Driver
Samples, revision `f4c1ff842b6a0001152f44cb61788b216a1c7627`, under the
[Microsoft Public License](vendor/sysvad/LICENSE). Copyright and source notices
remain in the files. `upstream.json` records each source's Git blob identity
and SHA-256; `Build.ps1` verifies these before preparing/building.

Source: <https://github.com/microsoft/Windows-driver-samples/tree/f4c1ff842b6a0001152f44cb61788b216a1c7627/audio/sysvad>.

AllConnect supplies its own render-only descriptor, endpoint assembly, hardware
ID, INF, project and build script. Generated upstream modifications
remove the adapter registry setting that could enable sample PCM data files and
retain the ordinary-device branches of eight volume/mute methods whose sideband
references are unguarded upstream when sideband backends are disabled;
the original vendored adapter remains unchanged. No sample capture endpoint,
sample hardware-loopback pin, APO, keyword-detector DLL, Bluetooth bypass or USB
sideband endpoint is registered by this project. Some supporting sample code
remains linked because the reusable miniport classes reference it.

Distribute this notice and the MS-PL license with source/binary packages derived
from these files. No Microsoft endorsement or production-driver certification
is implied. This prototype has been natively built but has not been signed or
deployed on the current development PC; see [build and lab status](README.md).
