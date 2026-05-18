#include <android/log.h>
#include <uchar.h>
#include <android/binder_ibinder.h>
#include <android/binder_ibinder_jni.h>
#include <android/binder_parcel.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

#define LOG_TAG "SketchfabFpsControlJni"
#define SURFACE_FLINGER_DESCRIPTOR "android.ui.ISurfaceComposer"
#define TRANSACTION_FPS_CONTROL 80

static AIBinder_Class* g_surface_flinger_class = NULL;

static void* noop_on_create(void* args) {
    return args;
}

static void noop_on_destroy(void* user_data) {
    (void)user_data;
}

static binder_status_t noop_on_transact(
        AIBinder* binder,
        transaction_code_t code,
        const AParcel* in,
        AParcel* out) {
    (void)binder;
    (void)code;
    (void)in;
    (void)out;
    return STATUS_UNKNOWN_TRANSACTION;
}

JNIEXPORT jstring JNICALL
Java_com_redmagic_sketchfabsbs_NativeFpsControl_fpsControlBinderNative(
        JNIEnv* env,
        jclass clazz,
        jobject java_binder,
        jstring name,
        jint code,
        jint value) {
    (void)clazz;

    const char* package_name = (*env)->GetStringUTFChars(env, name, 0);
    if (!package_name) {
        return (*env)->NewStringUTF(env, "GetStringUTFChars failed");
    }

    char result[256];
    AIBinder* binder = AIBinder_fromJavaBinder(env, java_binder);
    if (!binder) {
        (*env)->ReleaseStringUTFChars(env, name, package_name);
        return (*env)->NewStringUTF(env, "AIBinder_fromJavaBinder failed");
    }

    if (!g_surface_flinger_class) {
        g_surface_flinger_class = AIBinder_Class_define(
                SURFACE_FLINGER_DESCRIPTOR,
                noop_on_create,
                noop_on_destroy,
                noop_on_transact);
    }
    if (!g_surface_flinger_class) {
        AIBinder_decStrong(binder);
        (*env)->ReleaseStringUTFChars(env, name, package_name);
        return (*env)->NewStringUTF(env, "AIBinder_Class_define failed");
    }

    if (!AIBinder_associateClass(binder, g_surface_flinger_class)) {
        AIBinder_decStrong(binder);
        (*env)->ReleaseStringUTFChars(env, name, package_name);
        return (*env)->NewStringUTF(env, "AIBinder_associateClass failed");
    }

    AParcel* in = NULL;
    binder_status_t status = AIBinder_prepareTransaction(binder, &in);
    if (status != STATUS_OK || !in) {
        snprintf(result, sizeof(result), "AIBinder_prepareTransaction failed status=%d", status);
        AIBinder_decStrong(binder);
        (*env)->ReleaseStringUTFChars(env, name, package_name);
        return (*env)->NewStringUTF(env, result);
    }

    status = AParcel_writeInt32(in, code);
    if (status == STATUS_OK) {
        status = AParcel_writeInt32(in, value);
    }
    if (status == STATUS_OK) {
        status = AParcel_writeString(in, package_name, (int32_t)strlen(package_name));
    }
    if (status != STATUS_OK) {
        snprintf(result, sizeof(result), "AParcel write failed status=%d", status);
        AParcel_delete(in);
        AIBinder_decStrong(binder);
        (*env)->ReleaseStringUTFChars(env, name, package_name);
        return (*env)->NewStringUTF(env, result);
    }

    AParcel* out = NULL;
    status = AIBinder_transact(binder, TRANSACTION_FPS_CONTROL, &in, &out, 0);
    if (out) {
        AParcel_delete(out);
    }
    AIBinder_decStrong(binder);

    if (status != STATUS_OK) {
        snprintf(result, sizeof(result), "AIBinder_transact failed status=%d", status);
        (*env)->ReleaseStringUTFChars(env, name, package_name);
        return (*env)->NewStringUTF(env, result);
    }

    snprintf(result, sizeof(result), "AIBinder fpsControl(%s,%d,%d) called", package_name, code, value);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "%s", result);
    (*env)->ReleaseStringUTFChars(env, name, package_name);
    return (*env)->NewStringUTF(env, result);
}

