// User-mode activation bridge. Capture, rendering and COM lifetime stay on the app's audio worker.
#include <windows.h>
#include <audioclient.h>
#include <audioclientactivationparams.h>
#include <mmdeviceapi.h>
#include <wrl.h>
#include <new>
using namespace Microsoft::WRL;

class Activation final : public RuntimeClass<RuntimeClassFlags<ClassicCom>, FtmBase,
    IActivateAudioInterfaceCompletionHandler> {
public:
    HANDLE completed = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    AUDIOCLIENT_ACTIVATION_PARAMS parameters{};
    PROPVARIANT variant{};
    HRESULT result = E_PENDING;
    ComPtr<IUnknown> activated;
    ~Activation() { if (completed) CloseHandle(completed); }
    STDMETHOD(ActivateCompleted)(IActivateAudioInterfaceAsyncOperation* operation) override {
        HRESULT activationResult = E_FAIL;
        result = operation->GetActivateResult(&activationResult, &activated);
        if (SUCCEEDED(result)) result = activationResult;
        SetEvent(completed);
        return S_OK;
    }
};

extern "C" __declspec(dllexport) HRESULT ac_activate(DWORD pid, INT64 startEpochMillis, IAudioClient** client) {
    if (!client) return E_POINTER;
    *client = nullptr;
    if (!pid || pid == GetCurrentProcessId()) return E_INVALIDARG;
    HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION | SYNCHRONIZE, FALSE, pid);
    if (!process) return HRESULT_FROM_WIN32(GetLastError());
    FILETIME creation{}, exit{}, kernel{}, user{};
    const BOOL times = GetProcessTimes(process, &creation, &exit, &kernel, &user);
    ULARGE_INTEGER created{}; created.LowPart = creation.dwLowDateTime; created.HighPart = creation.dwHighDateTime;
    const bool matches = times && static_cast<INT64>(created.QuadPart / 10000ULL - 11644473600000ULL) == startEpochMillis
        && WaitForSingleObject(process, 0) == WAIT_TIMEOUT;
    if (!matches) { CloseHandle(process); return HRESULT_FROM_WIN32(ERROR_NOT_FOUND); }
    auto activation = Make<Activation>();
    if (!activation || !activation->completed) { CloseHandle(process); return E_OUTOFMEMORY; }
    activation->parameters.ActivationType = AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK;
    activation->parameters.ProcessLoopbackParams.TargetProcessId = pid;
    activation->parameters.ProcessLoopbackParams.ProcessLoopbackMode = PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE;
    activation->variant.vt = VT_BLOB;
    activation->variant.blob.cbSize = sizeof(activation->parameters);
    activation->variant.blob.pBlobData = reinterpret_cast<BYTE*>(&activation->parameters);
    ComPtr<IActivateAudioInterfaceAsyncOperation> operation;
    HRESULT hr = ActivateAudioInterfaceAsync(VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK, __uuidof(IAudioClient),
        &activation->variant, activation.Get(), &operation);
    if (SUCCEEDED(hr)) {
        HANDLE events[] = { activation->completed, process };
        DWORD wait = WaitForMultipleObjects(2, events, FALSE, 5000);
        if (wait == WAIT_OBJECT_0) {
            hr = activation->result;
            if (SUCCEEDED(hr)) hr = activation->activated.CopyTo(client);
        } else hr = HRESULT_FROM_WIN32(wait == WAIT_TIMEOUT ? ERROR_TIMEOUT : ERROR_PROCESS_ABORTED);
    }
    CloseHandle(process);
    return hr;
}
