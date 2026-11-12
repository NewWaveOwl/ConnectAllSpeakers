// Minimal virtual topology: bridge input -> named speaker endpoint, no hardware controls.
#pragma once

// Device-relative MediaCategories registration in the INF gives this pin its
// AllConnect Speakers name without changing the global speaker-category name.
static const GUID AllConnectEndpointName =
    { 0x1f308a3a, 0x61c0, 0x4b57, { 0xb7, 0x2f, 0x62, 0xeb, 0x9d, 0x63, 0xdf, 0x49 } };
static PCPIN_DESCRIPTOR AllConnectTopoPins[] = {
    { 0, 0, 0, NULL,
      { 0, NULL, 0, NULL, ARRAYSIZE(AllConnectBridgeRanges), AllConnectBridgeRanges,
        KSPIN_DATAFLOW_IN, KSPIN_COMMUNICATION_NONE, &KSCATEGORY_AUDIO, NULL, 0 } },
    { 0, 0, 0, NULL,
      { 0, NULL, 0, NULL, ARRAYSIZE(AllConnectBridgeRanges), AllConnectBridgeRanges,
        KSPIN_DATAFLOW_OUT, KSPIN_COMMUNICATION_NONE, &KSNODETYPE_SPEAKER, &AllConnectEndpointName, 0 } }
};
static PCCONNECTION_DESCRIPTOR AllConnectTopoConnections[] = {
    { PCFILTER_NODE, KSPIN_TOPO_WAVEOUT_SOURCE, PCFILTER_NODE, KSPIN_TOPO_LINEOUT_DEST }
};
static PCFILTER_DESCRIPTOR AllConnectTopoDescriptor = {
    0, NULL, sizeof(PCPIN_DESCRIPTOR), ARRAYSIZE(AllConnectTopoPins), AllConnectTopoPins,
    sizeof(PCNODE_DESCRIPTOR), 0, NULL, ARRAYSIZE(AllConnectTopoConnections), AllConnectTopoConnections, 0, NULL
};
