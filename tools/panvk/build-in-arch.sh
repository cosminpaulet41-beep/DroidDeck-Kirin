#!/usr/bin/bash
# Builds the glibc PanVK-kbase Vulkan driver for the Linux runtime (Arch Linux ARM, as the
# runtime is) from panvk-kbase-android at a pinned commit, plus tools/panvk/ion_fallback.py.
# Output: panvk-kirin.zip (libvulkan_panfrost.so, panfrost_icd.aarch64.json, meta.json),
# importable under Components > GPU drivers > Import .zip.
set -euxo pipefail
WORK=/work
cd "$WORK"
. tools/panvk/release.env
grep -q '^DisableSandbox' /etc/pacman.conf || sed -i 's/^\[options\]/[options]\nDisableSandbox/' /etc/pacman.conf
{ for m in https://ca.us.mirror.archlinuxarm.org https://fl.us.mirror.archlinuxarm.org https://de3.mirror.archlinuxarm.org https://nl.mirror.archlinuxarm.org; do echo "Server = $m/\$arch/\$repo"; done; cat /etc/pacman.d/mirrorlist; } > /etc/pacman.d/mirrorlist.new
mv /etc/pacman.d/mirrorlist.new /etc/pacman.d/mirrorlist
pacman -Syu --noconfirm --needed git python python-mako python-packaging python-yaml python-pycparser meson ninja \
  clang llvm libclc spirv-llvm-translator spirv-tools spirv-headers glslang libdrm wayland wayland-protocols \
  libx11 libxcb libxext libxrandr libxshmfence libxxf86vm libxfixes expat zlib cmake bison flex pkgconf zip binutils

B=/tmp/panvk
rm -rf "$B" && mkdir -p "$B"
git clone -q "$PANVK_REPO" "$B/src"
git -C "$B/src" checkout -q "$PANVK_COMMIT"
MESA_REPO=$(python -c "import json;print(json.load(open('$B/src/sources.lock'))['mesaRepo'])")
MESA_COMMIT=$(python -c "import json;print(json.load(open('$B/src/sources.lock'))['mesaCommit'])")
mkdir -p "$B/mesa"
git -C "$B/mesa" init -q
git -C "$B/mesa" remote add origin "$MESA_REPO"
for attempt in 1 2 3; do git -C "$B/mesa" fetch -q --depth 1 origin "$MESA_COMMIT" && break; sleep 30; done
git -C "$B/mesa" checkout -q --detach FETCH_HEAD
test "$(git -C "$B/mesa" rev-parse HEAD)" = "$MESA_COMMIT"

sh "$B/src/scripts/apply-patches.sh" --profile "$PANVK_PROFILE" --mesa "$B/mesa"
python tools/panvk/ion_fallback.py "$B/mesa/src/panfrost/lib/kmod/kbase_kmod.c"

meson setup "$B/build" "$B/mesa" \
  --native-file "$B/src/meson/linux-aarch64-native.ini" \
  -Dbuildtype=release -Dplatforms=x11,wayland -Dgallium-drivers= -Dvulkan-drivers=panfrost \
  -Dpanfrost-kmds=kbase -Degl=disabled -Dgles1=disabled -Dgles2=disabled -Dopengl=false \
  -Dglx=disabled -Dgbm=disabled -Dzstd=disabled -Dlibunwind=disabled
ninja -C "$B/build"

OUT="$WORK/panvk-out"
rm -rf "$OUT" && mkdir -p "$OUT"
cp "$(find "$B/build" -name libvulkan_panfrost.so | head -1)" "$OUT/libvulkan_panfrost.so"
strip --strip-unneeded "$OUT/libvulkan_panfrost.so"
cp "$(find "$B/build" -name 'panfrost_icd.*.json' | head -1)" "$OUT/panfrost_icd.aarch64.json"
readelf -d "$OUT/libvulkan_panfrost.so" | grep NEEDED
MIN_GLIBC=$(readelf -V "$OUT/libvulkan_panfrost.so" | grep -o 'GLIBC_[0-9.]*' | sed 's/GLIBC_//' | sort -V | tail -1)
strings "$OUT/libvulkan_panfrost.so" | grep -q 'no dma-heap, using ION heap mask'
python - "$OUT" "$MIN_GLIBC" <<'PY'
import json, os, sys, hashlib
out, glibc = sys.argv[1], sys.argv[2]
env = {k: v.strip('"') for k, v in (l.strip().split('=', 1) for l in open('tools/panvk/release.env') if '=' in l)}
so = os.path.join(out, 'libvulkan_panfrost.so')
json.dump({
    "schemaVersion": 1,
    "kind": "linux-vulkan-icd",
    "name": env["PANVK_NAME"],
    "driverVersion": env["PANVK_BASE"] + "+ion",
    "libraryName": "libvulkan_panfrost.so",
    "minGlibc": glibc,
    "abi": "linux-aarch64-glibc",
    "backend": "panvk-kbase",
    "source": env["PANVK_REPO"] + "@" + env["PANVK_COMMIT"] + " + DroidDeck-Kirin tools/panvk/ion_fallback.py",
    "sha256": hashlib.sha256(open(so, 'rb').read()).hexdigest(),
}, open(os.path.join(out, 'meta.json'), 'w'), indent=2)
PY
cat "$OUT/meta.json"
(cd "$OUT" && zip -9 "$WORK/panvk-kirin.zip" libvulkan_panfrost.so panfrost_icd.aarch64.json meta.json)
