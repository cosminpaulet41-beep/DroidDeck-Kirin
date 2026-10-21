#!/usr/bin/env python3
"""Adds an ION fallback to PanVK-kbase's shareable-BO allocator.

PanVK over mali_kbase allocates every BO it may export (WSI images, gamescope's
textures, placed maps) from /dev/dma_heap/system and imports the dma-buf into
kbase. Huawei's Kirin kernels (5.10, EMUI 14) have no dma-heap but keep the
new-ABI /dev/ion (mode 0664, so apps open it read-only, as libion does). Without
this, vkGetMemoryFdKHR fails and gamescope aborts.

Usage: ion_fallback.py <mesa>/src/panfrost/lib/kmod/kbase_kmod.c
"""
import re
import sys

path = sys.argv[1]
src = open(path, encoding="utf-8").read()


def once(pattern, repl, text, what):
    new, n = re.subn(pattern, repl, text, count=1, flags=re.S)
    if n != 1:
        sys.exit(f"ion_fallback: anchor not found: {what}")
    return new


if "heap_is_ion" in src:
    sys.exit("ion_fallback: already applied")

src = once(r'(#include "kbase_kmod\.h"\n)', r'''\1
/* ION new ABI (4.12+), used when the kernel has no dma-heap. */
struct kbase_ion_alloc {
   uint64_t len;
   uint32_t heap_id_mask;
   uint32_t flags;
   uint32_t fd;
   uint32_t unused;
};
struct kbase_ion_heap_data {
   char name[32];
   uint32_t type;
   uint32_t heap_id;
   uint32_t reserved0;
   uint32_t reserved1;
   uint32_t reserved2;
};
struct kbase_ion_heap_query {
   uint32_t cnt;
   uint32_t reserved0;
   uint64_t heaps;
   uint32_t reserved1;
   uint32_t reserved2;
};
#define KBASE_ION_IOC_ALLOC      _IOWR('I', 0, struct kbase_ion_alloc)
#define KBASE_ION_IOC_HEAP_QUERY _IOWR('I', 8, struct kbase_ion_heap_query)
''', src, "kbase_kmod.h include")

src = once(r'(\n(\s*)int dma_heap_fd;\n)', r'''\1\2bool heap_is_ion;
\2uint32_t ion_heap_mask;
''', src, "dma_heap_fd field")

src = once(r'(mesa_logd\("kbase: dma-heap unavailable at %s: %s", dma_heap,\s*strerror\(errno\)\);\n)', r'''\1
   if (kbase_dev->dma_heap_fd < 0 && !getenv("PANVK_KBASE_NO_ION")) {
      int ion = open("/dev/ion", O_RDONLY | O_CLOEXEC);
      if (ion >= 0) {
         struct kbase_ion_heap_data heaps[32];
         memset(heaps, 0, sizeof(heaps));
         struct kbase_ion_heap_query q = {
            .cnt = 32,
            .heaps = (uintptr_t)heaps,
         };
         if (ioctl(ion, KBASE_ION_IOC_HEAP_QUERY, &q) == 0) {
            for (uint32_t i = 0; i < q.cnt && i < 32; i++) {
               mesa_logi("kbase: ION heap %u '%.32s' type %u", heaps[i].heap_id,
                         heaps[i].name, heaps[i].type);
               if (heaps[i].type == 0 /* ION_HEAP_TYPE_SYSTEM */ &&
                   !kbase_dev->ion_heap_mask)
                  kbase_dev->ion_heap_mask = 1u << heaps[i].heap_id;
            }
         } else {
            mesa_logi("kbase: ION heap query failed: %s", strerror(errno));
         }
         const char *mask = getenv("PANVK_KBASE_ION_HEAP_MASK");
         if (mask && mask[0])
            kbase_dev->ion_heap_mask = (uint32_t)strtoul(mask, NULL, 0);
         if (kbase_dev->ion_heap_mask) {
            kbase_dev->dma_heap_fd = ion;
            kbase_dev->heap_is_ion = true;
            mesa_logi("kbase: no dma-heap, using ION heap mask 0x%x",
                      kbase_dev->ion_heap_mask);
         } else {
            mesa_logi("kbase: no usable ION system heap");
            close(ion);
         }
      } else {
         mesa_logi("kbase: /dev/ion unavailable: %s", strerror(errno));
      }
   }
''', src, "dma-heap open")

src = once(r'if \(ioctl\(kbase_dev->dma_heap_fd, DMA_HEAP_IOCTL_ALLOC, &alloc\)\) \{', r'''if (kbase_dev->heap_is_ion) {
      struct kbase_ion_alloc ia = {
         .len = alloc.len,
         .heap_id_mask = kbase_dev->ion_heap_mask,
         .flags = 1, /* ION_FLAG_CACHED, as the system dma-heap */
      };
      if (ioctl(kbase_dev->dma_heap_fd, KBASE_ION_IOC_ALLOC, &ia)) {
         mesa_loge("kbase: ION_IOC_ALLOC failed: %s", strerror(errno));
         return NULL;
      }
      alloc.fd = ia.fd;
   } else if (ioctl(kbase_dev->dma_heap_fd, DMA_HEAP_IOCTL_ALLOC, &alloc)) {''', src, "DMA_HEAP_IOCTL_ALLOC")

for header in ("#include <string.h>", "#include <stdlib.h>"):
    if header not in src:
        src = header + "\n" + src

open(path, "w", encoding="utf-8").write(src)
print("ion_fallback: applied to", path)
