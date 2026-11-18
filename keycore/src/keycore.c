// The native key core: the ciphers of the key ring, Argon2id and random bytes, over a trimmed libsodium and, for AES
// where the processor has no AES instructions, BearSSL's constant-time AES. Keys sit in a pool of slots allocated
// once, guarded and locked in memory; passwords and derived keys in guarded memory of their own. Data is worked on in
// place in plain memory (the plaintext is in the Java heap anyway) and wiped before it is freed. Every failure is told
// apart: wrong arguments throw IllegalArgumentException, no memory OutOfMemoryError, and null means only that the bytes
// do not open under this key.

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "sodium/crypto_aead_aes256gcm.h"
#include "sodium/crypto_aead_xchacha20poly1305.h"
#include "sodium/randombytes.h"
#include "sodium/runtime.h"
#include "sodium/utils.h"
#include "sodium/private/implementations.h"
#include "argon2.h"
#include "bearssl_aead.h"
#include "bearssl_block.h"
#include "bearssl_hash.h"

enum { AES = 1, XCHACHA = 2, CASCADE = 3 };
enum { KEY = 64, NONCE = 24, TAG = 16 };

int _sodium_runtime_get_cpu_features(void);
int _crypto_generichash_blake2b_pick_best_implementation(void);
int _crypto_onetimeauth_poly1305_pick_best_implementation(void);
int _crypto_pwhash_argon2_pick_best_implementation(void);
int _crypto_stream_chacha20_pick_best_implementation(void);

// libsodium calls this on a misuse it cannot report; core.c, which defines it, is left out.
void sodium_misuse(void) {
    abort();
}

static int ready;

// Fault injection for the host tests (KEYCORE_FAULTS): the n-th allocation after arm(n) fails, and every live one is
// counted, so a test can fail each allocation in turn and see nothing leak. Not built for the app.
#ifdef KEYCORE_FAULTS
#include <sys/mman.h>
static long fault_at = -1;
static long fault_calls;
static long live;
static int faulted(void) {
    return __atomic_add_fetch(&fault_calls, 1, __ATOMIC_SEQ_CST) == __atomic_load_n(&fault_at, __ATOMIC_SEQ_CST);
}
#define FAULT() faulted()
#define LIVE(d) ((void) __atomic_add_fetch(&live, (d), __ATOMIC_SEQ_CST))
void *kc_fault_malloc(size_t n) {
    void *p = FAULT() ? NULL : malloc(n);
    if (p != NULL) LIVE(1);
    return p;
}
void kc_fault_free(void *p) {
    if (p != NULL) LIVE(-1);
    free(p);
}
void *kc_fault_mmap(void *a, size_t n, int prot, int flags, int fd, off_t off) {
    void *p = FAULT() ? MAP_FAILED : mmap(a, n, prot, flags, fd, off);
    if (p != MAP_FAILED) LIVE(1);
    return p;
}
int kc_fault_munmap(void *p, size_t n) {
    LIVE(-1);
    return munmap(p, n);
}
JNIEXPORT void JNICALL Java_dev_antite_keys_KeyCoreFaults_arm(JNIEnv *env, jclass cls, jlong n) {
    (void) env;
    (void) cls;
    __atomic_store_n(&fault_calls, 0, __ATOMIC_SEQ_CST);
    __atomic_store_n(&fault_at, (long) n, __ATOMIC_SEQ_CST);
}
JNIEXPORT jlong JNICALL Java_dev_antite_keys_KeyCoreFaults_calls(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    return __atomic_load_n(&fault_calls, __ATOMIC_SEQ_CST);
}
JNIEXPORT jlong JNICALL Java_dev_antite_keys_KeyCoreFaults_live(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    return __atomic_load_n(&live, __ATOMIC_SEQ_CST);
}
#else
#define FAULT() 0
#define LIVE(d) ((void) 0)
#endif

enum { NOT_OPEN = 1, BAD_ARGS = 2, NO_MEMORY = 3 };
enum { SLOT = 64, SLOTS = 64, MAX_AAD = 1 << 20, MAX_DERIVED = 1024, MAX_LANES = 16 };

static unsigned char *pool;
static uint64_t used;
static int pool_locked;

static void fail(JNIEnv *env, int err, const char *what) {
    jclass c;
    if (err == 0 || err == NOT_OPEN || (*env)->ExceptionCheck(env)) return;
    c = (*env)->FindClass(env, err == NO_MEMORY ? "java/lang/OutOfMemoryError" : "java/lang/IllegalArgumentException");
    if (c != NULL) (*env)->ThrowNew(env, c, what);
}

static void *plain_alloc(size_t n) {
    void *p = FAULT() ? NULL : malloc(n > 0 ? n : 1);
    if (p != NULL) LIVE(1);
    return p;
}

static void plain_free(void *p, size_t n) {
    if (p == NULL) return;
    sodium_memzero(p, n);
    free(p);
    LIVE(-1);
}

static void *guarded_alloc(size_t n) {
    void *p = FAULT() ? NULL : sodium_malloc(n > 0 ? n : 1);
    if (p != NULL) LIVE(1);
    return p;
}

static void guarded_free(void *p) {
    if (p == NULL) return;
    sodium_free(p);
    LIVE(-1);
}

// A key slot from the pool; when every slot is taken, guarded memory of its own.
static unsigned char *slot_take(void) {
    uint64_t u = __atomic_load_n(&used, __ATOMIC_ACQUIRE);
    while (u != UINT64_MAX) {
        int bit = __builtin_ctzll(~u);
        if (__atomic_compare_exchange_n(&used, &u, u | (UINT64_C(1) << bit), 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
            return pool + (size_t) bit * SLOT;
        }
    }
    return guarded_alloc(SLOT);
}

static void slot_give_back(unsigned char *p) {
    if (p == NULL) return;
    sodium_memzero(p, SLOT);
    if (p >= pool && p < pool + (size_t) SLOT * SLOTS) {
        __atomic_and_fetch(&used, ~(UINT64_C(1) << ((p - pool) / SLOT)), __ATOMIC_RELEASE);
    } else {
        guarded_free(p);
    }
}

JNIEXPORT jboolean JNICALL Java_dev_antite_keys_KeyCore_init(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    if (!ready) {
        _sodium_runtime_get_cpu_features();
        _crypto_generichash_blake2b_pick_best_implementation();
        _crypto_onetimeauth_poly1305_pick_best_implementation();
        _crypto_pwhash_argon2_pick_best_implementation();
        _crypto_stream_chacha20_pick_best_implementation();
        _sodium_alloc_init();
        randombytes_stir();
        // sodium_malloc locks its pages but ignores a refusal, so the lock is asked for again and the answer kept.
        pool = sodium_malloc((size_t) SLOT * SLOTS);
        if (pool == NULL) return JNI_FALSE;
        pool_locked = sodium_mlock(pool, (size_t) SLOT * SLOTS) == 0;
        ready = 1;
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_dev_antite_keys_KeyCore_aesHardware(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    return crypto_aead_aes256gcm_is_available() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_dev_antite_keys_KeyCore_memoryLocked(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    return pool_locked ? JNI_TRUE : JNI_FALSE;
}

static jbyteArray give(JNIEnv *env, const unsigned char *p, size_t n) {
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
static int run(int seal, int cipher, int soft, const unsigned char *key, const unsigned char *nonce,
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

static jbyteArray crypt(JNIEnv *env, int seal, jint cipher, jbyteArray key, jbyteArray nonce, jbyteArray aad,
                        jbyteArray data, jint off, jint len, jboolean soft) {
    unsigned char n24[NONCE];
    unsigned char *k = NULL, *a = NULL, *w = NULL;
    size_t aad_n, cap, n;
    size_t over = cipher == CASCADE ? 2 * TAG : TAG;
    jbyteArray r = NULL;
    int err = 0;

    if (cipher < AES || cipher > CASCADE || key == NULL || nonce == NULL || aad == NULL || data == NULL ||
        (*env)->GetArrayLength(env, key) != KEY || (*env)->GetArrayLength(env, nonce) != NONCE || off < 0 ||
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
        (*env)->GetByteArrayRegion(env, key, 0, KEY, (jbyte *) k);
        (*env)->GetByteArrayRegion(env, nonce, 0, NONCE, (jbyte *) n24);
        (*env)->GetByteArrayRegion(env, aad, 0, (jsize) aad_n, (jbyte *) a);
        (*env)->GetByteArrayRegion(env, data, off, len, (jbyte *) w);
        if (!(*env)->ExceptionCheck(env)) {
            err = run(seal, cipher, soft == JNI_TRUE, k, n24, a, aad_n, w, &n);
            if (err == 0) r = give(env, w, n);
        }
    }
    fail(env, err, "the key core has no memory");
    plain_free(w, cap);
    plain_free(a, aad_n);
    slot_give_back(k);
    return r;
}

JNIEXPORT jbyteArray JNICALL Java_dev_antite_keys_KeyCore_seal(JNIEnv *env, jclass cls, jint cipher, jbyteArray key,
                                                               jbyteArray nonce, jbyteArray aad, jbyteArray data,
                                                               jint off, jint len, jboolean soft) {
    (void) cls;
    return crypt(env, 1, cipher, key, nonce, aad, data, off, len, soft);
}

JNIEXPORT jbyteArray JNICALL Java_dev_antite_keys_KeyCore_open(JNIEnv *env, jclass cls, jint cipher, jbyteArray key,
                                                               jbyteArray nonce, jbyteArray aad, jbyteArray data,
                                                               jint off, jint len, jboolean soft) {
    (void) cls;
    return crypt(env, 0, cipher, key, nonce, aad, data, off, len, soft);
}

// Argon2id with any number of lanes: libsodium's own crypto_pwhash fixes them at 1, and passwords set before the
// core were derived with 2.
JNIEXPORT jbyteArray JNICALL Java_dev_antite_keys_KeyCore_argon2id(JNIEnv *env, jclass cls, jbyteArray password,
                                                                   jbyteArray salt, jint iterations, jint memoryKib,
                                                                   jint lanes, jint length) {
    argon2_context c;
    unsigned char *p = NULL, *s = NULL, *out = NULL;
    size_t p_n, s_n;
    jbyteArray r = NULL;
    int err = 0, res;

    (void) cls;
    if (password == NULL || salt == NULL || iterations < 1 || lanes < 1 || lanes > MAX_LANES ||
        memoryKib < 8 * lanes || length < (jint) ARGON2_MIN_OUTLEN || length > MAX_DERIVED ||
        (*env)->GetArrayLength(env, salt) < (jint) ARGON2_MIN_SALT_LENGTH) {
        fail(env, BAD_ARGS, "wrong Argon2id arguments");
        return NULL;
    }
    p_n = (size_t) (*env)->GetArrayLength(env, password);
    s_n = (size_t) (*env)->GetArrayLength(env, salt);
    if ((p = guarded_alloc(p_n)) == NULL || (s = plain_alloc(s_n)) == NULL || (out = guarded_alloc((size_t) length)) == NULL) {
        err = NO_MEMORY;
    } else {
        (*env)->GetByteArrayRegion(env, password, 0, (jsize) p_n, (jbyte *) p);
        (*env)->GetByteArrayRegion(env, salt, 0, (jsize) s_n, (jbyte *) s);
        memset(&c, 0, sizeof c);
        c.out = out;
        c.outlen = (uint32_t) length;
        c.pwd = p;
        c.pwdlen = (uint32_t) p_n;
        c.salt = s;
        c.saltlen = (uint32_t) s_n;
        c.t_cost = (uint32_t) iterations;
        c.m_cost = (uint32_t) memoryKib;
        c.lanes = (uint32_t) lanes;
        c.threads = (uint32_t) lanes;
        c.flags = ARGON2_DEFAULT_FLAGS;
        res = argon2_ctx(&c, Argon2_id);
        if (res == ARGON2_OK) r = give(env, out, (size_t) length);
        else err = res == ARGON2_MEMORY_ALLOCATION_ERROR ? NO_MEMORY : BAD_ARGS;
    }
    fail(env, err, err == NO_MEMORY ? "no memory for Argon2id" : "Argon2id refused its parameters");
    if (out != NULL) sodium_memzero(out, (size_t) length);
    guarded_free(out);
    plain_free(s, s_n);
    guarded_free(p);
    return r;
}

// Fills the array through a small buffer on the stack: nothing to allocate, so nothing that can fail and leave it as
// it was.
JNIEXPORT void JNICALL Java_dev_antite_keys_KeyCore_random(JNIEnv *env, jclass cls, jbyteArray out) {
    unsigned char b[256];
    jsize n, at, k;

    (void) cls;
    if (out == NULL) {
        fail(env, BAD_ARGS, "no array");
        return;
    }
    n = (*env)->GetArrayLength(env, out);
    for (at = 0; at < n && !(*env)->ExceptionCheck(env); at += k) {
        k = n - at < (jsize) sizeof b ? n - at : (jsize) sizeof b;
        randombytes_buf(b, (size_t) k);
        (*env)->SetByteArrayRegion(env, out, at, k, (const jbyte *) b);
    }
    sodium_memzero(b, sizeof b);
}
