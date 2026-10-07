/* Android compatibility for the Mercury HF modem (GPL-3.0-or-later).
 * File-backed replacement for POSIX shm_open/shm_unlink; see android_port.h. */
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

#include "android_port.h"

static int shm_path(const char *name, char *out, size_t len)
{
    const char *dir = getenv("MERCURY_SHM_DIR");
    if (dir == NULL || dir[0] == '\0')
        dir = "/data/local/tmp";
    while (*name == '/')
        name++;
    int n = snprintf(out, len, "%s/mercury-shm-%s", dir, name);
    if (n < 0 || (size_t) n >= len) {
        errno = ENAMETOOLONG;
        return -1;
    }
    return 0;
}

int shm_open(const char *name, int oflag, mode_t mode)
{
    char path[PATH_MAX];
    if (shm_path(name, path, sizeof(path)) != 0)
        return -1;
    return open(path, oflag | O_CLOEXEC, mode);
}

int shm_unlink(const char *name)
{
    char path[PATH_MAX];
    if (shm_path(name, path, sizeof(path)) != 0)
        return -1;
    return unlink(path);
}
