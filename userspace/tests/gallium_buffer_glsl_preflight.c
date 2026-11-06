/* SPDX-License-Identifier: MIT */
/* Separate CPU word decoder: never linked into the DRM application. The
 * resource callbacks are arithmetic-only, not a trap/drain/coherence model. */
#include <assert.h>
#include <setjmp.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/mman.h>
#include "../examples/gallium_buffer_glsl_contract.h"
#include "cs_word_vm.h"
#include "opengpu_texture_image_format_abi.h"
#include "opengpu_kernarg_va.h"
static struct gb_model expected;
static uint32_t *data[GB_BOS];
static jmp_buf fault;
static unsigned texture_reads[32],texture_queries[32],image_reads[4],image_writes[4],image_queries[4],faults;
static uint32_t texture(void *unused,unsigned unit,uint32_t index,unsigned component,int query)
{
    (void)unused; assert(unit<32 && component<4); unsigned bo=expected.texture[unit],bytes=gb_bytes(bo);
    if(query) { assert(!component); texture_queries[unit]++; return bytes/4; }
    if(index>=bytes/4) { faults++; longjmp(fault,1); }
    texture_reads[unit]++; return !component?data[bo][index]:component==3?1u:0u;
}
static void image(void *unused,unsigned unit,unsigned controls,uint32_t index,uint32_t rgba[4],int write,int query)
{
    (void)unused; assert(unit<4 && (controls&7)==5);
    unsigned bo=expected.image[unit],offset=expected.image_offset[unit],bytes=gb_bytes(bo)-offset;
    if(query) { image_queries[unit]++; rgba[0]=bytes/4; return; }
    assert(index<bytes/4 && controls==(5u|1u<<3|1u<<5|OPENGPU_TEXTURE_FORMAT_R32UI<<6));
    uint32_t *p=data[bo]+offset/4+index;
    if(write) { image_writes[unit]++; *p=rgba[0]; }
    else { image_reads[unit]++; rgba[0]=*p; rgba[3]=1; }
}
static FILE *input(const char *directory,unsigned n,const char *suffix)
{
    char path[4096]; int length=snprintf(path,sizeof(path),"%s/%s.%s",directory,gb_names[n],suffix);
    assert(length>0 && (size_t)length<sizeof(path)); FILE *f=fopen(path,"rb"); assert(f); return f;
}
static void initialize_data(unsigned first,unsigned end)
{
    for(unsigned bo=first;bo<end;bo++) for(unsigned tail=0;tail<2;tail++)
        memcpy(data[bo]+gb_region_offset(bo,tail)/4,&expected.words[bo][tail?GB_PREFIX_BYTES/4:0],gb_region_bytes(tail));
}
static void check(unsigned n)
{
    for(unsigned bo=0;bo<expected.count;bo++) for(unsigned tail=0;tail<2;tail++)
        for(unsigned w=0;w<gb_region_bytes(tail)/4;w++) {
            unsigned byte=gb_region_offset(bo,tail)+w*4; uint32_t want=gb_read(&expected,bo,byte),got=data[bo][byte/4];
            if(got!=want) fprintf(stderr,"BUFFER_CPU_MISMATCH epoch=%u program=%s bo=%u byte=%u got=%08x want=%08x\n",expected.epoch,gb_names[n],bo,byte,got,want);
            assert(got==want);
        }
}
static void execute(const char *directory,unsigned n)
{
    uint32_t code[512],uniforms[13][4096]; unsigned words,reads,writes,tex,ir,iw,iq;
    FILE *f=input(directory,n,"words"); assert(fread(code,1,sizeof(code),f)==sizeof(code) && fgetc(f)==EOF && !fclose(f));
    f=input(directory,n,"resources");
    assert(fscanf(f,"words=%u reads=%u writes=%u textures=%u imageReads=%u imageWrites=%u imageQueries=%u\n",
        &words,&reads,&writes,&tex,&ir,&iw,&iq)==7 && words<=512);
    struct cs_vm_view memory[4]; unsigned seen=0;
    for(unsigned b=0;b<4;b++) {
        unsigned native,binding; assert(fscanf(f,"native.%u.binding=%u\n",&native,&binding)==2 && native==b && binding<4 && !(seen&(1u<<binding)));
        seen|=1u<<binding; memory[b]=(struct cs_vm_view){data[expected.ssbo[binding]],gb_bytes(expected.ssbo[binding]),
            ((reads>>b)&1u)|(((writes>>b)&1u)<<1)};
    }
    assert(fgetc(f)==EOF && !fclose(f));
    for(unsigned b=0;b<13;b++) for(unsigned w=0;w<(b?4096u:1024u);w++) uniforms[b][w]=gb_uniform(expected.epoch,b,w);
    struct cs_vm_view u[13]={{uniforms[0],4096,1}};
    f=input(directory,n,"reflection"); char line[256]; unsigned bindings=0;
    while(fgets(line,sizeof(line),f)) {
        unsigned local,global;
        if(sscanf(line,"UBO.%u.binding=%u",&local,&global)==2) {
            assert(local<12 && global>=24 && global<36 && !(bindings&(1u<<local))); bindings|=1u<<local;
            u[local+1]=(struct cs_vm_view){uniforms[global-23],16384,1};
        }
    }
    assert(!ferror(f) && !fclose(f) && bindings==(n?0u:4095u));
    memset(texture_reads,0,sizeof(texture_reads)); memset(texture_queries,0,sizeof(texture_queries));
    memset(image_reads,0,sizeof(image_reads)); memset(image_writes,0,sizeof(image_writes)); memset(image_queries,0,sizeof(image_queries)); faults=0;
    struct cs_vm_uniform_trace trace={0}; const struct cs_vm_resources resources={NULL,texture,image}; uint32_t group[3]={0};
    if(!setjmp(fault)) cs_vm_group_resources_checked(code,words,gb_grid,group,gb_local,memory,u,&trace,&resources);
    assert(!trace.fault_warps && faults==(n==5?1u:0u));
    for(unsigned t=0;t<32;t++) {
        assert(texture_reads[t]==((tex&(UINT32_C(1)<<t)) && n!=5?512u:0u));
        assert(texture_queries[t]==(n>=1 && n<=3 && t==gb_texture_query[n-1]?128u:n==5 && t==31?16u:0u));
    }
    for(unsigned b=0;b<4;b++) assert(image_reads[b]==(n==4?128u:0u) && image_writes[b]==(n==4?128u:0u) && image_queries[b]==(n==4?128u:0u));
    if(n!=5) gb_reference(&expected,n);
    check(n);
    printf("BUFFER_CPU_WORD_ORACLE epoch=%u program=%s words=%u BOs=%u checkedWords=%u uniformReads=%u actualWordDecode=PASS GPU=UNRUN\n",
        expected.epoch,gb_names[n],words,expected.count,expected.count*GB_REGION_WORDS,trace.reads);
}
static void plan_mapping(unsigned char l1[1024],const struct opengpu_kernarg_va_plan *p)
{
    assert(!(p->va_page&4095) && !(p->span&4095) && p->span &&
        p->va_page<UINT64_C(0x100000000) && p->span<=UINT64_C(0x100000000)-p->va_page);
    for(uint64_t page=p->va_page;page<p->va_page+p->span;page+=4096) l1[page>>22]=1;
}
static void layout_plan(void)
{
    unsigned char l1[1024]={0}; struct opengpu_kernarg_va_plan p;
    /* Arithmetic only: the example PA is not an observed allocation/PFN. */
    for(unsigned b=0;b<4;b++) {
        assert(!opengpu_storage_va_plan(0x80000000u,GB_VIEW_BYTES,b,4096,&p)); plan_mapping(l1,&p);
        assert(!opengpu_image_backing_va_plan(0x80000000u,GB_VIEW_BYTES,b,4096,&p)); plan_mapping(l1,&p);
    }
    for(unsigned b=0;b<32;b++) {
        assert(!opengpu_texture_va_plan(0x80000000u,GB_TEXTURE_BYTES,3+b,4096,&p)); plan_mapping(l1,&p);
    }
    assert(!opengpu_kernarg_va_plan(0x80000000u,64,2,4096,&p)); plan_mapping(l1,&p);
    assert(!opengpu_code_va_plan(0x80000000u,2048,1,4096,&p)); plan_mapping(l1,&p);
    /* Command, full32x16-slot texture arena, storage, images, GUM1 snapshot.
     * Sources of UBO copies are not shader-mapped at their public PFNs. */
    const unsigned slots[]={1,2,3,4,6},sizes[]={4096,128+2304*32,256,1024,204800};
    for(unsigned i=0;i<5;i++) {
        assert(!opengpu_command_va_plan(0x80000000u,sizes[i],slots[i],4096,&p)); plan_mapping(l1,&p);
    }
    unsigned tables=0; for(unsigned i=0;i<1024;i++) tables+=l1[i];
    assert(tables<=384); /* current OPENGPU_VM_MAX_TABLES; on-demand, per VM */
    uint64_t baseline=8*UINT64_C(134217728)+32*UINT64_C(4194304),peak=baseline+GB_VIEW_BYTES;
    assert(baseline==UINT64_C(1207959552) && peak==UINT64_C(1342177280));
    assert(UINT64_C(0x40000000)+3*UINT64_C(1073741824)==UINT64_C(0x100000000));
    assert(UINT64_C(0x80000000)+1536*UINT64_C(1048576)==UINT64_C(0xe0000000));
    printf("BUFFER_CPU_LAYOUT pageAlignedL1=%u maxPerVM=384 baselineBOBytes=%llu peakBOBytes=%llu futureCMA1536MiBMarginBeforeMetadata=256MiB actualPFN_PTE_DTB=UNRUN\n",
        tables,(unsigned long long)baseline,(unsigned long long)peak);
}
int main(int argc,char **argv)
{
    assert(argc==2); layout_plan(); (void)cs_vm_group; (void)cs_vm_group_uniforms_checked;
    for(unsigned bo=0;bo<GB_BOS;bo++) {
        data[bo]=mmap(NULL,gb_bytes(bo),PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
        assert(data[bo]!=MAP_FAILED);
    }
    for(unsigned epoch=1;epoch<=2;epoch++) {
        gb_initialize(&expected,epoch); initialize_data(0,40); execute(argv[1],0);
        if(epoch==2) { gb_rebind(&expected); initialize_data(40,41); }
        for(unsigned n=1;n<5;n++) execute(argv[1],n);
        if(epoch==1) execute(argv[1],5);
    }
    for(unsigned bo=0;bo<GB_BOS;bo++) assert(!munmap(data[bo],gb_bytes(bo)));
    puts("BUFFER_CPU_PREFLIGHT full32Textures=PASS full4SSBO=PASS full4IndependentImages=PASS full13CB=PASS allSparseWordGuards=PASS faultNoInvalidDereference=PASS hardwareTrapDrainVisibility=UNRUN GPU=UNRUN");
    return 0;
}
