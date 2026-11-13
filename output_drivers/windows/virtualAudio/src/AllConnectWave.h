// AllConnect render-only WaveRT descriptor. See vendor/sysvad/LICENSE (MS-PL)
// for the Microsoft miniport implementation used by this descriptor.
#pragma once

// One shared-mode PCM wire format. The Windows engine may expose float32 mix
// to WASAPI loopback; the app validates the actual negotiated format separately.
static KSDATAFORMAT_WAVEFORMATEXTENSIBLE AllConnectFormats[] = {
    {
        { sizeof(KSDATAFORMAT_WAVEFORMATEXTENSIBLE), 0, 0, 0,
          STATICGUIDOF(KSDATAFORMAT_TYPE_AUDIO), STATICGUIDOF(KSDATAFORMAT_SUBTYPE_PCM),
          STATICGUIDOF(KSDATAFORMAT_SPECIFIER_WAVEFORMATEX) },
        { { WAVE_FORMAT_EXTENSIBLE, 2, 48000, 192000, 4, 16,
            sizeof(WAVEFORMATEXTENSIBLE) - sizeof(WAVEFORMATEX) },
          16, KSAUDIO_SPEAKER_STEREO, STATICGUIDOF(KSDATAFORMAT_SUBTYPE_PCM) }
    }
};
static MODE_AND_DEFAULT_FORMAT AllConnectModes[] = {
    { STATIC_AUDIO_SIGNALPROCESSINGMODE_DEFAULT, &AllConnectFormats[0].DataFormat },
    { STATIC_AUDIO_SIGNALPROCESSINGMODE_RAW, &AllConnectFormats[0].DataFormat }
};
static PIN_DEVICE_FORMATS_AND_MODES AllConnectPinFormats[] = {
    { SystemRenderPin, AllConnectFormats, ARRAYSIZE(AllConnectFormats), AllConnectModes, ARRAYSIZE(AllConnectModes) },
    { BridgePin, NULL, 0, NULL, 0 }
};
static KSDATARANGE_AUDIO AllConnectStreamRange = {
    { sizeof(KSDATARANGE_AUDIO), KSDATARANGE_ATTRIBUTES, 0, 0,
      STATICGUIDOF(KSDATAFORMAT_TYPE_AUDIO), STATICGUIDOF(KSDATAFORMAT_SUBTYPE_PCM),
      STATICGUIDOF(KSDATAFORMAT_SPECIFIER_WAVEFORMATEX) },
    2, 16, 16, 48000, 48000
};
static PKSDATARANGE AllConnectStreamRanges[] = {
    PKSDATARANGE(&AllConnectStreamRange), PKSDATARANGE(&PinDataRangeAttributeList)
};
static KSDATARANGE AllConnectBridgeRange = {
    sizeof(KSDATARANGE), 0, 0, 0, STATICGUIDOF(KSDATAFORMAT_TYPE_AUDIO),
    STATICGUIDOF(KSDATAFORMAT_SUBTYPE_ANALOG), STATICGUIDOF(KSDATAFORMAT_SPECIFIER_NONE)
};
static PKSDATARANGE AllConnectBridgeRanges[] = { &AllConnectBridgeRange };
static PCPIN_DESCRIPTOR AllConnectWavePins[] = {
    { 1, 1, 0, NULL,
      { 0, NULL, 0, NULL, ARRAYSIZE(AllConnectStreamRanges), AllConnectStreamRanges,
        KSPIN_DATAFLOW_IN, KSPIN_COMMUNICATION_SINK, &KSCATEGORY_AUDIO, NULL, 0 } },
    { 0, 0, 0, NULL,
      { 0, NULL, 0, NULL, ARRAYSIZE(AllConnectBridgeRanges), AllConnectBridgeRanges,
        KSPIN_DATAFLOW_OUT, KSPIN_COMMUNICATION_NONE, &KSCATEGORY_AUDIO, NULL, 0 } }
};
// Deliberately no hardware loopback pin, offload pin, audio-engine node or
// capture endpoint. Windows performs software engine loopback of the real mix;
// SYSVAD's generated-sine capture code cannot supply this endpoint's input.
static PCCONNECTION_DESCRIPTOR AllConnectWaveConnections[] = {
    { PCFILTER_NODE, KSPIN_WAVE_RENDER3_SINK_SYSTEM, PCFILTER_NODE, KSPIN_WAVE_RENDER3_SOURCE }
};
static PCPROPERTY_ITEM AllConnectFilterProperties[] = {
    { &KSPROPSETID_Pin, KSPROPERTY_PIN_PROPOSEDATAFORMAT,
      KSPROPERTY_TYPE_SET | KSPROPERTY_TYPE_BASICSUPPORT, PropertyHandler_WaveFilter },
    { &KSPROPSETID_Pin, KSPROPERTY_PIN_PROPOSEDATAFORMAT2,
      KSPROPERTY_TYPE_GET | KSPROPERTY_TYPE_BASICSUPPORT, PropertyHandler_WaveFilter }
};
DEFINE_PCAUTOMATION_TABLE_PROP(AllConnectFilterAutomation, AllConnectFilterProperties);
static PCFILTER_DESCRIPTOR AllConnectWaveDescriptor = {
    0, &AllConnectFilterAutomation, sizeof(PCPIN_DESCRIPTOR), ARRAYSIZE(AllConnectWavePins), AllConnectWavePins,
    sizeof(PCNODE_DESCRIPTOR), 0, NULL, ARRAYSIZE(AllConnectWaveConnections), AllConnectWaveConnections, 0, NULL
};
