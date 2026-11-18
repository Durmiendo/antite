// The ciphers of the key core and the boxes of keys (see memory.c).

#include "keycore.h"

jbyteArray give(JNIEnv *env, const unsigned char *p, size_t n) {
    jbyteArray r;
    if (FAULT()) {
        fail(env, NO_MEMORY, "injected");
        return NULL;
    }
    r = (*env)->NewByteArray(env, (jsize) n);
    if (r != NULL) (*env)->SetByteArrayRegion(env, r, 0, (jsize) n, (const jbyte *) p);
    return r;
}

// AES-256-GCM in place: w holds n bytes; sealing appends the tag, opening takes it off. soft: BearSSL's
// constant-time 64-bit AES and GHASH, for processors without AES instructions.
static int aes(int seal, int soft, const unsigned char *key, const unsigned char *nonce12, const unsigned char *aad,
               size_t aad_n, unsigned char *w, size_t *n) {
    unsigned long long got = 0;

    if (!seal && *n < TAG) return NOT_OPEN;
    if (soft || !crypto_aead_aes256gcm_is_available()) {
        br_aes_ct64_ctr_keys ctr;
        br_gcm_context gcm;
        size_t body = seal ? *n : *n - TAG;
        int ok = 1;

        br_aes_ct64_ctr_init(&ctr, key, 32);
        br_gcm_init(&gcm, &ctr.vtable, br_ghash_ctmul64);
        br_gcm_reset(&gcm, nonce12, 12);
        br_gcm_aad_inject(&gcm, aad, aad_n);
        br_gcm_flip(&gcm);
        br_gcm_run(&gcm, seal, w, body);
        if (seal) br_gcm_get_tag(&gcm, w + body);
        else ok = br_gcm_check_tag(&gcm, w + body) ? 1 : 0;
        sodium_memzero(&ctr, sizeof ctr);
        sodium_memzero(&gcm, sizeof gcm);
        *n = seal ? body + TAG : body;
        return ok ? 0 : NOT_OPEN;
    }
    if (seal) {
        crypto_aead_aes256gcm_encrypt(w, &got, w, *n, aad, aad_n, NULL, nonce12, key);
    } else if (crypto_aead_aes256gcm_decrypt(w, &got, NULL, w, *n, aad, aad_n, nonce12, key) != 0) {
        return NOT_OPEN;
    }
    *n = (size_t) got;
    return 0;
}

static int xchacha(int seal, const unsigned char *key, const unsigned char *nonce24, const unsigned char *aad,
                   size_t aad_n, unsigned char *w, size_t *n) {
    unsigned long long got = 0;

    if (seal) {
        crypto_aead_xchacha20poly1305_ietf_encrypt(w, &got, w, *n, aad, aad_n, NULL, nonce24, key);
    } else if (*n < TAG || crypto_aead_xchacha20poly1305_ietf_decrypt(w, &got, NULL, w, *n, aad, aad_n, nonce24, key) != 0) {
        return NOT_OPEN;
    }
    *n = (size_t) got;
    return 0;
}

// Keys are 64 bytes: AES takes the first half, XChaCha20 the second; the cascade is XChaCha20 inside, AES outside.
int run(int seal, int cipher, int soft, const unsigned char *key, const unsigned char *nonce,
               const unsigned char *aad, size_t aad_n, unsigned char *w, size_t *n) {
    int r;
    switch (cipher) {
        case AES:
            return aes(seal, soft, key, nonce, aad, aad_n, w, n);
        case XCHACHA:
            return xchacha(seal, key + 32, nonce, aad, aad_n, w, n);
        default:
            if (seal) {
                r = xchacha(1, key + 32, nonce, aad, aad_n, w, n);
                return r != 0 ? r : aes(1, soft, key, nonce, aad, aad_n, w, n);
            }
            r = aes(0, soft, key, nonce, aad, aad_n, w, n);
            return r != 0 ? r : xchacha(0, key + 32, nonce, aad, aad_n, w, n);
    }
}

// The key comes from the array, or, when it is null, from the table by handle.
static jbyteArray crypt(JNIEnv *env, int seal, jint cipher, jbyteArray key, jlong handle, jbyteArray nonce,
                        jbyteArray aad, jbyteArray data, jint off, jint len, jboolean soft) {
    unsigned char n24[NONCE];
    unsigned char *k = NULL, *a = NULL, *w = NULL;
    size_t aad_n, cap, n;
    size_t over = cipher == CASCADE ? 2 * TAG : TAG;
    jbyteArray r = NULL;
    int err = 0;

    if (cipher < AES || cipher > CASCADE || nonce == NULL || aad == NULL || data == NULL ||
        (key != NULL && (*env)->GetArrayLength(env, key) != KEY) || (*env)->GetArrayLength(env, nonce) != NONCE || off < 0 ||
        len < 0 || (jlong) off + len > (*env)->GetArrayLength(env, data) ||
        (*env)->GetArrayLength(env, aad) > MAX_AAD || (seal && (jlong) len + (jlong) over > INT32_MAX)) {
        fail(env, BAD_ARGS, "wrong arguments");
        return NULL;
    }
    aad_n = (size_t) (*env)->GetArrayLength(env, aad);
    n = (size_t) len;
    cap = seal ? n + over : n;
    if ((k = slot_take()) == NULL || (a = plain_alloc(aad_n)) == NULL || (w = plain_alloc(cap)) == NULL) {
        err = NO_MEMORY;
    } else {
        if (key != NULL) (*env)->GetByteArrayRegion(env, key, 0, KEY, (jbyte *) k);
        else err = table_copy(handle, k);
        (*env)->GetByteArrayRegion(env, nonce, 0, NONCE, (jbyte *) n24);
        (*env)->GetByteArrayRegion(env, aad, 0, (jsize) aad_n, (jbyte *) a);
        (*env)->GetByteArrayRegion(env, data, off, len, (jbyte *) w);
        if (err == 0 && !(*env)->ExceptionCheck(env)) {
            err = run(seal, cipher, soft == JNI_TRUE, k, n24, a, aad_n, w, &n);
            if (err == 0) r = give(env, w, n);
        }
    }
    fail(env, err, err == NO_MEMORY ? "the key core has no memory" : "no such key");
    plain_free(w, cap);
    plain_free(a, aad_n);
    slot_give_back(k);
    return r;
}

JNIEXPORT jbyteArray JNICALL Java_dev_antite_keys_KeyCore_seal(JNIEnv *env, jclass cls, jint cipher, jbyteArray key,
                                                               jbyteArray nonce, jbyteArray aad, jbyteArray data,
                                                               jint off, jint len, jboolean soft) {
    (void) cls;
    if (key == NULL) {
        fail(env, BAD_ARGS, "no key");
        return NULL;
    }
    return crypt(env, 1, cipher, key, 0, nonce, aad, data, off, len, soft);
}

JNIEXPORT jbyteArray JNICALL Java_dev_antite_keys_KeyCore_open(JNIEnv *env, jclass cls, jint cipher, jbyteArray key,
                                                               jbyteArray nonce, jbyteArray aad, jbyteArray data,
                                                               jint off, jint len, jboolean soft) {
    (void) cls;
    if (key == NULL) {
        fail(env, BAD_ARGS, "no key");
        return NULL;
    }
    return crypt(env, 0, cipher, key, 0, nonce, aad, data, off, len, soft);
}

JNIEXPORT jbyteArray JNICALL Java_dev_antite_keys_KeyCore_sealWith(JNIEnv *env, jclass cls, jint cipher, jlong key,
                                                                   jbyteArray nonce, jbyteArray aad, jbyteArray data,
                                                                   jint off, jint len, jboolean soft) {
    (void) cls;
    return crypt(env, 1, cipher, NULL, key, nonce, aad, data, off, len, soft);
}

JNIEXPORT jbyteArray JNICALL Java_dev_antite_keys_KeyCore_openWith(JNIEnv *env, jclass cls, jint cipher, jlong key,
                                                                   jbyteArray nonce, jbyteArray aad, jbyteArray data,
                                                                   jint off, jint len, jboolean soft) {
    (void) cls;
    return crypt(env, 0, cipher, NULL, key, nonce, aad, data, off, len, soft);
}

// A box of a key under another key, as Ciphers.box makes them: cipher id, a random nonce of 24 bytes, the sealed key.
// What leaves the core is only ever sealed.
JNIEXPORT jbyteArray JNICALL Java_dev_antite_keys_KeyCore_wrap(JNIEnv *env, jclass cls, jint cipher, jlong wrapping,
                                                               jlong key, jbyteArray aad) {
    unsigned char *k = NULL, *w = NULL, *a = NULL, nonce[NONCE];
    size_t aad_n, n = KEY;
    jbyteArray r = NULL;
    int err = 0;

    (void) cls;
    if (cipher < AES || cipher > CASCADE || aad == NULL || (*env)->GetArrayLength(env, aad) > MAX_AAD) {
        fail(env, BAD_ARGS, "wrong arguments");
        return NULL;
    }
    aad_n = (size_t) (*env)->GetArrayLength(env, aad);
    if ((k = slot_take()) == NULL || (w = guarded_alloc(1 + NONCE + KEY + 2 * TAG)) == NULL || (a = plain_alloc(aad_n)) == NULL) {
        err = NO_MEMORY;
    } else if ((err = table_copy(wrapping, k)) == 0 && (err = table_copy(key, w + 1 + NONCE)) == 0) {
        (*env)->GetByteArrayRegion(env, aad, 0, (jsize) aad_n, (jbyte *) a);
        randombytes_buf(nonce, NONCE);
        w[0] = (unsigned char) cipher;
        memcpy(w + 1, nonce, NONCE);
        err = run(1, cipher, 0, k, nonce, a, aad_n, w + 1 + NONCE, &n);
        if (err == 0) r = give(env, w, 1 + NONCE + n);
    }
    fail(env, err, err == NO_MEMORY ? "the key core has no memory" : "no such key");
    plain_free(a, aad_n);
    if (w != NULL) sodium_memzero(w, 1 + NONCE + KEY + 2 * TAG);
    guarded_free(w);
    slot_give_back(k);
    return r;
}

// A box made by wrap opened straight into the table: the handle of the key in it, or 0 when it does not open under
// this key.
JNIEXPORT jlong JNICALL Java_dev_antite_keys_KeyCore_unwrap(JNIEnv *env, jclass cls, jlong wrapping, jbyteArray box,
                                                            jbyteArray aad) {
    unsigned char *k = NULL, *w = NULL, *a = NULL, *at, nonce[NONCE];
    size_t aad_n, box_n, n;
    jlong h = 0;
    int err = 0, cipher;

    (void) cls;
    if (box == NULL || aad == NULL || (*env)->GetArrayLength(env, aad) > MAX_AAD) {
        fail(env, BAD_ARGS, "wrong arguments");
        return 0;
    }
    box_n = (size_t) (*env)->GetArrayLength(env, box);
    aad_n = (size_t) (*env)->GetArrayLength(env, aad);
    // A box of another length or cipher is damage, not a wrong argument.
    if (box_n != 1 + NONCE + KEY + TAG && box_n != 1 + NONCE + KEY + 2 * TAG) return 0;
    if ((k = slot_take()) == NULL || (w = guarded_alloc(box_n)) == NULL || (a = plain_alloc(aad_n)) == NULL) {
        err = NO_MEMORY;
    } else if ((err = table_copy(wrapping, k)) == 0) {
        (*env)->GetByteArrayRegion(env, box, 0, (jsize) box_n, (jbyte *) w);
        (*env)->GetByteArrayRegion(env, aad, 0, (jsize) aad_n, (jbyte *) a);
        cipher = w[0];
        memcpy(nonce, w + 1, NONCE);
        n = box_n - 1 - NONCE;
        if (cipher < AES || cipher > CASCADE || n != KEY + (cipher == CASCADE ? 2 * TAG : TAG) ||
            run(0, cipher, 0, k, nonce, a, aad_n, w + 1 + NONCE, &n) != 0 || n != KEY) {
            err = NOT_OPEN;
        } else if ((err = table_new(&at, &h)) == 0) {
            memcpy(at, w + 1 + NONCE, KEY);
        }
    }
    fail(env, err, err == NO_MEMORY ? "the key core has no memory" : "no such key");
    plain_free(a, aad_n);
    if (w != NULL) sodium_memzero(w, box_n);
    guarded_free(w);
    slot_give_back(k);
    return err == 0 ? h : 0;
}
