// Shared by the files of the native key core (see memory.c for what it is).
#ifndef KEYCORE_H
#define KEYCORE_H

#include <jni.h>
#include <pthread.h>
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
enum { NOT_OPEN = 1, BAD_ARGS = 2, NO_MEMORY = 3 };
enum { SLOT = 64, SLOTS = 64, MAX_AAD = 1 << 20, MAX_DERIVED = 1024, MAX_LANES = 16 };

// Fault injection of the host tests (KEYCORE_FAULTS, memory.c): FAULT() is true for the allocation chosen to fail.
#ifdef KEYCORE_FAULTS
int kc_faulted(void);
void kc_live(long d);
#define FAULT() kc_faulted()
#define LIVE(d) kc_live(d)
#else
#define FAULT() 0
#define LIVE(d) ((void) 0)
#endif

void fail(JNIEnv *env, int err, const char *what);
void *plain_alloc(size_t n);
void plain_free(void *p, size_t n);
void *guarded_alloc(size_t n);
void guarded_free(void *p);
unsigned char *slot_take(void);
void slot_give_back(unsigned char *p);
int table_new(unsigned char **at, jlong *handle);
int table_copy(jlong handle, unsigned char *dst);
int table_free(jlong handle);
jbyteArray give(JNIEnv *env, const unsigned char *p, size_t n);
int run(int seal, int cipher, int soft, const unsigned char *key, const unsigned char *nonce,
        const unsigned char *aad, size_t aad_n, unsigned char *w, size_t *n);

#endif
