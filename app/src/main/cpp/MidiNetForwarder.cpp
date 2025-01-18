#include <jni.h>
#include <unistd.h>
#include <pthread.h>
#include <android/log.h>

#include <amidi/AMidi.h>
#include <string>
#include <arpa/inet.h>

#include "libs/NetUMP/UMP_Transcoder.h"
#include "libs/NetUMP/NetUMP.h"

#define LTAG "Nakama-JNI"
#define MAX_BYTES_TO_RECEIVE 128
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LTAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LTAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_INFO, LTAG, __VA_ARGS__)

static AMidiOutputPort *sMidiOutputPort = nullptr;
static AMidiInputPort *sMidiInputPort = nullptr;
static AMidiDevice *sNativeMidiDevice = nullptr;
static pthread_t sReadFromMIDIThread;
static pthread_t sReadFromNetworkThread;

static CNetUMPHandler *sNetUMPHandler = nullptr;
static uint32_t sUMPMsg[4];

static std::atomic<bool> sMIDIReading(false);
static std::atomic<bool> sNetworkReading(false);

static unsigned int sUMPSize [16] = {1, 1, 1, 2, 2, 4, 1, 1, 2, 2, 2, 3, 3, 4, 4, 4};

static void
onUMPMessage(void *, uint32_t *data) {
    unsigned int MTSize;

    // Process Endpoint related UMP messages
    if ((data[0] & 0xFFFF0000) == 0xF0000000) {
        LOGI("UMP Endpoint related UMP message");
        return;
    }

    // Push UMP message into AMidi
    MTSize = sUMPSize[data[0] >> 28];
    LOGI("UMP Message arrived, size: %d", MTSize);
    int sent = AMidiInputPort_send(sMidiInputPort, (uint8_t*)data, MTSize * 4);
    if (sent < MTSize)
        LOGW("Could not send message, retval: %d", sent);
}

static void*
readFromMIDILoop(void *) {
    AMidiOutputPort* outputPort = sMidiOutputPort;
    uint8_t incomingMessage[MAX_BYTES_TO_RECEIVE];
    int32_t opcode;
    size_t numBytesReceived;
    int64_t timestamp;

    LOGI("--- Reading from MIDI thread started");
    sMIDIReading = true;
    while (sMIDIReading) {
        ssize_t numMessagesReceived = AMidiOutputPort_receive(
            outputPort, &opcode, incomingMessage, MAX_BYTES_TO_RECEIVE,
            &numBytesReceived, &timestamp);

        if (numMessagesReceived < 0) {
            LOGW("Failure receiving MIDI data, error: %zd", numMessagesReceived);
            sMIDIReading = false;
        }
        if (numMessagesReceived > 0) {
            if (opcode == AMIDI_OPCODE_DATA) {
                LOGI("MIDI received, numMsg: %zd, numBytes: %zd",
                     numMessagesReceived, numBytesReceived);

                if (sNetUMPHandler) {
                    if (TranscodeMIDI1_UMP(incomingMessage, numBytesReceived, sUMPMsg)) {
                        sNetUMPHandler->SendUMPMessage(sUMPMsg);
                    }
                }
            }
        }

        usleep(1000);
    }

    LOGI("--- Reading from MIDI thread finished");
    return nullptr;
}

static void*
readFromNetworkLoop(void *) {
    if (sNetUMPHandler == nullptr) {
        LOGE("NetUMP instance is not ready!");
        return nullptr;
    }

    LOGI("--- Reading from network thread started");
    sNetworkReading = true;
    while (sNetworkReading) {
        if (sNetUMPHandler)
            sNetUMPHandler->RunSession();
        usleep(1000);
    }

    LOGI("--- Reading from network thread finished");
    return nullptr;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_dev_sevenfgames_nakama_MainActivity_00024Companion_isRunning(
        JNIEnv *, jobject) {
    return sMIDIReading && sNetworkReading;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_sevenfgames_nakama_MainActivity_startProcessingMidi(
        JNIEnv *env, jobject, jobject midiDeviceObj,
        jint outPort, jint inPort, jstring destHost, jint destPort, jstring endpointName) {

    LOGI("startProcessingMidi called");
    if (sMIDIReading || sNetworkReading) {
        LOGE("Cannot start, threads are already running");
        return;
    }

    AMidiDevice_fromJava(env, midiDeviceObj, &sNativeMidiDevice);

    // this destPort -> NET
    int32_t result = AMidiOutputPort_open(sNativeMidiDevice, outPort, &sMidiOutputPort);
    if (result != AMEDIA_OK) {
        LOGE("Could not open output destPort %d, error: %d", outPort, result);
        return;
    }

    // NET -> this destPort
    result = AMidiInputPort_open(sNativeMidiDevice, inPort, &sMidiInputPort);
    if (result != AMEDIA_OK) {
        LOGE("Could not open input destPort %d, error: %d", inPort, result);
        return;
    }

    // Create NetUMP instance and setup it
    sNetUMPHandler = new CNetUMPHandler(&onUMPMessage, nullptr);
    if (sNetUMPHandler == nullptr) {
        LOGE("Could not create NetUMP instance!");
        return;
    }

    sNetUMPHandler->SetProductInstanceID((char*)"ZNK_001");

    const char *endpointNameChar = env->GetStringUTFChars(endpointName, nullptr);
    sNetUMPHandler->SetEndpointName((char*)endpointNameChar);
    env->ReleaseStringUTFChars(endpointName, endpointNameChar);

    const char *destHostChar = env->GetStringUTFChars(destHost, nullptr);
    int32_t destIP = ntohl(inet_addr(destHostChar));
    env->ReleaseStringUTFChars(destHost, destHostChar);

    result = sNetUMPHandler->InitiateSession(destIP, destPort, 5504, true);
    if (result < 0) {
        LOGE("Could not initiate NetUMP session, error: %d", result);
        delete sNetUMPHandler;
        sNetUMPHandler = nullptr;
        return;
    }

    pthread_create(&sReadFromMIDIThread, nullptr, readFromMIDILoop, nullptr);
    pthread_create(&sReadFromNetworkThread, nullptr, readFromNetworkLoop, nullptr);
}

extern "C"
JNIEXPORT void JNICALL
Java_dev_sevenfgames_nakama_MainActivity_stopProcessingMidi(
        JNIEnv *,  jobject) {

    if (!sMIDIReading || !sNetworkReading) {
        LOGE("Cannot stop, threads are not running");
        return;
    }

    sNetUMPHandler->CloseSession();

    sNetworkReading = false;
    sMIDIReading = false;
    pthread_join(sReadFromMIDIThread, nullptr);
    pthread_join(sReadFromNetworkThread, nullptr);

    if (sMidiInputPort != nullptr) {
        AMidiInputPort_close(sMidiInputPort);
        sMidiInputPort = nullptr;
    }
    if (sMidiOutputPort != nullptr) {
        AMidiOutputPort_close(sMidiOutputPort);
        sMidiOutputPort = nullptr;
    }
    if (sNativeMidiDevice != nullptr) {
        AMidiDevice_release(sNativeMidiDevice);
        sNativeMidiDevice = nullptr;
    }
    if (sNetUMPHandler != nullptr) {
        delete sNetUMPHandler;
        sNetUMPHandler = nullptr;
    }

    LOGI("All threads stopped and MIDI device released");
}
