#!/usr/bin/env bash
set -euo pipefail

# Cross-verify AVBForge Rust core against official AOSP avbtool.py (bidirectional!).
# Usage: ./cross_verify_avbtool.sh <path-to-avbforge-core> <path-to-avbtool.py>

CORE_BIN="${1:?Missing avbforge-core binary path}"
AVBTOOL="${2:?Missing avbtool.py path}"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

echo "=== 1. Rust key gen -> compare pkmd with avbtool.py extract_public_key ==="
"$CORE_BIN" key gen --bits 2048 --out-pem "$TMP/key.pem" --out-pkmd "$TMP/rust_pkmd.bin"
python3 "$AVBTOOL" extract_public_key --key "$TMP/key.pem" --output "$TMP/aosp_pkmd.bin"
cmp "$TMP/rust_pkmd.bin" "$TMP/aosp_pkmd.bin"
echo "PASS: 520-byte RSA-2048 avb_pkmd.bin byte-for-byte identical!"

"$CORE_BIN" key gen --bits 4096 --out-pem "$TMP/key4096.pem" --out-pkmd "$TMP/rust_pkmd4096.bin"
python3 "$AVBTOOL" extract_public_key --key "$TMP/key4096.pem" --output "$TMP/aosp_pkmd4096.bin"
cmp "$TMP/rust_pkmd4096.bin" "$TMP/aosp_pkmd4096.bin"
echo "PASS: 1032-byte RSA-4096 avb_pkmd.bin byte-for-byte identical!"

echo "=== 2. Rust add-hash-footer -> verify with official avbtool.py ==="
dd if=/dev/urandom of="$TMP/init_boot.img" bs=4096 count=64 status=none
"$CORE_BIN" avb add-hash-footer \
  --image "$TMP/init_boot.img" \
  --partition-name init_boot \
  --partition-size 1048576 \
  --key "$TMP/key.pem" \
  --prop "com.android.build.init_boot.os_version:14"
python3 "$AVBTOOL" verify_image --image "$TMP/init_boot.img" --key "$TMP/key.pem"
echo "PASS: official avbtool.py verified Rust hash footer!"

echo "=== 3. Rust add-hashtree-footer (multi-level 2 MiB) -> verify with official avbtool.py ==="
dd if=/dev/urandom of="$TMP/system.img" bs=4096 count=512 status=none
"$CORE_BIN" avb add-hashtree-footer \
  --image "$TMP/system.img" \
  --partition-name system \
  --key "$TMP/key.pem"
python3 "$AVBTOOL" verify_image --image "$TMP/system.img" --key "$TMP/key.pem"
echo "PASS: official avbtool.py verified Rust multi-level Merkle hashtree footer!"

echo "=== 4. Official avbtool.py add_hashtree_footer -> verify with Rust avbforge-core ==="
dd if=/dev/urandom of="$TMP/vendor.img" bs=4096 count=512 status=none
python3 "$AVBTOOL" add_hashtree_footer \
  --image "$TMP/vendor.img" \
  --partition_name vendor \
  --hash_algorithm sha256 \
  --algorithm SHA256_RSA2048 \
  --key "$TMP/key.pem" \
  --do_not_generate_fec
"$CORE_BIN" avb verify --image "$TMP/vendor.img" --expected-pubkey "$TMP/rust_pkmd.bin"
echo "PASS: Rust verified official avbtool.py hashtree footer!"

echo "=== ALL BIDIRECTIONAL CROSS-VERIFICATION TESTS PASSED ==="
