/* SPDX-License-Identifier: MIT */
#define _GNU_SOURCE
#include "../../userspace/examples/qualification_pagemap.h"
#include <assert.h>
#include <stdlib.h>
#include <string.h>
static unsigned checks;
struct input { uint64_t first,pfn,bad_page,bad_value; unsigned calls; int error; };
struct output { unsigned calls; uint64_t pages,physical; int error; };
static int read_entries(void *p,uint64_t page,uint64_t *entries,unsigned n) {
    struct input *in=p; assert(page>=in->first && n<=512); in->calls++;
    if(in->error) return in->error;
    for(unsigned i=0;i<n;i++) entries[i]=page+i==in->bad_page?in->bad_value:
        (UINT64_C(1)<<63)|(in->pfn+page+i-in->first);
    return 0;
}
static int emit(void *p,const struct opengpu_qual_pfn_run *r) {
    struct output *o=p; assert(r->page==o->pages && r->pages); o->calls++; o->pages+=r->pages; o->physical=r->physical;
    return o->error;
}
static int walk(uintptr_t address,uint64_t bytes,struct input *in,struct output *out) {
    return opengpu_qual_pagemap_walk(address,bytes,0x40000000,UINT64_C(1)<<32,read_entries,in,emit,out);
}
int main(void) {
    struct input in={.first=1,.pfn=0x80000,.bad_page=UINT64_MAX}; struct output out={0};
    /* 128MiB metadata, not BO contents: 64 small pagemap reads, one run. */
    assert(!walk(4096,128*1024*1024,&in,&out));
    assert(in.calls==64 && out.calls==1 && out.pages==32768 && out.physical==0x80000000); checks++;
    in.calls=0; in.bad_page=513; in.bad_value=(UINT64_C(1)<<63)|0x90000; out=(struct output){0};
    assert(!walk(4096,514*4096,&in,&out) && in.calls==2 && out.calls==3 && out.pages==514); checks++;
    const uint64_t invalid[]={0,UINT64_C(1)<<63,(UINT64_C(3)<<62)|0x80000,
        (UINT64_C(1)<<63)|0x100000,(UINT64_C(1)<<63)|0x3ffff};
    for(unsigned i=0;i<sizeof(invalid)/sizeof(invalid[0]);i++) {
        in.bad_page=1; in.bad_value=invalid[i]; out=(struct output){0};
        assert(walk(4096,4096,&in,&out)==(i<3?-ENODATA:-ERANGE) && !out.calls); checks++;
    }
    in.bad_page=UINT64_MAX; in.pfn=0xfffff; out=(struct output){0};
    assert(!walk(4096,4096,&in,&out) && out.physical==0xfffff000); checks++;
    out=(struct output){0}; assert(walk(4096,8192,&in,&out)==-ERANGE); checks++;
    in.pfn=0x80000; in.error=-EIO; out=(struct output){0}; assert(walk(4096,4096,&in,&out)==-EIO); in.error=0; checks++;
    out=(struct output){.error=-EPIPE}; assert(walk(4096,4096,&in,&out)==-EPIPE); checks++;
    const uintptr_t bad_addresses[]={1,UINTPTR_MAX-4095};
    for(unsigned i=0;i<2;i++) { out=(struct output){0}; assert(walk(bad_addresses[i],8192,&in,&out)==-EINVAL); checks++; }
    assert(walk(4096,4095,&in,&out)==-EINVAL && walk(4096,0,&in,&out)==-EINVAL); checks++;
    /* Real pread/short-read paths use only a tiny metadata file. The fake
     * mapping address is never dereferenced, including under ASan/UBSan. */
    FILE *file=tmpfile(); assert(file); int fd=fileno(file); uint64_t p=(UINT64_C(1)<<63)|0x80000;
    assert(pwrite(fd,&p,8,8)==8); out=(struct output){0};
    assert(!opengpu_qual_pagemap_walk(4096,4096,0x40000000,UINT64_C(1)<<32,opengpu_qual_pagemap_pread,&fd,emit,&out));
    assert(out.pages==1); out=(struct output){0};
    assert(opengpu_qual_pagemap_walk(4096,8192,0x40000000,UINT64_C(1)<<32,opengpu_qual_pagemap_pread,&fd,emit,&out)==-EIO);
    assert(opengpu_qual_pagemap_pread(&fd,UINT64_MAX,&p,1)==-EOVERFLOW); assert(!fclose(file)); checks++;
    puts("PAGEMAP_CPU PASS allPagesChecked=true metadataOnly=true hiddenPFN=failclosed guest=UNRUN");
    printf("PAGEMAP_CPU checks=%u\n",checks);
    return 0;
}
