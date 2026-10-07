/* Two ffbase ring-buffer readers that ffaudio's aaudio.c calls but the ffbase
 * copy bundled with Mercury v1.9.17 does not have (GPL-3.0-or-later).
 * Force-included into aaudio.c only, by CMakeLists.txt. */
#pragma once
#include <ffbase/ring.h>

static inline ffsize ffring_ext_used(ffring *b)
{
    ffsize rh = FFINT_READONCE(b->rhead);
    ffcpu_fence_acquire(); /* read 'rhead' before 'wtail' */
    return FFINT_READONCE(b->wtail) - rh;
}

/* Lock all data currently in the ring, but only if there are at least 'n'
 * bytes; otherwise lock nothing (d1 and d2 empty). */
static inline ffring_head ffring_read_min_begin(ffring *b, ffsize n, ffstr *d1, ffstr *d2, ffsize *used)
{
    ffsize avail = ffring_ext_used(b);
    return ffring_read_all_begin(b, (avail >= n) ? avail : 0, d1, d2, used);
}

/* Lock up to 'n' bytes: as many as are in the ring, at most 'n'. */
static inline ffring_head ffring_read_max_begin(ffring *b, ffsize n, ffstr *d1, ffstr *d2, ffsize *used)
{
    ffsize avail = ffring_ext_used(b);
    return ffring_read_all_begin(b, (avail < n) ? avail : n, d1, d2, used);
}
