// AllConnect endpoint assembly, using Microsoft SYSVAD miniports (MS-PL).
#pragma once
#include "AllConnectWave.h"
#include "AllConnectTopology.h"

NTSTATUS CreateMiniportWaveRTSYSVAD(_Out_ PUNKNOWN*, _In_ REFCLSID, _In_opt_ PUNKNOWN,
    _In_ POOL_FLAGS, _In_ PUNKNOWN, _In_opt_ PVOID, _In_ PENDPOINT_MINIPAIR);
NTSTATUS CreateMiniportTopologySYSVAD(_Out_ PUNKNOWN*, _In_ REFCLSID, _In_opt_ PUNKNOWN,
    _In_ POOL_FLAGS, _In_ PUNKNOWN, _In_opt_ PVOID, _In_ PENDPOINT_MINIPAIR);

static PHYSICALCONNECTIONTABLE AllConnectPhysicalConnections[] = {
    { KSPIN_TOPO_WAVEOUT_SOURCE, KSPIN_WAVE_RENDER3_SOURCE, CONNECTIONTYPE_WAVE_OUTPUT }
};
static ENDPOINT_MINIPAIR AllConnectMiniports = {
    eSpeakerDevice,
    L"TopologyAllConnect", NULL, CreateMiniportTopologySYSVAD, &AllConnectTopoDescriptor, 0, NULL,
    L"WaveAllConnect", NULL, CreateMiniportWaveRTSYSVAD, &AllConnectWaveDescriptor, 0, NULL,
    2, AllConnectPinFormats, ARRAYSIZE(AllConnectPinFormats),
    AllConnectPhysicalConnections, ARRAYSIZE(AllConnectPhysicalConnections),
    ENDPOINT_NO_FLAGS, NULL, 0, NULL
};
static PENDPOINT_MINIPAIR g_RenderEndpoints[] = { &AllConnectMiniports };
// The upstream adapter expects a pointer even when there are zero capture pairs.
static PENDPOINT_MINIPAIR* g_CaptureEndpoints = NULL;
#define g_cRenderEndpoints ARRAYSIZE(g_RenderEndpoints)
static ULONG g_cCaptureEndpoints = 0;
#define g_MaxMiniports 2
