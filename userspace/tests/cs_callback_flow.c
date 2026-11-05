/* SPDX-License-Identifier: MIT */
/* Actual Gallium callbacks -> private pipe -> lib ioctl with modeled OS/GEM
 * primitives. The independent word VM is CPU compiler evidence, not a GPU. */
#include <assert.h>
#include <errno.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <linux/sync_file.h>
#include <drm/drm.h>
#include <drm/drm_mode.h>
#include "pipe/p_context.h"
#include "pipe/p_screen.h"
#include "pipe/p_state.h"
#include "tgsi/tgsi_build.h"
#include "util/u_inlines.h"
#include "util/u_transfer.h"
#include "util/u_upload_mgr.h"
#include "opengpu_public.h"
#include "opengpu_internal.h"
#include "opengpu_cs_emit.h"
#include "opengpu_shader_validator.h"
#include "cs_word_vm.h"

#define N 512u
#define TOKENS 2048u
static struct bo { unsigned handle, maps, binds, jobs; uint64_t size; uint8_t *data; } bos[N];
static struct context { unsigned live; struct drm_opengpu_resource b[39]; } contexts[N];
static struct job {
    unsigned live, context, kind, refs[N];
    int status;
    struct drm_opengpu_compute_storage compute;
    struct drm_opengpu_fill fill;
} jobs[N];
static unsigned next_bo=1, next_context=1, next_sync=1, computes, waits, held_checks, fills;
static unsigned live_sync[N], exported[N], sync_status[N], semantic=1;
static uint64_t features=OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE;
static uint32_t limit_items=128, limit_axis=128;
static unsigned fail_request, fail_nth, fail_seen, wait_error, status_error, map_error;
static unsigned last_compute_context, canceled, rejected_programs;
static struct pipe_resource *held_resources[4];

static void maybe_free(unsigned h)
{
    struct bo *b=&bos[h];
    if (!b->handle && !b->maps && !b->binds && !b->jobs) { free(b->data); b->data=NULL; }
}
static void drop_binding(struct drm_opengpu_resource *b)
{
    if (b->handle) { unsigned h=b->handle; assert(bos[h].binds); bos[h].binds--; memset(b,0,sizeof(*b)); maybe_free(h); }
}
static void retain_job(struct job *j,unsigned h)
{
    assert(h && h<next_bo && bos[h].data);
    if (!j->refs[h]) { j->refs[h]=1; bos[h].jobs++; }
}
static void finish_job(unsigned s,int execute)
{
    struct job *j=&jobs[s];
    if (!j->live) return;
    if (!execute) canceled++;
    if (execute && !j->status) {
        if (j->kind==1 && semantic) {
            struct drm_opengpu_compute_storage *cmd=&j->compute;
            struct context *c=&contexts[j->context];
            struct cs_vm_view m[4]={{0}};
            struct drm_opengpu_resource *shader=&c->b[cmd->launch.shader_slot];
            for (unsigned i=0;i<4;i++) if (cmd->bindings[i].slot) {
                struct drm_opengpu_resource *v=&c->b[cmd->bindings[i].slot];
                m[i]=(struct cs_vm_view){(uint32_t *)(bos[v->handle].data+v->offset),v->size,cmd->bindings[i].access};
                assert(v->offset+v->size<=65536); /* bounded semantic fixture only */
            }
            assert(cmd->launch.grid[0]<=2 && cmd->launch.grid[1]<=2 && cmd->launch.grid[2]<=2);
            for (uint32_t z=0;z<cmd->launch.grid[2];z++) for(uint32_t y=0;y<cmd->launch.grid[1];y++)
                for(uint32_t x=0;x<cmd->launch.grid[0];x++) {
                    uint32_t group[3]={x,y,z};
                    cs_vm_group((uint32_t *)bos[shader->handle].data,shader->size/4,
                        cmd->launch.grid,group,cmd->launch.local,m);
                }
        } else if (j->kind==2) {
            assert(j->fill.destination_offset+j->fill.bytes<=65536);
            uint32_t *p=(uint32_t *)(bos[j->fill.destination_handle].data+j->fill.destination_offset);
            for(unsigned i=0;i<j->fill.bytes/4;i++)p[i]=j->fill.pattern;
        }
    }
    sync_status[s]=(unsigned)(j->status ? j->status : 1);
    for (unsigned h=1;h<next_bo;h++)if(j->refs[h]) { assert(bos[h].jobs); bos[h].jobs--; maybe_free(h); }
    j->live=0;
}

/* These are the only interposed primitives; compile/dispatch/resource helpers
 * themselves are production bodies, linked separately. */
int cs_fixture_ioctl(int fd,unsigned long request,...)
{
    va_list ap;void *arg;
    va_start(ap,request);arg=va_arg(ap,void *);va_end(ap);
    assert(fd==101 || fd>=20000);
    if(request==fail_request && ++fail_seen==fail_nth){errno=ENOMEM;return -1;}
    if(request==DRM_IOCTL_OPENGPU_GET_PARAM){
        struct drm_opengpu_param *p=arg;
        switch(p->param){
        case OPENGPU_PARAM_CAPABILITIES:p->value=OPENGPU_CAP_NATIVE_PIXEL_FORMATS|OPENGPU_CAP_FRAGMENT_CORE;break;
        case OPENGPU_PARAM_DRIVER_FEATURES:p->value=features;break;
        case OPENGPU_PARAM_COMPUTE_LOCAL_ITEMS:p->value=limit_items;break;
        case OPENGPU_PARAM_COMPUTE_LOCAL_AXIS:p->value=limit_axis;break;
        default:assert(!"unexpected param");
        }return 0;
    }
    if(request==DRM_IOCTL_MODE_GETRESOURCES){errno=ENODEV;return -1;}
    if(request==DRM_IOCTL_OPENGPU_CONTEXT_CREATE){
        struct drm_opengpu_context *p=arg;assert(!p->id && next_context<N);p->id=next_context++;contexts[p->id].live=1;return 0;
    }
    if(request==DRM_IOCTL_OPENGPU_CONTEXT_DESTROY){
        struct drm_opengpu_context *p=arg;assert(contexts[p->id].live);
        for(unsigned s=1;s<next_sync;s++)if(jobs[s].live && jobs[s].context==p->id)finish_job(s,0);
        for(unsigned i=1;i<39;i++)drop_binding(&contexts[p->id].b[i]);
        contexts[p->id].live=0;return 0;
    }
    if(request==DRM_IOCTL_OPENGPU_CREATE_BUFFER){
        struct drm_opengpu_create_buffer *p=arg;unsigned h=next_bo++;
        assert(h<N && p->width && p->height && p->bpp==32);
        p->pitch=(p->width*4+63)&~63u;p->size=(uint64_t)p->pitch*p->height;
        p->handle=h;p->offset=(uint64_t)h*4096;
        bos[h]=(struct bo){.handle=1,.size=p->size,.data=calloc(1,65536)};assert(bos[h].data);return 0;
    }
    if(request==DRM_IOCTL_GEM_CLOSE){struct drm_gem_close *p=arg;assert(bos[p->handle].handle);bos[p->handle].handle=0;maybe_free(p->handle);return 0;}
    if(request==DRM_IOCTL_OPENGPU_RESOURCE_BIND){
        const struct drm_opengpu_resource *p=arg;assert(contexts[p->context_id].live && p->slot && p->slot<39);
        assert(p->handle && p->handle<next_bo && bos[p->handle].handle);
        assert(p->offset<=bos[p->handle].size && p->size<=bos[p->handle].size-p->offset);
        if(p->type==OPENGPU_RESOURCE_COMPUTE_STORAGE){
            unsigned align=features&OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_BYTE_VIEWS?256:4096;
            assert(!(p->offset&(align-1)) && p->size && p->size<=0x8000000u);
            if(align==4096)assert(!(p->size&63));
        }
        drop_binding(&contexts[p->context_id].b[p->slot]);
        contexts[p->context_id].b[p->slot]=*p;bos[p->handle].binds++;return 0;
    }
    if(request==DRM_IOCTL_OPENGPU_RESOURCE_UNBIND){const struct drm_opengpu_resource *p=arg;assert(contexts[p->context_id].live);drop_binding(&contexts[p->context_id].b[p->slot]);return 0;}
    if(request==DRM_IOCTL_SYNCOBJ_CREATE){struct drm_syncobj_create *p=arg;p->handle=next_sync++;assert(p->handle<N);live_sync[p->handle]=1;return 0;}
    if(request==DRM_IOCTL_SYNCOBJ_DESTROY){struct drm_syncobj_destroy *p=arg;assert(live_sync[p->handle]);live_sync[p->handle]=0;return 0;}
    if(request==DRM_IOCTL_OPENGPU_COMPUTE_STORAGE){
        const struct drm_opengpu_compute_storage *p=arg;unsigned s=p->launch.out_syncobj;
        struct context *c=&contexts[p->launch.context_id];assert(c->live && live_sync[s] && !jobs[s].live);
        const struct drm_opengpu_resource *shader=&c->b[p->launch.shader_slot],*karg=&c->b[p->launch.kernarg_slot];
        assert(shader->type==OPENGPU_RESOURCE_COMPUTE_SHADER && shader->size==2048);
        assert(karg->type==OPENGPU_RESOURCE_COMPUTE_KERNARG && (karg->flags&OPENGPU_RESOURCE_UNCACHED));
        unsigned product=p->launch.local[0]*p->launch.local[1]*p->launch.local[2];assert(product && product<=limit_items);
        assert(opengpu_compute_shader_validate_words_storage((uint32_t *)bos[shader->handle].data,shader->size/4,karg->size,product,false));
        assert(!memcmp(bos[karg->handle].data,p->launch.grid,12));
        jobs[s]=(struct job){.live=1,.kind=1,.context=p->launch.context_id,.compute=*p,.status=status_error? -EIO:0};
        retain_job(&jobs[s],shader->handle);retain_job(&jobs[s],karg->handle);
        for(unsigned i=0;i<4;i++)if(p->bindings[i].slot){assert(p->bindings[i].slot==35+i);retain_job(&jobs[s],c->b[35+i].handle);}
        last_compute_context=p->launch.context_id;computes++;return 0;
    }
    if(request==DRM_IOCTL_OPENGPU_FILL){
        struct drm_opengpu_fill *p=arg;unsigned s=p->out_syncobj;assert(live_sync[s]);
        jobs[s]=(struct job){.live=1,.kind=2,.context=p->context_id,.fill=*p};retain_job(&jobs[s],p->destination_handle);fills++;return 0;
    }
    if(request==DRM_IOCTL_SYNCOBJ_WAIT){
        struct drm_syncobj_wait *p=arg;unsigned s=*(uint32_t *)(uintptr_t)p->handles;
        assert(p->count_handles==1 && live_sync[s] && jobs[s].live);waits++;
        for(unsigned h=1;h<next_bo;h++)if(jobs[s].refs[h])assert(bos[h].data && bos[h].jobs);
        if(jobs[s].kind==1){
            for(unsigned i=0;i<4;i++)if(held_resources[i])assert(held_resources[i]->reference.count>=2);
            held_checks++;
        }
        if(wait_error){errno=ETIME;return -1;}
        finish_job(s,1);return 0;
    }
    if(request==DRM_IOCTL_SYNCOBJ_HANDLE_TO_FD){struct drm_syncobj_handle *p=arg;assert(!jobs[p->handle].live);p->fd=20000+p->handle;exported[p->handle]++;return 0;}
    if(request==SYNC_IOC_FILE_INFO){struct sync_file_info *p=arg;unsigned s=(unsigned)fd-20000;assert(exported[s]);p->status=(int)sync_status[s];return 0;}
    fprintf(stderr,"unexpected ioctl %lx\n",request);abort();
}
int cs_fixture_dup(int fd){assert(fd==7);return 101;}
int cs_fixture_close(int fd){if(fd>=20000){unsigned s=(unsigned)fd-20000;assert(exported[s]);exported[s]--;}else assert(fd==101);return 0;}
void *cs_fixture_mmap(void *addr,size_t length,int prot,int flags,int fd,off_t offset)
{unsigned h=(unsigned)(offset/4096);(void)addr;(void)prot;(void)flags;assert(fd==101 && h<next_bo && length==bos[h].size);if(map_error){map_error--;errno=ENOMEM;return MAP_FAILED;}bos[h].maps++;return bos[h].data;}
int cs_fixture_munmap(void *addr,size_t length)
{for(unsigned h=1;h<next_bo;h++)if(bos[h].data==addr){assert(length==bos[h].size && bos[h].maps);bos[h].maps--;maybe_free(h);return 0;}assert(!"unmatched unmap");return -1;}

/* Unused generic graphics upload helpers: never substitute the compute path. */
struct u_upload_mgr *u_upload_create_default(struct pipe_context *p){(void)p;return (struct u_upload_mgr *)calloc(1,1);}
void u_upload_destroy(struct u_upload_mgr *p){free(p);}
void u_upload_alloc(struct u_upload_mgr *u,unsigned m,unsigned n,unsigned a,unsigned *o,struct pipe_resource **r,void **p)
{(void)u;(void)m;(void)n;(void)a;(void)o;(void)r;(void)p;assert(!"unexpected graphics uploader");}
void u_upload_unmap(struct u_upload_mgr *u){(void)u;}
void u_default_buffer_subdata(struct pipe_context *p,struct pipe_resource *r,unsigned u,unsigned o,unsigned n,const void *d)
{(void)p;(void)r;(void)u;(void)o;(void)n;(void)d;assert(!"unexpected generic upload");}
void u_default_texture_subdata(struct pipe_context *p,struct pipe_resource *r,unsigned l,unsigned u,const struct pipe_box *b,const void *d,unsigned s,unsigned z)
{(void)p;(void)r;(void)l;(void)u;(void)b;(void)d;(void)s;(void)z;assert(!"unexpected generic texture upload");}
void u_default_transfer_flush_region(struct pipe_context *p,struct pipe_transfer *t,const struct pipe_box *b)
{(void)p;(void)t;(void)b;assert(!"unexpected generic flush");}
void tgsi_dump(const struct tgsi_token *t,unsigned f){(void)t;(void)f;assert(!"unexpected debug dump");}

struct builder {struct tgsi_token t[TOKENS];struct tgsi_header *h;};
static void begin(struct builder *b,unsigned processor)
{memset(b,0,sizeof(*b));b->h=(struct tgsi_header *)b->t;*b->h=tgsi_build_header();struct tgsi_processor p=tgsi_build_processor(processor,b->h);memcpy(b->t+1,&p,4);}
static void append_decl(struct builder *b,unsigned file,unsigned first,unsigned last,int semantic)
{struct tgsi_full_declaration d=tgsi_default_full_declaration();d.Declaration.File=file;d.Range.First=first;d.Range.Last=last;
 if(semantic>=0){d.Declaration.Semantic=1;d.Declaration.UsageMask=file==TGSI_FILE_SYSTEM_VALUE?7:15;d.Semantic.Name=(unsigned)semantic;}
 assert(tgsi_build_full_declaration(&d,b->t+b->h->HeaderSize+b->h->BodySize,b->h,TOKENS-b->h->HeaderSize-b->h->BodySize));}
static void immediate(struct builder *b,uint32_t a,uint32_t c,uint32_t d,uint32_t e)
{struct tgsi_full_immediate v=tgsi_default_full_immediate();v.Immediate.NrTokens=5;v.Immediate.DataType=TGSI_IMM_UINT32;v.u[0].Uint=a;v.u[1].Uint=c;v.u[2].Uint=d;v.u[3].Uint=e;
 assert(tgsi_build_full_immediate(&v,b->t+b->h->HeaderSize+b->h->BodySize,b->h,TOKENS-b->h->HeaderSize-b->h->BodySize));}
static struct tgsi_src_register src(unsigned f,int i,unsigned c)
{struct tgsi_src_register s={0};s.File=f;s.Index=i;s.SwizzleX=s.SwizzleY=s.SwizzleZ=s.SwizzleW=c;return s;}
static void insn(struct builder *b,unsigned op,unsigned df,unsigned di,unsigned mask,
                 struct tgsi_src_register a,struct tgsi_src_register z)
{struct tgsi_full_instruction i=tgsi_default_full_instruction();i.Instruction.Opcode=op;
 if(op!=TGSI_OPCODE_END){i.Instruction.NumDstRegs=1;i.Instruction.NumSrcRegs=op==TGSI_OPCODE_MOV?1:2;i.Dst[0].Register.File=df;i.Dst[0].Register.Index=(int)di;i.Dst[0].Register.WriteMask=mask;i.Src[0].Register=a;i.Src[1].Register=z;}
 else{i.Instruction.NumDstRegs=i.Instruction.NumSrcRegs=0;}
 if(op==TGSI_OPCODE_LOAD || op==TGSI_OPCODE_STORE){i.Instruction.Memory=1;i.Memory.Texture=TGSI_TEXTURE_BUFFER;}
 assert(tgsi_build_full_instruction(&i,b->t+b->h->HeaderSize+b->h->BodySize,b->h,TOKENS-b->h->HeaderSize-b->h->BodySize));}
#define T(i,c) src(TGSI_FILE_TEMPORARY,i,c)
#define S(i,c) src(TGSI_FILE_SYSTEM_VALUE,i,c)
#define I(c) src(TGSI_FILE_IMMEDIATE,0,c)
static void program(struct builder *b,uint32_t seed)
{
 begin(b,PIPE_SHADER_COMPUTE);append_decl(b,TGSI_FILE_TEMPORARY,0,3,-1);append_decl(b,TGSI_FILE_BUFFER,0,3,-1);
 append_decl(b,TGSI_FILE_SYSTEM_VALUE,0,0,TGSI_SEMANTIC_THREAD_ID);append_decl(b,TGSI_FILE_SYSTEM_VALUE,1,1,TGSI_SEMANTIC_BLOCK_ID);
 append_decl(b,TGSI_FILE_SYSTEM_VALUE,2,2,TGSI_SEMANTIC_BLOCK_SIZE);append_decl(b,TGSI_FILE_SYSTEM_VALUE,3,3,TGSI_SEMANTIC_GRID_SIZE);immediate(b,2,seed,0xdeadbeefu,7);
 insn(b,TGSI_OPCODE_UMUL,TGSI_FILE_TEMPORARY,0,1,S(0,2),S(2,1));
 insn(b,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,0,1,T(0,0),S(0,1));
 insn(b,TGSI_OPCODE_UMUL,TGSI_FILE_TEMPORARY,0,1,T(0,0),S(2,0));
 insn(b,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,0,1,T(0,0),S(0,0));
 insn(b,TGSI_OPCODE_UMUL,TGSI_FILE_TEMPORARY,1,1,S(1,2),S(3,1));
 insn(b,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,1,1,T(1,0),S(1,1));
 insn(b,TGSI_OPCODE_UMUL,TGSI_FILE_TEMPORARY,1,1,T(1,0),S(3,0));
 insn(b,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,1,1,T(1,0),S(1,0));
 insn(b,TGSI_OPCODE_UMUL,TGSI_FILE_TEMPORARY,2,1,S(2,0),S(2,1));
 insn(b,TGSI_OPCODE_UMUL,TGSI_FILE_TEMPORARY,2,1,T(2,0),S(2,2));
 insn(b,TGSI_OPCODE_UMUL,TGSI_FILE_TEMPORARY,1,1,T(1,0),T(2,0));
 insn(b,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,0,1,T(0,0),T(1,0));
 insn(b,TGSI_OPCODE_SHL,TGSI_FILE_TEMPORARY,1,1,T(0,0),I(0));
 insn(b,TGSI_OPCODE_LOAD,TGSI_FILE_TEMPORARY,2,1,src(TGSI_FILE_BUFFER,0,0),T(1,0));
 insn(b,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,2,1,T(2,0),I(1));
 insn(b,TGSI_OPCODE_STORE,TGSI_FILE_BUFFER,1,1,T(1,0),T(2,0));
 insn(b,TGSI_OPCODE_XOR,TGSI_FILE_TEMPORARY,2,1,T(0,0),I(2));
 insn(b,TGSI_OPCODE_SHL,TGSI_FILE_TEMPORARY,2,1,T(2,0),I(3));
 insn(b,TGSI_OPCODE_USHR,TGSI_FILE_TEMPORARY,2,1,T(2,0),I(3));
 insn(b,TGSI_OPCODE_AND,TGSI_FILE_TEMPORARY,2,1,T(2,0),I(2));
 insn(b,TGSI_OPCODE_OR,TGSI_FILE_TEMPORARY,2,1,T(2,0),T(0,0));
 insn(b,TGSI_OPCODE_STORE,TGSI_FILE_BUFFER,2,1,T(1,0),T(2,0));
 struct tgsi_src_register a=S(0,0);a.SwizzleX=2;a.SwizzleY=1;a.SwizzleZ=0;
 insn(b,TGSI_OPCODE_MOV,TGSI_FILE_TEMPORARY,3,7,a,a);
 a=T(3,0);a.SwizzleX=1;a.SwizzleY=2;a.SwizzleZ=0;
 struct tgsi_src_register z=S(0,0);z.SwizzleX=0;z.SwizzleY=2;z.SwizzleZ=1;
 insn(b,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,3,7,a,z);
 insn(b,TGSI_OPCODE_STORE,TGSI_FILE_BUFFER,3,1,T(1,0),T(3,1));
 insn(b,TGSI_OPCODE_END,0,0,0,I(0),I(0));
}
static struct pipe_resource *buffer(struct pipe_screen *s,unsigned bytes)
{struct pipe_resource t={0};t.target=PIPE_BUFFER;t.format=PIPE_FORMAT_R32_UINT;t.width0=bytes;t.height0=t.depth0=t.array_size=1;t.bind=PIPE_BIND_SHADER_BUFFER;return s->resource_create(s,&t);}
static void initialize(struct pipe_resource *r[4],unsigned offset)
{for(unsigned j=0;j<4;j++){uint32_t *p=pipe_opengpu_resource_map(opengpu_resource(r[j])->gpu);unsigned words=(unsigned)pipe_opengpu_resource_size(opengpu_resource(r[j])->gpu)/4;assert(words<=16384);for(unsigned i=0;i<words;i++)p[i]=0xdeadfaceu;if(j==0)for(unsigned i=0;i<1024;i++)p[offset/4+i]=0xfffff000u+i;}}
static void check(struct pipe_resource *r[4],unsigned offset,const struct pipe_grid_info *g,uint32_t seed)
{
 unsigned local=g->block[0]*g->block[1]*g->block[2],groups=g->grid[0]*g->grid[1]*g->grid[2],n=local*groups;
 for(unsigned j=0;j<4;j++){uint32_t *p=pipe_opengpu_resource_map(opengpu_resource(r[j])->gpu);
  unsigned words=(unsigned)pipe_opengpu_resource_size(opengpu_resource(r[j])->gpu)/4;assert(words<=16384);
  for(unsigned k=0;k<words;k++){
   uint32_t want=0xdeadfaceu;
   if(k>=offset/4 && k<offset/4+1024 && j==0)want=0xfffff000u+k-offset/4;
   if(k>=offset/4 && k<offset/4+n){unsigned i=k-offset/4,li=i%local;
    if(j==1)want=0xfffff000u+i+seed;
    if(j==2)want=((((i^0xdeadbeefu)<<7)>>7)&0xdeadbeefu)|i;
    if(j==3)want=li%g->block[0]+li/(g->block[0]*g->block[1]);}
   assert(p[k]==want);
  }
 }
}
static void views(struct pipe_context *p,struct pipe_resource *r[4],unsigned offset,unsigned size,unsigned writable)
{struct pipe_shader_buffer b[4];for(unsigned i=0;i<4;i++)b[i]=(struct pipe_shader_buffer){r[i],offset,size};p->set_shader_buffers(p,PIPE_SHADER_COMPUTE,0,4,b,writable);}
static void expect_no_submit(struct pipe_context *p,const struct pipe_grid_info *g)
{unsigned before=computes;p->launch_grid(p,g);assert(computes==before);}
static void no_leaks(void)
{for(unsigned h=1;h<next_bo;h++)assert(!bos[h].data && !bos[h].handle && !bos[h].jobs && !bos[h].binds && !bos[h].maps);
 for(unsigned c=1;c<next_context;c++)assert(!contexts[c].live);
 for(unsigned s=1;s<next_sync;s++)assert(!live_sync[s] && !exported[s] && !jobs[s].live);}

static unsigned live_contexts(void)
{unsigned count=0;for(unsigned c=1;c<next_context;c++)count+=contexts[c].live;return count;}
static void reject_program(struct pipe_context *p,struct builder *b)
{
 struct opengpu_cs_program out,unchanged;memset(&out,0xa5,sizeof(out));unchanged=out;
 struct pipe_compute_state state={.ir_type=PIPE_SHADER_IR_TGSI,.prog=b->t};
 assert(opengpu_cs_emit(b->t,&out) && !memcmp(&out,&unchanged,sizeof(out)));
 assert(!p->create_compute_state(p,&state));rejected_programs++;
}
static unsigned find_token(struct builder *b,unsigned type,int opcode)
{
 for(unsigned pos=2;pos<2u+b->h->BodySize;){
  struct tgsi_token t;memcpy(&t,b->t+pos,4);
  struct tgsi_instruction i;memcpy(&i,b->t+pos,4);
  if(t.Type==type && (opcode<0 || (int)i.Opcode==opcode))return pos;
  pos+=t.NrTokens+(t.Type==TGSI_TOKEN_TYPE_INSTRUCTION);
 }
 assert(!"missing fixture token");return 0;
}
static void compiler_negatives(struct pipe_context *p)
{
 struct builder b;
 program(&b,7);b.h->HeaderSize=1;reject_program(p,&b);
 program(&b,7);((struct tgsi_processor *)(b.t+1))->Processor=PIPE_SHADER_FRAGMENT;reject_program(p,&b);
 program(&b,7);b.h->BodySize--;reject_program(p,&b); /* missing END */
 program(&b,7);struct tgsi_instruction *i=(struct tgsi_instruction *)(b.t+find_token(&b,TGSI_TOKEN_TYPE_INSTRUCTION,TGSI_OPCODE_UADD));i->Opcode=TGSI_OPCODE_ADD;reject_program(p,&b);
 program(&b,7);i=(struct tgsi_instruction *)(b.t+find_token(&b,TGSI_TOKEN_TYPE_INSTRUCTION,TGSI_OPCODE_MOV));i->NrTokens--;reject_program(p,&b);
 program(&b,7);unsigned pos=find_token(&b,TGSI_TOKEN_TYPE_INSTRUCTION,TGSI_OPCODE_UMUL);((struct tgsi_src_register *)(b.t+pos+2))->Indirect=1;reject_program(p,&b);
 program(&b,7);((struct tgsi_declaration *)(b.t+2))->File=TGSI_FILE_IMAGE;reject_program(p,&b); /* parser would otherwise read an extra image word */
 program(&b,7);((struct tgsi_immediate *)(b.t+find_token(&b,TGSI_TOKEN_TYPE_IMMEDIATE,-1)))->NrTokens=1;reject_program(p,&b);
 program(&b,7);((struct tgsi_immediate *)(b.t+find_token(&b,TGSI_TOKEN_TYPE_IMMEDIATE,-1)))->DataType=TGSI_IMM_FLOAT32;reject_program(p,&b);
 program(&b,7);pos=find_token(&b,TGSI_TOKEN_TYPE_INSTRUCTION,TGSI_OPCODE_LOAD);((struct tgsi_instruction_memory *)(b.t+pos+1))->Qualifier=TGSI_MEMORY_COHERENT;reject_program(p,&b);
 begin(&b,PIPE_SHADER_COMPUTE);append_decl(&b,TGSI_FILE_TEMPORARY,0,1,-1);insn(&b,TGSI_OPCODE_MOV,TGSI_FILE_TEMPORARY,0,1,T(1,0),T(1,0));insn(&b,TGSI_OPCODE_END,0,0,0,I(0),I(0));reject_program(p,&b);
 begin(&b,PIPE_SHADER_COMPUTE);append_decl(&b,TGSI_FILE_TEMPORARY,0,31,-1);immediate(&b,1,2,3,4);
 for(unsigned n=0;n<8;n++)insn(&b,TGSI_OPCODE_MOV,TGSI_FILE_TEMPORARY,n,15,I(0),I(0));
 insn(&b,TGSI_OPCODE_END,0,0,0,I(0),I(0));reject_program(p,&b); /* component-register exhaustion */
 begin(&b,PIPE_SHADER_COMPUTE);append_decl(&b,TGSI_FILE_TEMPORARY,0,0,-1);immediate(&b,1,2,3,4);
 for(unsigned n=0;n<100;n++)insn(&b,TGSI_OPCODE_UADD,TGSI_FILE_TEMPORARY,0,1,I(0),I(1));
 insn(&b,TGSI_OPCODE_END,0,0,0,I(0),I(0));reject_program(p,&b); /* >512 words, no larger sandbox */
 struct pipe_compute_state state={.ir_type=PIPE_SHADER_IR_NIR,.prog=b.t};assert(!p->create_compute_state(p,&state));
 program(&b,7);state.ir_type=PIPE_SHADER_IR_TGSI;state.req_local_mem=1;assert(!p->create_compute_state(p,&state));
 state.req_local_mem=0;state.req_private_mem=1;assert(!p->create_compute_state(p,&state));
 state.req_private_mem=0;state.req_input_mem=4;assert(!p->create_compute_state(p,&state));
}

static void failure_recovery(struct pipe_context *p,void *cs,struct pipe_resource *r[4],const struct pipe_grid_info *g,unsigned offset)
{
 const struct {unsigned request,nth,accepted;} cases[]={
  {DRM_IOCTL_OPENGPU_CONTEXT_CREATE,1,0}, {DRM_IOCTL_OPENGPU_CREATE_BUFFER,1,0},
  {DRM_IOCTL_OPENGPU_CREATE_BUFFER,2,0}, {DRM_IOCTL_OPENGPU_RESOURCE_BIND,1,0},
  {DRM_IOCTL_OPENGPU_RESOURCE_BIND,2,0}, {DRM_IOCTL_OPENGPU_RESOURCE_BIND,4,0},
  {DRM_IOCTL_OPENGPU_RESOURCE_BIND,6,0}, {DRM_IOCTL_SYNCOBJ_CREATE,1,0},
  {DRM_IOCTL_OPENGPU_COMPUTE_STORAGE,1,0}, {DRM_IOCTL_SYNCOBJ_HANDLE_TO_FD,1,1},
  {SYNC_IOC_FILE_INFO,1,1}, {0,0,0}, {0,0,1}, {0,0,1}
 };
 for(unsigned k=0;k<sizeof(cases)/sizeof(cases[0]);k++){
  p->bind_compute_state(p,NULL);p->bind_compute_state(p,cs);assert(live_contexts()==1);
  initialize(r,offset);unsigned before=computes,prior_context=last_compute_context,old_canceled=canceled;
  fail_request=cases[k].request;fail_nth=cases[k].nth;fail_seen=0;
  map_error=k==11;wait_error=k==12;status_error=k==13;
  p->launch_grid(p,g);assert(computes==before+cases[k].accepted && live_contexts()==1);
  if(wait_error)assert(canceled==old_canceled+1);
  fail_request=fail_nth=fail_seen=map_error=wait_error=status_error=0;
  initialize(r,offset);before=computes;p->launch_grid(p,g);
  assert(computes==before+1 && last_compute_context!=prior_context && live_contexts()==2);
  check(r,offset,g,0x12345u);
 }
}

static void graphics_setup(struct pipe_context *p,struct pipe_resource **color,struct pipe_surface **surface,void **fs)
{
 struct builder b;begin(&b,PIPE_SHADER_FRAGMENT);append_decl(&b,TGSI_FILE_OUTPUT,0,0,TGSI_SEMANTIC_COLOR);
 immediate(&b,0x3f800000u,0x3f800000u,0x3f800000u,0x3f800000u);
 ((struct tgsi_immediate *)(b.t+find_token(&b,TGSI_TOKEN_TYPE_IMMEDIATE,-1)))->DataType=TGSI_IMM_FLOAT32;
 insn(&b,TGSI_OPCODE_MOV,TGSI_FILE_OUTPUT,0,15,I(0),I(0));insn(&b,TGSI_OPCODE_END,0,0,0,I(0),I(0));
 struct pipe_shader_state shader={.type=PIPE_SHADER_IR_TGSI,.tokens=b.t};*fs=p->create_fs_state(p,&shader);assert(*fs);p->bind_fs_state(p,*fs);
 struct pipe_resource resource={0};resource.target=PIPE_TEXTURE_2D;resource.format=PIPE_FORMAT_R8G8B8A8_UNORM;
 resource.width0=resource.height0=16;resource.depth0=resource.array_size=1;resource.bind=PIPE_BIND_RENDER_TARGET;
 *color=p->screen->resource_create(p->screen,&resource);assert(*color);
 struct pipe_surface view={.format=resource.format};*surface=p->create_surface(p,*color,&view);assert(*surface);
 struct pipe_framebuffer_state fb={.width=16,.height=16,.nr_cbufs=1,.cbufs={*surface}};p->set_framebuffer_state(p,&fb);
}
static void graphics_clear(struct pipe_context *p,struct pipe_resource *color,float red,float green,uint32_t expected)
{
 union pipe_color_union c={.f={red,green,0,1}};unsigned before=fills;p->clear(p,PIPE_CLEAR_COLOR0,NULL,&c,0,0);assert(fills==before+1);
 uint32_t *pixels=pipe_opengpu_resource_map(opengpu_resource(color)->gpu);
 for(unsigned n=0;n<256;n++)assert(pixels[n]==expected);
}

static void reference_and_state_tests(struct pipe_screen *s,struct pipe_context *p,void *cs,
                                     struct pipe_resource *r[4],const struct pipe_grid_info *g)
{
 /* Drop every application reference, then swap slots. Taking/releasing one
  * slot at a time would destroy the source for a later slot (ASan catches it). */
 struct pipe_resource *owned[4],*raw[4];
 for(unsigned i=0;i<4;i++){owned[i]=buffer(s,8192);assert(owned[i]);raw[i]=owned[i];}
 views(p,raw,0,4096,14);for(unsigned i=0;i<4;i++)pipe_resource_reference(&owned[i],NULL);
 struct pipe_resource *reverse[4]={raw[3],raw[2],raw[1],raw[0]};views(p,reverse,0,4096,14);
 for(unsigned i=0;i<4;i++)assert(raw[i]->reference.count==1);
 /* Four aliases retain one pipe reference per slot and one modeled job ref
  * per backing. Source/write usage remains in the actual ioctl. */
 struct pipe_resource *same[4]={raw[0],raw[0],raw[0],raw[0]};views(p,same,0,4096,14);
 assert(raw[0]->reference.count==4);for(unsigned i=0;i<4;i++)held_resources[i]=NULL;
 semantic=0;unsigned before=computes;p->launch_grid(p,g);assert(computes==before+1);semantic=1;
 views(p,r,0,4096,14);for(unsigned i=0;i<4;i++)held_resources[i]=r[i];
 struct pipe_resource *padded=buffer(s,4097);assert(padded);
 struct pipe_shader_buffer padding={padded,0,4160};p->set_shader_buffers(p,PIPE_SHADER_COMPUTE,0,1,&padding,0);
 expect_no_submit(p,g);pipe_resource_reference(&padded,NULL);views(p,r,0,4096,14);

 struct pipe_context *other=s->context_create(s,NULL,0);assert(other);
 struct builder b;program(&b,11);struct pipe_compute_state state={.ir_type=PIPE_SHADER_IR_TGSI,.prog=b.t};
 void *foreign=other->create_compute_state(other,&state);assert(foreign);
 p->bind_compute_state(p,foreign);expect_no_submit(p,g);p->delete_compute_state(p,foreign);
 p->bind_compute_state(p,cs);other->destroy(other); /* also deletes its unbound state */

 /* Fixed block properties are actual TGSI, not a side-channel launch menu. */
 program(&b,0x12345u);b.h->BodySize--;
 struct tgsi_full_property prop=tgsi_default_full_property();
 prop.Property.PropertyName=TGSI_PROPERTY_CS_FIXED_BLOCK_WIDTH;prop.Property.NrTokens=2;prop.u[0].Data=128;
 assert(tgsi_build_full_property(&prop,b.t+b.h->HeaderSize+b.h->BodySize,b.h,TOKENS-b.h->HeaderSize-b.h->BodySize));
 insn(&b,TGSI_OPCODE_END,0,0,0,I(0),I(0));
 void *fixed=p->create_compute_state(p,&state);assert(fixed);p->bind_compute_state(p,fixed);
 struct pipe_grid_info mismatch=*g;mismatch.block[0]=33;expect_no_submit(p,&mismatch);
 initialize(r,0);before=computes;p->launch_grid(p,g);assert(computes==before+1);check(r,0,g,0x12345u);
 p->delete_compute_state(p,fixed);p->bind_compute_state(p,cs);
}

static void disabled_screen_tests(void)
{
 struct builder b;program(&b,1);struct pipe_compute_state state={.ir_type=PIPE_SHADER_IR_TGSI,.prog=b.t};
 for(unsigned k=0;k<5;k++){
  features=k==0?0:k==1?OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_BYTE_VIEWS:OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE;
  limit_items=k==2||k==4?0:128;limit_axis=k==2?0:k==3?129:128;
  struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s);
  assert(!s->get_param(s,PIPE_CAP_COMPUTE));assert(!s->get_shader_param(s,PIPE_SHADER_COMPUTE,PIPE_SHADER_CAP_MAX_SHADER_BUFFERS));
  assert(!s->get_compute_param(s,PIPE_SHADER_IR_TGSI,PIPE_COMPUTE_CAP_MAX_THREADS_PER_BLOCK,NULL));
  struct pipe_context *p=s->context_create(s,NULL,0);assert(p && !p->create_compute_state(p,&state));p->destroy(p);s->destroy(s);
 }
 features=OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE;limit_items=limit_axis=128;
}

static void byte_view_tests(void)
{
 features=OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE|OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_BYTE_VIEWS;
 struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s);
 assert(s->get_param(s,PIPE_CAP_SHADER_BUFFER_OFFSET_ALIGNMENT)==256 && !s->get_param(s,PIPE_CAP_COMPUTE));
 struct pipe_context *p=s->context_create(s,NULL,0);assert(p);
 struct builder b;program(&b,0x12345u);struct pipe_compute_state state={.ir_type=PIPE_SHADER_IR_TGSI,.prog=b.t};
 void *cs=p->create_compute_state(p,&state);assert(cs);p->bind_compute_state(p,cs);
 struct pipe_resource *r[4];for(unsigned i=0;i<4;i++){r[i]=buffer(s,8192);assert(r[i]);held_resources[i]=r[i];}
 struct pipe_grid_info g={.grid={2,2,2},.block={128,1,1}};
 const unsigned offsets[]={256,768},bytes[]={4099,4097};
 for(unsigned n=0;n<2;n++){
  views(p,r,offsets[n],bytes[n],14);initialize(r,offsets[n]);unsigned before=computes;
  p->launch_grid(p,&g);assert(computes==before+1);check(r,offsets[n],&g,0x12345u);
  for(unsigned i=0;i<4;i++){const struct drm_opengpu_resource *v=&contexts[last_compute_context].b[35+i];assert(v->offset==offsets[n] && v->size==bytes[n]);}
 }
 views(p,r,128,4099,14);expect_no_submit(p,&g);views(p,r,256,0,14);expect_no_submit(p,&g);
 views(p,r,8192,1,14);expect_no_submit(p,&g);views(p,r,0,8193,14);expect_no_submit(p,&g);
 struct pipe_resource *padded=buffer(s,4097);assert(padded);struct pipe_shader_buffer bad={padded,0,4160};
 p->set_shader_buffers(p,PIPE_SHADER_COMPUTE,0,1,&bad,0);expect_no_submit(p,&g);pipe_resource_reference(&padded,NULL);

 /* Every positive length is legal metadata; a dynamic incomplete word would
  * still fault in the checked storage service. This 1-byte case is not run by
  * the CPU word VM or claimed as a successful GPU memory access. */
 views(p,r,256,1,14);semantic=0;unsigned before=computes;p->launch_grid(p,&g);assert(computes==before+1);
 for(unsigned i=0;i<4;i++)assert(contexts[last_compute_context].b[35+i].size==1);
 struct pipe_resource *big[4];for(unsigned i=0;i<4;i++){big[i]=buffer(s,0x8000100u);assert(big[i]);held_resources[i]=big[i];}
 views(p,big,256,0x8000000u,14);before=computes;p->launch_grid(p,&g);assert(computes==before+1);
 for(unsigned i=0;i<4;i++){const struct drm_opengpu_resource *v=&contexts[last_compute_context].b[35+i];assert(v->offset==256 && v->size==0x8000000u);}
 semantic=1;views(p,r,256,4099,14);for(unsigned i=0;i<4;i++){held_resources[i]=r[i];pipe_resource_reference(&big[i],NULL);}
 failure_recovery(p,cs,r,&g,256);
 p->destroy(p);for(unsigned i=0;i<4;i++){held_resources[i]=NULL;pipe_resource_reference(&r[i],NULL);}s->destroy(s);
 features=OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE;
}

int main(void)
{
 struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s);
 assert(s->get_param(s,PIPE_CAP_SHADER_BUFFER_OFFSET_ALIGNMENT)==4096);
 assert(!s->get_param(s,PIPE_CAP_COMPUTE) && s->get_param(s,PIPE_CAP_ESSL_FEATURE_LEVEL)==100);
 uint64_t limits[3]={0};assert(s->get_compute_param(s,PIPE_SHADER_IR_TGSI,PIPE_COMPUTE_CAP_MAX_BLOCK_SIZE,limits)==24 && limits[0]==128);
 assert(!s->get_compute_param(s,PIPE_SHADER_IR_NATIVE,PIPE_COMPUTE_CAP_MAX_BLOCK_SIZE,limits));
 assert(!s->get_compute_param(s,PIPE_SHADER_IR_TGSI,PIPE_COMPUTE_CAP_MAX_COMPUTE_UNITS,limits));
 assert(s->get_shader_param(s,PIPE_SHADER_COMPUTE,PIPE_SHADER_CAP_MAX_SHADER_BUFFERS)==4);
 struct pipe_context *p=s->context_create(s,NULL,0);assert(p && p->launch_grid && p->create_compute_state && p->set_shader_buffers);
 compiler_negatives(p);
 struct pipe_resource *color;struct pipe_surface *surface;void *fs;
 graphics_setup(p,&color,&surface,&fs);graphics_clear(p,color,1,0,0xff0000ffu);
 unsigned graphics_context=next_context-1;struct context graphics_saved=contexts[graphics_context];
 struct builder b;program(&b,0x12345u);struct pipe_compute_state state={.ir_type=PIPE_SHADER_IR_TGSI,.prog=b.t};
 struct opengpu_cs_program compiled;assert(!opengpu_cs_emit(b.t,&compiled));assert(compiled.words>16 && compiled.words<=512 && compiled.reads==1 && compiled.writes==14);
 void *cs=p->create_compute_state(p,&state);assert(cs);p->bind_compute_state(p,cs);
 struct pipe_resource *r[4];for(unsigned i=0;i<4;i++){r[i]=buffer(s,8192);assert(r[i]);held_resources[i]=r[i];}
 views(p,r,0,4096,14);
 struct pipe_grid_info g={.grid={2,2,2},.block={128,1,1}};
 const unsigned shapes[][3]={{128,1,1},{8,4,4},{33,1,1},{1,128,1},{5,3,7}};
 for(unsigned k=0;k<5;k++){memcpy(g.block,shapes[k],sizeof(g.block));initialize(r,0);unsigned before=computes;p->launch_grid(p,&g);assert(computes==before+1);check(r,0,&g,0x12345u);}
 assert(held_checks>=5);
 assert(last_compute_context!=graphics_context && !memcmp(&contexts[graphics_context],&graphics_saved,sizeof(graphics_saved)));
 graphics_clear(p,color,0,1,0xff00ff00u);
 /* No rounding: old kernel page offset and64-byte exact length. */
 views(p,r,4096,4096,14);memcpy(g.block,shapes[0],sizeof(g.block));initialize(r,4096);p->launch_grid(p,&g);check(r,4096,&g,0x12345u);
 views(p,r,256,4096,14);expect_no_submit(p,&g);views(p,r,0,4095,14);expect_no_submit(p,&g);
 views(p,r,0,4096,0);expect_no_submit(p,&g);views(p,r,0,4096,14);
 struct pipe_grid_info bad=g;bad.block[0]=129;expect_no_submit(p,&bad);bad=g;bad.last_block[0]=127;expect_no_submit(p,&bad);
 bad=g;bad.grid_base[1]=1;expect_no_submit(p,&bad);bad=g;bad.pc=1;expect_no_submit(p,&bad);bad=g;bad.input=&bad;expect_no_submit(p,&bad);
 bad=g;bad.indirect=r[0];expect_no_submit(p,&bad);bad=g;bad.work_dim=4;expect_no_submit(p,&bad);bad=g;bad.grid[0]=0;expect_no_submit(p,&bad);
 /* Fullu32 axes are passed verbatim in a metadata-only case, not traversed. */
 semantic=0;bad=g;bad.grid[0]=UINT32_MAX;bad.grid[1]=0x80000000u;bad.grid[2]=1;unsigned before=computes;p->launch_grid(p,&bad);assert(computes==before+1);semantic=1;
 /* All four maximum views keep their exact 128MiB authority. Only metadata is
  * traversed here; no giant CPU/GPU allocation or frame is performed. */
 struct pipe_resource *big[4];for(unsigned i=0;i<4;i++){big[i]=buffer(s,0x8000000u);assert(big[i]);}
 views(p,big,0,0x8000000u,14);for(unsigned i=0;i<4;i++)held_resources[i]=big[i];
 semantic=0;before=computes;p->launch_grid(p,&g);assert(computes==before+1);
 for(unsigned i=0;i<4;i++)assert(contexts[last_compute_context].b[35+i].size==0x8000000u);
 semantic=1;views(p,r,0,4096,14);for(unsigned i=0;i<4;i++){held_resources[i]=r[i];pipe_resource_reference(&big[i],NULL);}
 failure_recovery(p,cs,r,&g,0);
 assert(!memcmp(&contexts[graphics_context],&graphics_saved,sizeof(graphics_saved)));
 graphics_clear(p,color,1,0,0xff0000ffu);
 reference_and_state_tests(s,p,cs,r,&g);
 /* A changed shader must change actual code/data, not match a fixed menu. */
 program(&b,0xffffffffu);void *cs2=p->create_compute_state(p,&state);assert(cs2);p->bind_compute_state(p,cs2);
 initialize(r,0);p->launch_grid(p,&g);check(r,0,&g,UINT32_MAX);p->delete_compute_state(p,cs);p->delete_compute_state(p,cs2);
 expect_no_submit(p,&g);p->set_shader_buffers(p,PIPE_SHADER_COMPUTE,0,4,NULL,0);
 for(unsigned i=0;i<4;i++){held_resources[i]=NULL;pipe_resource_reference(&r[i],NULL);}
 p->bind_fs_state(p,NULL);p->delete_fs_state(p,fs);pipe_surface_reference(&surface,NULL);pipe_resource_reference(&color,NULL);
 p->destroy(p);s->destroy(s);disabled_screen_tests();byte_view_tests();no_leaks();
 printf("Gallium compute callbacks: actual TGSI/pipe/lib/storage validator,5 shapes/128 items,4x128MiB exact metadata,legacy+byte views,14 failure/recovery types in both modes,graphics isolation,%u rejected programs PASS (%u submissions,%u waits)\n",rejected_programs,computes,waits);
 return 0;
}
