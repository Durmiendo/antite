# The native key core (decision 0013): keycore.c over a trimmed libsodium and BearSSL's constant-time AES.
# Only the files listed here are built; tools/vendor/keycore.py copies exactly these (and the headers they use) from
# the verified release archives. Built by the NDK for the app and by the host compiler for the JVM tests of :keyring.
cmake_minimum_required(VERSION 3.22)
project(keycore C)

set(SODIUM_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libsodium CACHE PATH "libsodium sources")
set(BEARSSL_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/bearssl CACHE PATH "BearSSL sources")
set(S ${SODIUM_ROOT}/src/libsodium)
set(B ${BEARSSL_ROOT})

set(SODIUM_SOURCES
    ${S}/crypto_aead/aes256gcm/aead_aes256gcm.c
    ${S}/crypto_aead/aes256gcm/aesni/aead_aes256gcm_aesni.c
    ${S}/crypto_aead/aes256gcm/armcrypto/aead_aes256gcm_armcrypto.c
    ${S}/crypto_aead/xchacha20poly1305/aead_xchacha20poly1305.c
    ${S}/crypto_core/hchacha20/core_hchacha20.c
    ${S}/crypto_generichash/blake2b/ref/blake2b-compress-ref.c
    ${S}/crypto_generichash/blake2b/ref/blake2b-ref.c
    ${S}/crypto_generichash/blake2b/ref/generichash_blake2b.c
    ${S}/crypto_onetimeauth/poly1305/donna/poly1305_donna.c
    ${S}/crypto_onetimeauth/poly1305/onetimeauth_poly1305.c
    ${S}/crypto_pwhash/argon2/argon2-core.c
    ${S}/crypto_pwhash/argon2/argon2-fill-block-ref.c
    ${S}/crypto_pwhash/argon2/argon2.c
    ${S}/crypto_pwhash/argon2/blake2b-long.c
    ${S}/crypto_stream/chacha20/ref/chacha20_ref.c
    ${S}/crypto_stream/chacha20/stream_chacha20.c
    ${S}/crypto_verify/verify.c
    ${S}/randombytes/randombytes.c
    ${S}/randombytes/sysrandom/randombytes_sysrandom.c
    ${S}/sodium/runtime.c
    ${S}/sodium/utils.c
)

set(BEARSSL_SOURCES
    ${B}/src/aead/gcm.c
    ${B}/src/codec/ccopy.c
    ${B}/src/codec/dec32be.c
    ${B}/src/codec/dec32le.c
    ${B}/src/codec/dec64be.c
    ${B}/src/codec/enc32be.c
    ${B}/src/codec/enc32le.c
    ${B}/src/codec/enc64be.c
    ${B}/src/hash/ghash_ctmul64.c
    ${B}/src/symcipher/aes_common.c
    ${B}/src/symcipher/aes_ct64.c
    ${B}/src/symcipher/aes_ct64_ctr.c
    ${B}/src/symcipher/aes_ct64_enc.c
)

# SSSE3 comes with the AES-NI build switches, so its variants of BLAKE2b, ChaCha20 and Argon2 come too; AVX2 and
# SSE4.1 ones stay out. On arm64 Argon2 has a NEON variant; armv7 gets the plain C of everything and software AES.
set(SODIUM_X86_64
    ${S}/crypto_generichash/blake2b/ref/blake2b-compress-ssse3.c
    ${S}/crypto_pwhash/argon2/argon2-fill-block-ssse3.c
    ${S}/crypto_stream/chacha20/dolbeau/chacha20_dolbeau-ssse3.c
)
set(SODIUM_ARM64
    ${S}/crypto_pwhash/argon2/argon2-fill-block-neon.c
)

set(ARCH_SOURCES)
if(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64")
    set(ARCH_SOURCES ${SODIUM_X86_64})
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
    set(ARCH_SOURCES ${SODIUM_ARM64})
endif()

add_library(keycore SHARED src/keycore.c ${SODIUM_SOURCES} ${ARCH_SOURCES} ${BEARSSL_SOURCES})

if(NOT ANDROID)
    find_package(JNI REQUIRED COMPONENTS JVM)
    target_include_directories(keycore PRIVATE ${JNI_INCLUDE_DIRS})
endif()

target_include_directories(keycore PRIVATE
    ${S}/include ${S}/include/sodium ${S}/crypto_pwhash/argon2
    ${B}/inc ${B}/src
)

# What configure would find on Linux and Android; the processor's own extensions are chosen at run time.
target_compile_definitions(keycore PRIVATE
    CONFIGURED=1 SODIUM_STATIC=1 NATIVE_LITTLE_ENDIAN=1
    HAVE_SYS_MMAN_H=1 HAVE_MMAP=1 HAVE_MPROTECT=1 HAVE_MLOCK=1 HAVE_MADVISE=1 HAVE_POSIX_MEMALIGN=1
    HAVE_SYS_RANDOM_H=1 HAVE_GETRANDOM=1 HAVE_SYS_AUXV_H=1 HAVE_GETAUXVAL=1 HAVE_SYSCONF=1
    HAVE_INLINE_ASM=1 HAVE_ATOMIC_OPS=1 HAVE_GCC_MEMORY_FENCES=1
)
if(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64")
    target_compile_definitions(keycore PRIVATE HAVE_CPUID=1 HAVE_EMMINTRIN_H=1 HAVE_TMMINTRIN_H=1 HAVE_WMMINTRIN_H=1)
    target_compile_definitions(keycore PRIVATE BR_64=1 BR_LE_UNALIGNED=1)
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
    target_compile_definitions(keycore PRIVATE HAVE_ARMCRYPTO=1 HAVE_ARM_NEON=1)
    target_compile_definitions(keycore PRIVATE BR_64=1)
endif()

target_compile_options(keycore PRIVATE
    -O2 -fvisibility=hidden -ffunction-sections -fdata-sections -fno-strict-aliasing -fwrapv
    -fstack-protector-strong -D_FORTIFY_SOURCE=2 -Wall
    -ffile-prefix-map=${CMAKE_CURRENT_SOURCE_DIR}=keycore
)
# No build id: it is a hash of the unstripped library, which holds the build paths, so it would differ between machines.
target_link_options(keycore PRIVATE
    -Wl,--gc-sections -Wl,--no-undefined -Wl,--build-id=none -Wl,-z,relro,-z,now
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/exports.map
)
set_target_properties(keycore PROPERTIES LINK_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/exports.map)
