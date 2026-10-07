/* Android compatibility for the Mercury HF modem (GPL-3.0-or-later).
 * Force-included into every Mercury source file by CMakeLists.txt.
 * Fills in what Android's C library (bionic) does not provide. */
#pragma once
#ifdef __ANDROID__

#include <math.h>
#include <sys/types.h>

/* bionic has no exp10f; codec2/FreeDV uses it for dB conversions. */
static inline float mercury_exp10f(float x) { return powf(10.0f, x); }
#define exp10f mercury_exp10f

/* bionic has no POSIX named shared memory. Mercury uses it only to share the
 * sound buffers between its own threads, so a plain file mapped with mmap
 * works the same. Files go in $MERCURY_SHM_DIR (default /data/local/tmp). */
int shm_open(const char *name, int oflag, mode_t mode);
int shm_unlink(const char *name);

#endif
