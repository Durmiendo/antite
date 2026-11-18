// The native key core: the ciphers of the key ring, Argon2id and random bytes, over a trimmed libsodium and, for AES
// where the processor has no AES instructions, BearSSL's constant-time AES. This file: start, memory and the key table.
// Keys sit in the table or, for the length of a call, in a pool of slots allocated once, guarded and locked in memory;
// passwords in guarded memory of their own. Data is worked on in place in plain memory (the plaintext is in the Java
// heap anyway) and wiped before it is freed. Every failure is told apart: wrong arguments throw IllegalArgumentException,
// no memory OutOfMemoryError, and null means only that the bytes do not open under this key.

#include "keycore.h"

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
int kc_faulted(void) {
    return __atomic_add_fetch(&fault_calls, 1, __ATOMIC_SEQ_CST) == __atomic_load_n(&fault_at, __ATOMIC_SEQ_CST);
}
void kc_live(long d) {
    __atomic_add_fetch(&live, d, __ATOMIC_SEQ_CST);
}
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
#endif

static unsigned char *pool;
static uint64_t used;
static int pool_locked;

void fail(JNIEnv *env, int err, const char *what) {
    jclass c;
    if (err == 0 || err == NOT_OPEN || (*env)->ExceptionCheck(env)) return;
    c = (*env)->FindClass(env, err == NO_MEMORY ? "java/lang/OutOfMemoryError" : "java/lang/IllegalArgumentException");
    if (c != NULL) (*env)->ThrowNew(env, c, what);
}

void *plain_alloc(size_t n) {
    void *p = FAULT() ? NULL : malloc(n > 0 ? n : 1);
    if (p != NULL) LIVE(1);
    return p;
}

void plain_free(void *p, size_t n) {
    if (p == NULL) return;
    sodium_memzero(p, n);
    free(p);
    LIVE(-1);
}

void *guarded_alloc(size_t n) {
    void *p = FAULT() ? NULL : sodium_malloc(n > 0 ? n : 1);
    if (p != NULL) LIVE(1);
    return p;
}

void guarded_free(void *p) {
    if (p == NULL) return;
    sodium_free(p);
    LIVE(-1);
}

// A key slot from the pool; when every slot is taken, guarded memory of its own.
unsigned char *slot_take(void) {
    uint64_t u = __atomic_load_n(&used, __ATOMIC_ACQUIRE);
    while (u != UINT64_MAX) {
        int bit = __builtin_ctzll(~u);
        if (__atomic_compare_exchange_n(&used, &u, u | (UINT64_C(1) << bit), 0, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE)) {
            return pool + (size_t) bit * SLOT;
        }
    }
    return guarded_alloc(SLOT);
}

void slot_give_back(unsigned char *p) {
    if (p == NULL) return;
    sodium_memzero(p, SLOT);
    if (p >= pool && p < pool + (size_t) SLOT * SLOTS) {
        __atomic_and_fetch(&used, ~(UINT64_C(1) << ((p - pool) / SLOT)), __ATOMIC_RELEASE);
    } else {
        guarded_free(p);
    }
}

// The key table: the keys of the ring, the categories and the files, 64 bytes each in guarded memory locked in RAM,
// grown by pages of slots. Kotlin holds a handle only (slot number and generation): a handle whose key was freed is
// refused, and a key is copied into a pool slot for the length of each call, so freeing it never races a call.
enum { PAGE_SLOTS = 64, MAX_PAGES = 256 };
typedef struct {
    unsigned char *keys;
    uint32_t gen[PAGE_SLOTS];
    uint64_t used;
} key_page;
static key_page pages[MAX_PAGES];
static int npages;
static int table_unlocked;
static long table_count;
static pthread_mutex_t table = PTHREAD_MUTEX_INITIALIZER;

static jlong handle_of(int page, int slot, uint32_t gen) {
    return (jlong) (((uint64_t) (page * PAGE_SLOTS + slot) << 32) | gen);
}

// A new slot for a key, held for the caller until it fills it: *at points into the table. NO_MEMORY or 0.
int table_new(unsigned char **at, jlong *handle) {
    int pg, slot, err = 0;

    pthread_mutex_lock(&table);
    for (pg = 0; pg < npages && pages[pg].used == UINT64_MAX; pg++) {
    }
    if (pg == npages) {
        // A page stays for the life of the process: not counted as live by the fault tests.
        if (npages == MAX_PAGES || (pages[pg].keys = FAULT() ? NULL : sodium_malloc((size_t) SLOT * PAGE_SLOTS)) == NULL) {
            err = NO_MEMORY;
        } else {
            if (sodium_mlock(pages[pg].keys, (size_t) SLOT * PAGE_SLOTS) != 0) table_unlocked = 1;
            npages++;
        }
    }
    if (err == 0) {
        slot = __builtin_ctzll(~pages[pg].used);
        pages[pg].used |= UINT64_C(1) << slot;
        if (++pages[pg].gen[slot] == 0) pages[pg].gen[slot] = 1;
        *at = pages[pg].keys + (size_t) slot * SLOT;
        *handle = handle_of(pg, slot, pages[pg].gen[slot]);
        table_count++;
    }
    pthread_mutex_unlock(&table);
    return err;
}

static int table_find(jlong handle, int *pg, int *slot) {
    uint64_t h = (uint64_t) handle;
    uint32_t index = (uint32_t) (h >> 32), gen = (uint32_t) h;

    *pg = (int) (index / PAGE_SLOTS);
    *slot = (int) (index % PAGE_SLOTS);
    return gen != 0 && *pg < npages && (pages[*pg].used >> *slot & 1) && pages[*pg].gen[*slot] == gen ? 0 : BAD_ARGS;
}

// The key of a handle copied into dst (a pool slot). BAD_ARGS for a handle that is not a live key.
int table_copy(jlong handle, unsigned char *dst) {
    int pg, slot, err;

    pthread_mutex_lock(&table);
    err = table_find(handle, &pg, &slot);
    if (err == 0) memcpy(dst, pages[pg].keys + (size_t) slot * SLOT, KEY);
    pthread_mutex_unlock(&table);
    return err;
}

int table_free(jlong handle) {
    int pg, slot, err;

    pthread_mutex_lock(&table);
    err = table_find(handle, &pg, &slot);
    if (err == 0) {
        sodium_memzero(pages[pg].keys + (size_t) slot * SLOT, SLOT);
        pages[pg].used &= ~(UINT64_C(1) << slot);
        if (++pages[pg].gen[slot] == 0) pages[pg].gen[slot] = 1;
        table_count--;
    }
    pthread_mutex_unlock(&table);
    return err;
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
    return pool_locked && !table_unlocked ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL Java_dev_antite_keys_KeyCore_keyCount(JNIEnv *env, jclass cls) {
    long n;

    (void) env;
    (void) cls;
    pthread_mutex_lock(&table);
    n = table_count;
    pthread_mutex_unlock(&table);
    return (jlong) n;
}
