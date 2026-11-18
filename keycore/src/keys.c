// Keys made in the core: random, imported, derived from a password by Argon2id; random bytes (see memory.c).

#include "keycore.h"

// A new random key in the table.
JNIEXPORT jlong JNICALL Java_dev_antite_keys_KeyCore_keyRandom(JNIEnv *env, jclass cls) {
    unsigned char *at;
    jlong h = 0;

    (void) cls;
    if (table_new(&at, &h) != 0) {
        fail(env, NO_MEMORY, "the key table is full");
        return 0;
    }
    randombytes_buf(at, KEY);
    return h;
}

// A key from outside (the device key store, an old backup) into the table; the given array is wiped.
JNIEXPORT jlong JNICALL Java_dev_antite_keys_KeyCore_keyImport(JNIEnv *env, jclass cls, jbyteArray raw) {
    static const unsigned char zero[KEY];
    unsigned char *at;
    jlong h = 0;

    (void) cls;
    if (raw == NULL || (*env)->GetArrayLength(env, raw) != KEY) {
        fail(env, BAD_ARGS, "a key is 64 bytes");
        return 0;
    }
    if (table_new(&at, &h) != 0) {
        fail(env, NO_MEMORY, "the key table is full");
    } else {
        (*env)->GetByteArrayRegion(env, raw, 0, KEY, (jbyte *) at);
    }
    (*env)->SetByteArrayRegion(env, raw, 0, KEY, (const jbyte *) zero);
    return h;
}

JNIEXPORT void JNICALL Java_dev_antite_keys_KeyCore_keyFree(JNIEnv *env, jclass cls, jlong key) {
    (void) cls;
    if (table_free(key) != 0) fail(env, BAD_ARGS, "no such key");
}


// Argon2id with any number of lanes into out: libsodium's own crypto_pwhash fixes them at 1, and passwords set before
// the core were derived with 2.
static int argon2_into(JNIEnv *env, jbyteArray password, jbyteArray salt, jint iterations, jint memoryKib, jint lanes,
                       unsigned char *out, jint length) {
    argon2_context c;
    unsigned char *p = NULL, *s = NULL;
    size_t p_n, s_n;
    int err = 0, res;

    p_n = (size_t) (*env)->GetArrayLength(env, password);
    s_n = (size_t) (*env)->GetArrayLength(env, salt);
    if ((p = guarded_alloc(p_n)) == NULL || (s = plain_alloc(s_n)) == NULL) {
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
        if (res != ARGON2_OK) err = res == ARGON2_MEMORY_ALLOCATION_ERROR ? NO_MEMORY : BAD_ARGS;
    }
    plain_free(s, s_n);
    if (p != NULL) sodium_memzero(p, p_n);
    guarded_free(p);
    return err;
}

static int argon2_args(JNIEnv *env, jbyteArray password, jbyteArray salt, jint iterations, jint memoryKib, jint lanes,
                       jint length) {
    if (password == NULL || salt == NULL || iterations < 1 || lanes < 1 || lanes > MAX_LANES ||
        memoryKib < 8 * lanes || length < (jint) ARGON2_MIN_OUTLEN || length > MAX_DERIVED ||
        (*env)->GetArrayLength(env, salt) < (jint) ARGON2_MIN_SALT_LENGTH) {
        fail(env, BAD_ARGS, "wrong Argon2id arguments");
        return BAD_ARGS;
    }
    return 0;
}

// A 64-byte key from a password, straight into the table.
JNIEXPORT jlong JNICALL Java_dev_antite_keys_KeyCore_keyDerive(JNIEnv *env, jclass cls, jbyteArray password,
                                                               jbyteArray salt, jint iterations, jint memoryKib,
                                                               jint lanes) {
    unsigned char *at;
    jlong h = 0;
    int err;

    (void) cls;
    if (argon2_args(env, password, salt, iterations, memoryKib, lanes, KEY) != 0) return 0;
    err = table_new(&at, &h);
    if (err == 0) {
        err = argon2_into(env, password, salt, iterations, memoryKib, lanes, at, KEY);
        if (err != 0) table_free(h);
    }
    fail(env, err, err == NO_MEMORY ? "no memory for Argon2id" : "Argon2id refused its parameters");
    return err == 0 ? h : 0;
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

