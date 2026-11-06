/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_QUALIFICATION_PAGEMAP_H
#define OPENGPU_QUALIFICATION_PAGEMAP_H

/* Explicit qualification metadata only. mapping is used as an integer virtual
 * address for /proc/self/pagemap; this code never loads or stores BO data. */
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdint.h>
#include <stdio.h>
#include <unistd.h>

struct opengpu_qual_pfn_run { uint64_t page, pages, physical; };
typedef int (*opengpu_qual_pfn_read)(void *, uint64_t, uint64_t *, unsigned);
typedef int (*opengpu_qual_pfn_emit)(void *, const struct opengpu_qual_pfn_run *);

static inline int opengpu_qual_pagemap_walk(uintptr_t mapping, uint64_t bytes,
    uint64_t ram_base, uint64_t ram_end, opengpu_qual_pfn_read read_entries, void *read_context,
    opengpu_qual_pfn_emit emit, void *emit_context)
{
    uint64_t entries[512], pages=bytes/4096, first=(uint64_t)mapping/4096;
    struct opengpu_qual_pfn_run run={0};
    if (!bytes || (bytes&4095) || (mapping&4095) || bytes>UINTPTR_MAX-mapping ||
        ram_base>=ram_end || ram_end>(UINT64_C(1)<<32) || ((ram_base|ram_end)&4095) ||
        first>INT64_MAX/8 || pages>(uint64_t)INT64_MAX/8-first || !read_entries || !emit)
        return -EINVAL;
    for (uint64_t page=0;page<pages;) {
        unsigned count=pages-page>512?512:(unsigned)(pages-page);
        int rc=read_entries(read_context,first+page,entries,count);
        if (rc) return rc;
        for (unsigned n=0;n<count;n++,page++) {
            uint64_t p=entries[n], pfn=p&((UINT64_C(1)<<55)-1);
            /* PFNs hidden without CAP_SYS_ADMIN must not become physical0.
             * Swapped/missing/unknown pages cannot prove GPU allocation. */
            if (!(p&(UINT64_C(1)<<63)) || (p&(UINT64_C(1)<<62)) || !pfn) return -ENODATA;
            if (pfn>=(UINT64_C(1)<<20)) return -ERANGE;
            uint64_t physical=pfn<<12;
            if (physical<ram_base || physical>=ram_end || 4096>ram_end-physical) return -ERANGE;
            if (run.pages && physical==run.physical+run.pages*4096) run.pages++;
            else {
                if (run.pages && (rc=emit(emit_context,&run))) return rc;
                run=(struct opengpu_qual_pfn_run){page,1,physical};
            }
        }
    }
    return run.pages?emit(emit_context,&run):-ENODATA;
}

static inline int opengpu_qual_pagemap_pread(void *context,uint64_t page,uint64_t *entries,unsigned count)
{
    int fd=*(const int *)context;
    if (page>(uint64_t)INT64_MAX/8 || count>512 || (uint64_t)count>(uint64_t)INT64_MAX/8-page)
        return -EOVERFLOW;
    size_t bytes=count*sizeof(*entries), done=0;
    while (done<bytes) {
        ssize_t n=pread(fd,(char *)entries+done,bytes-done,(off_t)(page*8+done));
        if (n<0) { if (errno==EINTR) continue; return -errno; }
        if (!n) return -EIO;
        done+=(size_t)n;
    }
    return 0;
}

struct opengpu_qual_pfn_output { FILE *stream; const char *kind; unsigned binding,handle; };
static inline int opengpu_qual_pagemap_print(void *context,const struct opengpu_qual_pfn_run *run)
{
    const struct opengpu_qual_pfn_output *o=context;
    return fprintf(o->stream,"OPENGPU_QUAL_PFN kind=%s binding=%u handle=%u page=%" PRIu64
        " pages=%" PRIu64 " physical=0x%" PRIx64 "\n",o->kind,o->binding,o->handle,
        run->page,run->pages,run->physical)<0?-EIO:0;
}

static inline int opengpu_qual_pagemap_report(const void *mapping,uint64_t bytes,
    uint64_t ram_base,uint64_t ram_end,FILE *stream,const char *kind,unsigned binding,unsigned handle)
{
    if (sysconf(_SC_PAGESIZE)!=4096) return -ENOTSUP;
    int fd=open("/proc/self/pagemap",O_RDONLY|O_CLOEXEC);
    if (fd<0) return -errno;
    struct opengpu_qual_pfn_output out={stream,kind,binding,handle};
    int rc=opengpu_qual_pagemap_walk((uintptr_t)mapping,bytes,ram_base,ram_end,
        opengpu_qual_pagemap_pread,&fd,opengpu_qual_pagemap_print,&out);
    int saved=errno;
    if (close(fd) && !rc) rc=-errno;
    errno=saved;
    return rc;
}
#endif
