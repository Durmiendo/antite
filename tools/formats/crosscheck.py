"""The golden files of the keyring module against independent implementations: XChaCha20-Poly1305 from libsodium,
AES-256-GCM from OpenSSL (python cryptography). Same bytes means data written on Android opens anywhere these run.

Usage: python3 -I tools/formats/crosscheck.py [keyring/src/test/resources/golden]
Needs libsodium and python3-cryptography.
"""
import ctypes
import ctypes.util
import struct
import sys
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

g = sys.argv[1] if len(sys.argv) > 1 else "keyring/src/test/resources/golden"
sodium = ctypes.CDLL(ctypes.util.find_library("sodium") or "libsodium.so")
assert sodium.sodium_init() >= 0

def xchacha(key32, nonce24, aad, plain):
    out = ctypes.create_string_buffer(len(plain) + 16)
    outlen = ctypes.c_ulonglong()
    r = sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(out, ctypes.byref(outlen), plain, ctypes.c_ulonglong(len(plain)),
        aad, ctypes.c_ulonglong(len(aad)), None, nonce24, key32)
    assert r == 0
    return out.raw[:outlen.value]

def aes(key32, nonce12, aad, plain):
    return AESGCM(key32).encrypt(nonce12, plain, aad)

def seal(cipher, key, nonce, aad, plain):
    if cipher == "aes": return aes(key[:32], nonce[:12], aad, plain)
    if cipher == "xchacha": return xchacha(key[32:], nonce, aad, plain)
    return aes(key[:32], nonce[:12], aad, xchacha(key[32:], nonce, aad, plain))

key = bytes(range(64)); nonce = bytes(100 + i for i in range(24)); aad = b"antite-golden"; plain = b"Antite golden 1"
ok = True
for c in ("aes", "xchacha", "cascade"):
    mine = open(f"{g}/seal-{c}.bin", "rb").read()
    same = seal(c, key, nonce, aad, plain) == mine
    print(f"seal {c}: {'same' if same else 'DIFFERENT'}"); ok &= same
    # The vault file: header, then 64 KiB chunks with counter nonces and AAD "AVLT" 1 index last.
    content = bytes((i * 7 % 251) for i in range(65536 + 100))
    expect = b"AVLT" + bytes([2, {"aes": 1, "xchacha": 2, "cascade": 3}[c]]) + bytes(64)
    chunks = [content[i:i + 65536] for i in range(0, len(content), 65536)]
    for i, ch in enumerate(chunks):
        last = i == len(chunks) - 1
        n = struct.pack(">q", i) + bytes(16)
        a = b"AVLT" + bytes([1]) + struct.pack(">q", i) + bytes([1 if last else 0])
        expect += seal(c, key, n, a, ch)
    mine = open(f"{g}/vaultfile-{c}.bin", "rb").read()
    same = expect == mine
    print(f"vault file {c}: {'same' if same else 'DIFFERENT'} ({len(expect)} vs {len(mine)} bytes)"); ok &= same
print("ALL SAME" if ok else "MISMATCH")
sys.exit(0 if ok else 1)
