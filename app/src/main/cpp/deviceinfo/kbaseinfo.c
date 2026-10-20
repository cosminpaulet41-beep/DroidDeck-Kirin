#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <unistd.h>

// Arm's mali_kbase uAPI (the parts every userspace driver issues first). The JM and CSF kernels
// share the type and the SET_FLAGS / GET_GPUPROPS numbers; only the handshake number differs.
#define KBASE_IOCTL_TYPE 0x80
struct kbase_ioctl_version_check { uint16_t major; uint16_t minor; };
struct kbase_ioctl_set_flags { uint32_t create_flags; };
struct kbase_ioctl_get_gpuprops { uint64_t buffer; uint32_t size; uint32_t flags; };
#define KBASE_IOCTL_VERSION_CHECK_JM _IOWR(KBASE_IOCTL_TYPE, 0, struct kbase_ioctl_version_check)
#define KBASE_IOCTL_VERSION_CHECK_CSF _IOWR(KBASE_IOCTL_TYPE, 52, struct kbase_ioctl_version_check)
#define KBASE_IOCTL_SET_FLAGS _IOW(KBASE_IOCTL_TYPE, 1, struct kbase_ioctl_set_flags)
#define KBASE_IOCTL_GET_GPUPROPS _IOW(KBASE_IOCTL_TYPE, 3, struct kbase_ioctl_get_gpuprops)

#define LINE(...) do { if (used < sizeof(out)) used += snprintf(out + used, sizeof(out) - used, __VA_ARGS__); } while (0)

static char out[8192];
static size_t used;

static void access_line(const char *key, const char *path) {
    struct stat st;
    if (stat(path, &st) != 0) { LINE("%s=missing (%s)\n", key, strerror(errno)); return; }
    LINE("%s=mode %04o uid %u gid %u read %s write %s\n", key, (unsigned) (st.st_mode & 07777),
         (unsigned) st.st_uid, (unsigned) st.st_gid,
         access(path, R_OK) == 0 ? "yes" : "no", access(path, W_OK) == 0 ? "yes" : "no");
}

static const char *find_node(void) {
    static char path[32];
    for (int i = 0; i < 4; i++) {
        snprintf(path, sizeof(path), "/dev/mali%d", i);
        if (access(path, F_OK) == 0) return path;
    }
    return NULL;
}

// The handshake answers with the kernel's own version when asked for major 0.
static int handshake(int fd, const char **flavour, struct kbase_ioctl_version_check *v) {
    memset(v, 0, sizeof(*v));
    if (ioctl(fd, KBASE_IOCTL_VERSION_CHECK_CSF, v) == 0) { *flavour = "CSF"; return 0; }
    int csf_errno = errno;
    memset(v, 0, sizeof(*v));
    if (ioctl(fd, KBASE_IOCTL_VERSION_CHECK_JM, v) == 0) { *flavour = "JM"; return 0; }
    LINE("kbase.handshake_error=CSF %s, JM %s\n", strerror(csf_errno), strerror(errno));
    return -1;
}

static void dump_props(int fd) {
    struct kbase_ioctl_get_gpuprops q = {0};
    int size = ioctl(fd, KBASE_IOCTL_GET_GPUPROPS, &q);
    if (size <= 0) { LINE("kbase.gpuprops_error=%s\n", size < 0 ? strerror(errno) : "empty"); return; }
    uint8_t *buf = calloc(1, (size_t) size);
    if (!buf) return;
    q.buffer = (uint64_t) (uintptr_t) buf;
    q.size = (uint32_t) size;
    int got = ioctl(fd, KBASE_IOCTL_GET_GPUPROPS, &q);
    if (got <= 0) { LINE("kbase.gpuprops_error=%s\n", got < 0 ? strerror(errno) : "empty"); free(buf); return; }
    LINE("kbase.gpuprops_bytes=%d\n", got);
    LINE("kbase.props=");
    int off = 0, n = 0;
    while (off + 4 <= got) {
        uint32_t key;
        memcpy(&key, buf + off, 4);
        off += 4;
        int bytes = 1 << (key & 3);
        if (off + bytes > got) break;
        uint64_t value = 0;
        memcpy(&value, buf + off, (size_t) bytes);
        off += bytes;
        LINE("%s%u:%llx", n++ ? "," : "", key >> 2, (unsigned long long) value);
    }
    LINE("\n");
    free(buf);
}

JNIEXPORT jstring JNICALL
Java_com_droiddeck_launcher_gpu_KbaseInfo_nativeQuery(JNIEnv *env, jclass clazz) {
    used = 0;
    out[0] = 0;
    const char *node = find_node();
    if (!node) {
        LINE("kbase.node=none\n");
    } else {
        LINE("kbase.node=%s\n", node);
        access_line("kbase.node_access", node);
        int fd = open(node, O_RDWR | O_CLOEXEC);
        if (fd < 0) {
            LINE("kbase.open=failed: %s\n", strerror(errno));
        } else {
            LINE("kbase.open=ok\n");
            const char *flavour = "?";
            struct kbase_ioctl_version_check v;
            if (handshake(fd, &flavour, &v) == 0) {
                LINE("kbase.interface=%s\nkbase.uapi=%u.%u\n", flavour, v.major, v.minor);
                struct kbase_ioctl_set_flags flags = {0};
                if (ioctl(fd, KBASE_IOCTL_SET_FLAGS, &flags) != 0) LINE("kbase.context=failed: %s\n", strerror(errno));
                else LINE("kbase.context=ok\n");
                dump_props(fd);
            }
            close(fd);
        }
    }
    access_line("dri", "/dev/dri");
    access_line("dma_heap_system", "/dev/dma_heap/system");
    access_line("ion", "/dev/ion");
    char ctx[128] = {0};
    int a = open("/proc/self/attr/current", O_RDONLY | O_CLOEXEC);
    if (a >= 0) {
        ssize_t r = read(a, ctx, sizeof(ctx) - 1);
        close(a);
        if (r > 0) { ctx[strcspn(ctx, "\n")] = 0; LINE("selinux=%s\n", ctx); }
    }
    return (*env)->NewStringUTF(env, out);
}
