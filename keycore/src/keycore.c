// The native key core: the ciphers of the key ring, Argon2id and random bytes, over a trimmed libsodium and, for AES
// where the processor has no AES instructions, BearSSL's constant-time AES. Secrets copied in from Java live in
// guarded memory and are wiped before every return, on every path.

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
        ready = 1;
    }
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_dev_antite_keys_KeyCore_aesHardware(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    return crypto_aead_aes256gcm_is_available() ? JNI_TRUE : JNI_FALSE;
}

// A buffer for secrets: guarded pages, locked, wiped when freed.
typedef struct {
    unsigned char *p;
    size_t n;
} buf;

static int take(JNIEnv *env, jbyteArray a, jint off, jint len, buf *out) {
    out->p = NULL;
    out->n = 0;
    if (a == NULL || off < 0 || len < 0 || (*env)->GetArrayLength(env, a) - off < len) return -1;
    out->p = sodium_malloc(len > 0 ? (size_t) len : 1);
    if (out->p == NULL) return -1;
    out->n = (size_t) len;
    (*env)->GetByteArrayRegion(env, a, off, len, (jbyte *) out->p);
    return (*env)->ExceptionCheck(env) ? -1 : 0;
}

static int take_all(JNIEnv *env, jbyteArray a, buf *out) {
    out->p = NULL;
    out->n = 0;
    if (a == NULL) return -1;
    return take(env, a, 0, (*env)->GetArrayLength(env, a), out);
}

static void drop(buf *b) {
    if (b->p != NULL) sodium_free(b->p);
    b->p = NULL;
    b->n = 0;
}

static int fresh(size_t n, buf *out) {
    out->p = sodium_malloc(n > 0 ? n : 1);
    out->n = n;
    return out->p == NULL ? -1 : 0;
}

static jbyteArray give(JNIEnv *env, const buf *b) {
    jbyteArray r = (*env)->NewByteArray(env, (jsize) b->n);
    if (r != NULL) (*env)->SetByteArrayRegion(env, r, 0, (jsize) b->n, (const jbyte *) b->p);
    return r;
}

// AES-256-GCM in BearSSL's constant-time 64-bit AES and GHASH, for processors without AES instructions.
static int aes_soft(int seal, const unsigned char *key, const unsigned char *nonce12, const buf *aad,
                    const buf *in, buf *out) {
    br_aes_ct64_ctr_keys ctr;
    br_gcm_context gcm;
    size_t body;
    int ok = 0;

    if (seal) {
        body = in->n;
        if (fresh(body + TAG, out) != 0) return -1;
    } else {
        if (in->n < TAG) return -1;
        body = in->n - TAG;
        if (fresh(body, out) != 0) return -1;
    }
    memcpy(out->p, in->p, body);
    br_aes_ct64_ctr_init(&ctr, key, 32);
    br_gcm_init(&gcm, &ctr.vtable, br_ghash_ctmul64);
    br_gcm_reset(&gcm, nonce12, 12);
    br_gcm_aad_inject(&gcm, aad->p, aad->n);
    br_gcm_flip(&gcm);
    br_gcm_run(&gcm, seal, out->p, body);
    if (seal) {
        br_gcm_get_tag(&gcm, out->p + body);
        ok = 1;
    } else {
        ok = br_gcm_check_tag(&gcm, in->p + body) ? 1 : 0;
    }
    sodium_memzero(&ctr, sizeof ctr);
    sodium_memzero(&gcm, sizeof gcm);
    if (!ok) drop(out);
    return ok ? 0 : -1;
}

static int aes(int seal, int soft, const unsigned char *key, const unsigned char *nonce12, const buf *aad,
               const buf *in, buf *out) {
    unsigned long long n = 0;

    if (soft || !crypto_aead_aes256gcm_is_available()) return aes_soft(seal, key, nonce12, aad, in, out);
    if (seal) {
        if (fresh(in->n + TAG, out) != 0) return -1;
        crypto_aead_aes256gcm_encrypt(out->p, &n, in->p, in->n, aad->p, aad->n, NULL, nonce12, key);
        return 0;
    }
    if (in->n < TAG || fresh(in->n - TAG, out) != 0) return -1;
    if (crypto_aead_aes256gcm_decrypt(out->p, &n, NULL, in->p, in->n, aad->p, aad->n, nonce12, key) != 0) {
        drop(out);
        return -1;
    }
    return 0;
}

static int xchacha(int seal, const unsigned char *key, const unsigned char *nonce24, const buf *aad, const buf *in,
                   buf *out) {
    unsigned long long n = 0;

    if (seal) {
        if (fresh(in->n + TAG, out) != 0) return -1;
        crypto_aead_xchacha20poly1305_ietf_encrypt(out->p, &n, in->p, in->n, aad->p, aad->n, NULL, nonce24, key);
        return 0;
    }
    if (in->n < TAG || fresh(in->n - TAG, out) != 0) return -1;
    if (crypto_aead_xchacha20poly1305_ietf_decrypt(out->p, &n, NULL, in->p, in->n, aad->p, aad->n, nonce24, key) != 0) {
        drop(out);
        return -1;
    }
    return 0;
}

// Keys are 64 bytes: AES takes the first half, XChaCha20 the second; the cascade is XChaCha20 inside, AES outside.
static int run(int seal, int cipher, int soft, const buf *key, const buf *nonce, const buf *aad, const buf *in,
               buf *out) {
    buf mid = {NULL, 0};
    int r = -1;

    if (key->n != KEY || nonce->n != NONCE) return -1;
    switch (cipher) {
        case AES:
            return aes(seal, soft, key->p, nonce->p, aad, in, out);
        case XCHACHA:
            return xchacha(seal, key->p + 32, nonce->p, aad, in, out);
        case CASCADE:
            if (seal) {
                if (xchacha(1, key->p + 32, nonce->p, aad, in, &mid) == 0) r = aes(1, soft, key->p, nonce->p, aad, &mid, out);
            } else {
                if (aes(0, soft, key->p, nonce->p, aad, in, &mid) == 0) r = xchacha(0, key->p + 32, nonce->p, aad, &mid, out);
            }
            drop(&mid);
            return r;
        default:
            return -1;
    }
}

// Sealed or opened bytes; null when they do not open under this key, or when the arguments are wrong.
static jbyteArray crypt(JNIEnv *env, int seal, jint cipher, jbyteArray key, jbyteArray nonce, jbyteArray aad,
                        jbyteArray data, jint off, jint len, jboolean soft) {
    buf k, n, a, in, out = {NULL, 0};
    jbyteArray r = NULL;

    int ok = take_all(env, key, &k) == 0 && take_all(env, nonce, &n) == 0 && take_all(env, aad, &a) == 0 &&
             take(env, data, off, len, &in) == 0;
    if (ok && run(seal, cipher, soft == JNI_TRUE, &k, &n, &a, &in, &out) == 0) r = give(env, &out);
    drop(&out);
    drop(&in);
    drop(&a);
    drop(&n);
    drop(&k);
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

static int derive(const buf *p, const buf *s, uint32_t t, uint32_t m, uint32_t lanes, buf *out) {
    argon2_context c;

    memset(&c, 0, sizeof c);
    c.out = out->p;
    c.outlen = (uint32_t) out->n;
    c.pwd = p->p;
    c.pwdlen = (uint32_t) p->n;
    c.salt = s->p;
    c.saltlen = (uint32_t) s->n;
    c.t_cost = t;
    c.m_cost = m;
    c.lanes = lanes;
    c.threads = lanes;
    c.flags = ARGON2_DEFAULT_FLAGS;
    return argon2_ctx(&c, Argon2_id);
}

// Argon2id with any number of lanes: libsodium's own crypto_pwhash fixes them at 1, and passwords set before the
// core were derived with 2.
JNIEXPORT jbyteArray JNICALL Java_dev_antite_keys_KeyCore_argon2id(JNIEnv *env, jclass cls, jbyteArray password,
                                                                   jbyteArray salt, jint iterations, jint memoryKib,
                                                                   jint lanes, jint length) {
    buf p, s, out = {NULL, 0};
    jbyteArray r = NULL;

    (void) cls;
    if (iterations > 0 && memoryKib > 0 && lanes > 0 && length > 0 && take_all(env, password, &p) == 0 &&
        take_all(env, salt, &s) == 0 && fresh((size_t) length, &out) == 0 &&
        derive(&p, &s, (uint32_t) iterations, (uint32_t) memoryKib, (uint32_t) lanes, &out) == ARGON2_OK) {
        r = give(env, &out);
    }
    drop(&out);
    drop(&s);
    drop(&p);
    return r;
}

JNIEXPORT void JNICALL Java_dev_antite_keys_KeyCore_random(JNIEnv *env, jclass cls, jbyteArray out) {
    buf b;

    (void) cls;
    if (out == NULL || fresh((size_t) (*env)->GetArrayLength(env, out), &b) != 0) return;
    randombytes_buf(b.p, b.n);
    (*env)->SetByteArrayRegion(env, out, 0, (jsize) b.n, (const jbyte *) b.p);
    drop(&b);
}
