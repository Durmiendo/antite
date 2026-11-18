// Forced into argon2-core.c in the host test builds (KEYCORE_FAULTS): its allocations go through keycore.c's fault
// injection. The system headers come first, so only the calls are renamed, not their declarations.
#include <stdlib.h>
#include <sys/mman.h>
#include <sys/types.h>

void *kc_fault_malloc(size_t n);
void kc_fault_free(void *p);
void *kc_fault_mmap(void *a, size_t n, int prot, int flags, int fd, off_t off);
int kc_fault_munmap(void *p, size_t n);

#define malloc(n) kc_fault_malloc(n)
#define free(p) kc_fault_free(p)
#define mmap(a, n, prot, flags, fd, off) kc_fault_mmap(a, n, prot, flags, fd, off)
#define munmap(p, n) kc_fault_munmap(p, n)
