/* SPDX-License-Identifier: MIT */
/* Actual Gallium callbacks -> private pipe -> lib ioctl with modeled OS/GEM
 * primitives. The independent word VM is CPU compiler evidence, not a GPU. */
#define _DEFAULT_SOURCE
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
#ifndef OPENGPU_CS_CALLBACK_MAX_GRID_AXIS
#define OPENGPU_CS_CALLBACK_MAX_GRID_AXIS 2u
#endif
static struct bo { unsigned handle, maps, binds, jobs, sparse; uint64_t size; uint8_t *data; } bos[N];
static struct context { unsigned live, closing; struct drm_opengpu_resource b[OPENGPU_MAX_RESOURCE_BINDINGS + 1]; } contexts[N];
static struct job {
    unsigned live, context, kind, uniform, refs[N];
    int status;
    struct drm_opengpu_compute_storage compute;
    struct drm_opengpu_fill fill;
    struct drm_opengpu_invalidate invalidate;
    struct cs_vm_view uniforms[13];
} jobs[N];
static unsigned next_bo=1, next_context=1, next_sync=1, computes, waits, held_checks, fills;
static unsigned ioctl_calls, invalidate_attempts, invalidates, invalidate_waits;
static unsigned binding_attempts, resource_request_attempts;
static unsigned page_pad_next, sparse_next, destroy_errno;
static unsigned defer_namespace_destroy, render_closes;
static struct drm_opengpu_invalidate last_invalidate;
static unsigned live_sync[N], exported[N], sync_status[N], semantic=1;
static uint64_t features=OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE;
static uint32_t limit_items=128, limit_axis=128;
static uint32_t limit_shared_base, limit_shared_bytes, limit_shared_lanes;
static unsigned shared_query_error;
#ifdef OPENGPU_CS_NATIVE_LAYOUT
static uint64_t fixture_native_layout;
static unsigned native_layout_queries;
static int native_layout_error;
static uint64_t fixture_native_caps[OPENGPU_PARAM_NATIVE_CAPS_WORDS];
static unsigned native_caps_queries;
static int native_caps_fail_word=-1, native_caps_error;
#endif
static unsigned fail_request, fail_nth, fail_seen, wait_error, status_error, map_error;
static unsigned wait_error_kind, status_error_kind;
static unsigned last_compute_context, canceled, rejected_programs;
static struct pipe_resource *held_resources[4];
static unsigned uniform_submissions, uniform_snapshots;
static unsigned uniform_producer_handle, uniform_mutate_after_snapshot;
static uint32_t uniform_producer_value;
static unsigned indirect_publish_handle, indirect_publications;
static uint32_t indirect_publish_grid[3];
static void (*uniform_capture)(const struct drm_opengpu_compute_uniform_resources *,
    const struct job *, const struct drm_opengpu_resource *);
/* Optional real-submission capture for explicit compiler/CU qualification.
 * It observes the validated request and completed immutable snapshots. The
 * default CPU corpus has no observer and keeps its exact 51-dispatch contract. */
static void (*submission_capture)(const struct drm_opengpu_compute_storage *,
    const struct context *,const struct job *,const struct drm_opengpu_compute_uniform_resources *);
/* Optional composition through actual extracted kernel resource bodies. */
static int (*resource_ioctl)(unsigned long request, void *arg);
static void (*resource_finish)(unsigned sync, int execute);
/* Optional RTL input capture. Default CPU coverage and submission counts are
 * unchanged. Large resources retain their real view sizes; this OS model only
 * allocates a 64KiB prefix, and the independent VM must stay inside it. */
static const char *artifact_dir, *artifact_case;
static const struct tgsi_token *artifact_tokens;
static unsigned artifact_token_count, artifact_captures;

static FILE *artifact_file(const char *suffix)
{
    char path[4096];
    int n=snprintf(path,sizeof(path),"%s/%s.%s",artifact_dir,artifact_case,suffix);
    assert(n>0 && (size_t)n<sizeof(path));
    FILE *f=fopen(path,"wx");assert(f);return f;
}
static void capture_compute(const struct drm_opengpu_compute_storage *p,
                            const struct context *c)
{
    if (!artifact_case) return;
    const struct drm_opengpu_resource *shader=&c->b[p->launch.shader_slot];
    const struct drm_opengpu_resource *karg=&c->b[p->launch.kernarg_slot];
    const uint32_t *words=(const uint32_t *)(bos[shader->handle].data+shader->offset);
    unsigned count=(unsigned)shader->size/4,used=0,load=0,loads=0;
    for(unsigned i=0;i<count;i++) {
        if(words[i]==0x30500073u){assert(!used);used=i+1;}
        if((words[i]&127)==0x2b && (words[i]>>26)==15){load=i;loads++;}
    }
    assert(used && used<=512 && loads==1 && artifact_tokens && artifact_token_count);
    for(unsigned i=used;i<count;i++)assert(!words[i]);
    FILE *f=artifact_file("words");
    for(unsigned i=0;i<count;i++)assert(fprintf(f,"%08x\n",words[i])==9);
    assert(!fclose(f));
    f=artifact_file("tgsi");assert(fwrite(artifact_tokens,4,artifact_token_count,f)==artifact_token_count);assert(!fclose(f));
    f=artifact_file("kernarg");assert(fwrite(bos[karg->handle].data+karg->offset,1,12,f)==12);assert(!fclose(f));
    f=artifact_file("meta");
    fprintf(f,"format=1\ncontext=%u\nshader.slot=%u\nshader.handle=%u\nshader.offset=%llu\nshader.bytes=%llu\ncode.words=%u\nfirstLoad.word=%u\nkernarg.slot=%u\nkernarg.handle=%u\nkernarg.bytes=%llu\n",
        p->launch.context_id,p->launch.shader_slot,shader->handle,(unsigned long long)shader->offset,
        (unsigned long long)shader->size,used,load,p->launch.kernarg_slot,karg->handle,(unsigned long long)karg->size);
    for(unsigned a=0;a<3;a++)fprintf(f,"grid.%u=%u\nlocal.%u=%u\n",a,p->launch.grid[a],a,p->launch.local[a]);
    for(unsigned i=0;i<4;i++){
        const struct drm_opengpu_resource *v=&c->b[p->bindings[i].slot];
        assert(p->bindings[i].slot==35+i && v->type==OPENGPU_RESOURCE_COMPUTE_STORAGE);
        fprintf(f,"binding.%u.slot=%u\nbinding.%u.handle=%u\nbinding.%u.offset=%llu\nbinding.%u.bytes=%llu\nbinding.%u.access=%u\n",
            i,p->bindings[i].slot,i,v->handle,i,(unsigned long long)v->offset,
            i,(unsigned long long)v->size,i,p->bindings[i].access);
    }
    assert(!ferror(f) && !fclose(f));artifact_captures++;
}

static void maybe_free(unsigned h)
{
    struct bo *b=&bos[h];
    if (!b->handle && !b->maps && !b->binds && !b->jobs && b->data) {
        if (b->sparse) assert(!munmap(b->data,(size_t)b->size));
        else free(b->data);
        b->data=NULL;
    }
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
    if (j->kind == 4) { assert(resource_finish); resource_finish(s, execute && !j->status); }
    if (execute && !j->status) {
        if (j->kind==1 && semantic) {
            struct drm_opengpu_compute_storage *cmd=&j->compute;
            struct context *c=&contexts[j->context];
            struct cs_vm_view m[4]={{0}};
            struct drm_opengpu_resource *shader=&c->b[cmd->launch.shader_slot];
            for (unsigned i=0;i<4;i++) if (cmd->bindings[i].slot) {
                struct drm_opengpu_resource *v=&c->b[cmd->bindings[i].slot];
                m[i]=(struct cs_vm_view){(uint32_t *)(bos[v->handle].data+v->offset),v->size,cmd->bindings[i].access};
                if(artifact_case || j->uniform) {
                    assert(v->offset<65536);m[i].bytes=65536-v->offset;
                } else assert(v->offset+v->size<=65536);
            }
            assert(cmd->launch.grid[0]<=OPENGPU_CS_CALLBACK_MAX_GRID_AXIS &&
                cmd->launch.grid[1]<=OPENGPU_CS_CALLBACK_MAX_GRID_AXIS &&
                cmd->launch.grid[2]<=OPENGPU_CS_CALLBACK_MAX_GRID_AXIS);
            for (uint32_t z=0;z<cmd->launch.grid[2];z++) for(uint32_t y=0;y<cmd->launch.grid[1];y++)
                for(uint32_t x=0;x<cmd->launch.grid[0];x++) {
                    uint32_t group[3]={x,y,z};
                    cs_vm_group_uniforms((uint32_t *)bos[shader->handle].data,shader->size/4,
                        cmd->launch.grid,group,cmd->launch.local,m,j->uniforms);
                }
        } else if (j->kind==2) {
            assert(j->fill.destination_offset+j->fill.bytes<=65536);
            uint32_t *p=(uint32_t *)(bos[j->fill.destination_handle].data+j->fill.destination_offset);
            for(unsigned i=0;i<j->fill.bytes/4;i++)p[i]=j->fill.pattern;
        } else if (j->kind==3 && j->invalidate.handle==indirect_publish_handle) {
            /* Model CPU visibility changing only once the producer's cache
             * invalidation completes, before the consumer snapshots counts. */
            memcpy(bos[indirect_publish_handle].data,indirect_publish_grid,sizeof(indirect_publish_grid));
            indirect_publish_handle=0;indirect_publications++;
        }
    }
    sync_status[s]=(unsigned)(j->status ? j->status : 1);
    for (unsigned h=1;h<next_bo;h++)if(j->refs[h]) { assert(bos[h].jobs); bos[h].jobs--; maybe_free(h); }
    for (unsigned i=0;i<13;i++) { free(j->uniforms[i].data); j->uniforms[i].data=NULL; }
    j->live=0;
    if(contexts[j->context].closing){
        unsigned live=0;
        for(unsigned i=1;i<next_sync;i++)live|=jobs[i].live && jobs[i].context==j->context;
        if(!live){
            for(unsigned i=1;i<=OPENGPU_MAX_RESOURCE_BINDINGS;i++)drop_binding(&contexts[j->context].b[i]);
            contexts[j->context].closing=0;
        }
    }
}

/* These are the only interposed primitives; compile/dispatch/resource helpers
 * themselves are production bodies, linked separately. */
int cs_fixture_ioctl(int fd,unsigned long request,...)
{
    va_list ap;void *arg;
    va_start(ap,request);arg=va_arg(ap,void *);va_end(ap);
    assert(fd==101 || fd>=20000);
    ioctl_calls++;
    if(request==DRM_IOCTL_OPENGPU_RESOURCE_BIND || request==DRM_IOCTL_OPENGPU_RESOURCE_UNBIND) binding_attempts++;
    if(request==DRM_IOCTL_OPENGPU_COMPUTE_RESOURCES || request==DRM_IOCTL_OPENGPU_COMPUTE_IMAGE_RESOURCES ||
        request==DRM_IOCTL_OPENGPU_COMPUTE_UNIFORM_RESOURCES ||
        request==DRM_IOCTL_OPENGPU_COMPUTE_COUNTER_SOURCE_RESOURCES) resource_request_attempts++;
    if(request==DRM_IOCTL_OPENGPU_INVALIDATE){invalidate_attempts++;last_invalidate=*(struct drm_opengpu_invalidate *)arg;}
    if(request==fail_request && ++fail_seen==fail_nth){errno=ENOMEM;return -1;}
    if(resource_ioctl && (request==DRM_IOCTL_OPENGPU_COMPUTE_STORAGE || request==DRM_IOCTL_OPENGPU_COMPUTE_RESOURCES ||
        request==DRM_IOCTL_OPENGPU_COMPUTE_IMAGE_RESOURCES ||
        request==DRM_IOCTL_OPENGPU_COMPUTE_UNIFORM_RESOURCES ||
        request==DRM_IOCTL_OPENGPU_COMPUTE_COUNTER_SOURCE_RESOURCES)) return resource_ioctl(request,arg);
    if(request==DRM_IOCTL_OPENGPU_GET_PARAM){
        struct drm_opengpu_param *p=arg;
#ifdef OPENGPU_CS_NATIVE_LAYOUT
        if(p->param>=OPENGPU_PARAM_NATIVE_CAPS_BASE &&
           p->param<OPENGPU_PARAM_NATIVE_CAPS_BASE+OPENGPU_PARAM_NATIVE_CAPS_WORDS) {
            unsigned word=p->param-OPENGPU_PARAM_NATIVE_CAPS_BASE;
            assert(!p->pad);native_caps_queries++;
            if((int)word==native_caps_fail_word){errno=native_caps_error;return -1;}
            p->value=fixture_native_caps[word];return 0;
        }
#endif
        switch(p->param){
        case OPENGPU_PARAM_CAPABILITIES:p->value=OPENGPU_CAP_NATIVE_PIXEL_FORMATS|OPENGPU_CAP_FRAGMENT_CORE;break;
        case OPENGPU_PARAM_DRIVER_FEATURES:p->value=features;break;
#ifdef OPENGPU_CS_NATIVE_LAYOUT
        case OPENGPU_PARAM_NATIVE_LAYOUT:
            native_layout_queries++;
            if(native_layout_error){errno=native_layout_error;return -1;}
            p->value=fixture_native_layout;break;
#endif
        case OPENGPU_PARAM_COMPUTE_LOCAL_ITEMS:p->value=limit_items;break;
        case OPENGPU_PARAM_COMPUTE_LOCAL_AXIS:p->value=limit_axis;break;
        case OPENGPU_PARAM_COMPUTE_SHARED_BASE:
        case OPENGPU_PARAM_COMPUTE_SHARED_BYTES:
        case OPENGPU_PARAM_COMPUTE_SHARED_LANES:
            if(shared_query_error){errno=ENOTTY;return -1;}
            p->value=p->param==OPENGPU_PARAM_COMPUTE_SHARED_BASE?limit_shared_base:
                p->param==OPENGPU_PARAM_COMPUTE_SHARED_BYTES?limit_shared_bytes:limit_shared_lanes;
            break;
        default:assert(!"unexpected param");
        }return 0;
    }
    if(request==DRM_IOCTL_MODE_GETRESOURCES){errno=ENODEV;return -1;}
    if(request==DRM_IOCTL_OPENGPU_CONTEXT_CREATE){
        struct drm_opengpu_context *p=arg;assert(!p->id && next_context<N);p->id=next_context++;contexts[p->id].live=1;return 0;
    }
    if(request==DRM_IOCTL_OPENGPU_CONTEXT_DESTROY){
        struct drm_opengpu_context *p=arg;assert(contexts[p->id].live);
        if(defer_namespace_destroy){
            /* Namespace loss is not retirement: a later explicit device/free
             * event releases job refs and the still-owned VM bindings. */
            unsigned live=0;
            for(unsigned s=1;s<next_sync;s++)live|=jobs[s].live && jobs[s].context==p->id;
            contexts[p->id].live=0;contexts[p->id].closing=live;
            if(!live)for(unsigned i=1;i<=OPENGPU_MAX_RESOURCE_BINDINGS;i++)drop_binding(&contexts[p->id].b[i]);
            return 0;
        }
        for(unsigned s=1;s<next_sync;s++)if(jobs[s].live && jobs[s].context==p->id)finish_job(s,0);
        for(unsigned i=1;i<=OPENGPU_MAX_RESOURCE_BINDINGS;i++)drop_binding(&contexts[p->id].b[i]);
        contexts[p->id].live=0;return 0;
    }
    if(request==DRM_IOCTL_OPENGPU_CREATE_BUFFER){
        struct drm_opengpu_create_buffer *p=arg;unsigned h=next_bo++;
        assert(h<N && p->width && p->height && p->bpp==32);
        p->pitch=(p->width*4+63)&~63u;p->size=(uint64_t)p->pitch*p->height;
        if(page_pad_next){p->size=(p->size+4095)&~UINT64_C(4095);page_pad_next=0;}
        p->handle=h;p->offset=(uint64_t)h*4096;
        bos[h]=(struct bo){.handle=1,.size=p->size,.sparse=sparse_next};sparse_next=0;
        /* Only the explicit publication case reserves the full virtual BO;
         * touching its first/last bytes does not initialize 128MiB of RAM. */
        bos[h].data=bos[h].sparse?mmap(NULL,(size_t)p->size,PROT_READ|PROT_WRITE,
            MAP_PRIVATE|MAP_ANONYMOUS,-1,0):calloc(1,65536);
        assert(bos[h].data && bos[h].data!=MAP_FAILED);return 0;
    }
    if(request==DRM_IOCTL_GEM_CLOSE){struct drm_gem_close *p=arg;assert(bos[p->handle].handle);bos[p->handle].handle=0;maybe_free(p->handle);return 0;}
    if(request==DRM_IOCTL_OPENGPU_RESOURCE_BIND){
        const struct drm_opengpu_resource *p=arg;assert(contexts[p->context_id].live && p->slot && p->slot<=OPENGPU_MAX_RESOURCE_BINDINGS);
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
    if(request==DRM_IOCTL_SYNCOBJ_DESTROY){struct drm_syncobj_destroy *p=arg;assert(live_sync[p->handle]);live_sync[p->handle]=0;if(destroy_errno)errno=(int)destroy_errno;return 0;}
    if(request==DRM_IOCTL_OPENGPU_COMPUTE_STORAGE || request==DRM_IOCTL_OPENGPU_COMPUTE_UNIFORM_RESOURCES){
        const struct drm_opengpu_compute_uniform_resources *gum =
            request==DRM_IOCTL_OPENGPU_COMPUTE_UNIFORM_RESOURCES ? arg : NULL;
        struct drm_opengpu_compute_storage combined={0};
        if(gum){
            assert(features&OPENGPU_DRIVER_FEATURE_COMPUTE_UNIFORMS);
            struct drm_opengpu_compute_counter_resources empty={0};
            empty.launch=gum->resources.launch;
            memcpy(empty.bindings,gum->resources.bindings,sizeof(empty.bindings));
            empty.resource_flags=OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE;
            assert(!memcmp(&empty,&gum->resources,sizeof(empty)));
            assert(gum->version==1 && !(gum->active_mask&~4095u) && !gum->pad[0] && !gum->pad[1]);
            combined.launch=gum->resources.launch;
            memcpy(combined.bindings,gum->resources.bindings,sizeof(combined.bindings));
        }
        const struct drm_opengpu_compute_storage *p=gum?&combined:arg;unsigned s=p->launch.out_syncobj;
        struct context *c=&contexts[p->launch.context_id];assert(c->live && live_sync[s] && !jobs[s].live);
        const struct drm_opengpu_resource *shader=&c->b[p->launch.shader_slot],*karg=&c->b[p->launch.kernarg_slot];
        assert(shader->type==OPENGPU_RESOURCE_COMPUTE_SHADER && shader->size==2048);
        assert(karg->type==OPENGPU_RESOURCE_COMPUTE_KERNARG && (karg->flags&OPENGPU_RESOURCE_UNCACHED));
        unsigned product=p->launch.local[0]*p->launch.local[1]*p->launch.local[2];assert(product && product<=limit_items);
        if(gum) assert(opengpu_compute_shader_validate_words_uniform_resources(
            (uint32_t *)bos[shader->handle].data,shader->size/4,karg->size,product,
            false,false,false,true,false,false,
            !!(features&OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_ATOMICS),false));
        else assert(opengpu_compute_shader_validate_words_all_resources(
            (uint32_t *)bos[shader->handle].data,shader->size/4,karg->size,product,
            false,false,false,true,false,false,
            !!(features&OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_ATOMICS)));
        assert(!memcmp(bos[karg->handle].data,p->launch.grid,12));
        capture_compute(p,c);
        jobs[s]=(struct job){.live=1,.kind=1,.uniform=!!gum,.context=p->launch.context_id,.compute=*p,
            .status=status_error && (!status_error_kind || status_error_kind==1)? -EIO:0};
        retain_job(&jobs[s],shader->handle);retain_job(&jobs[s],karg->handle);
        for(unsigned i=0;i<4;i++)if(p->bindings[i].slot){assert(p->bindings[i].slot==35+i);retain_job(&jobs[s],c->b[35+i].handle);}
        if(gum){
            /* This is the modeled kernel boundary, not a running kernel. The
             * actual driver source must still receive the original GEM/view.
             * Publish a modeled producer before its one immutable copy. */
            if(uniform_producer_handle){
                assert(bos[uniform_producer_handle].data);
                memcpy(bos[uniform_producer_handle].data,&uniform_producer_value,4);
                uniform_producer_handle=0;
            }
            for(unsigned i=0;i<13;i++){
                const struct drm_opengpu_compute_uniform_view *v=i?&gum->blocks[i-1]:&gum->defaults;
                unsigned active=i?!!(gum->active_mask&(1u<<(i-1))):!!v->handle;
                if(!active){assert(!v->handle&&!v->bytes&&!v->offset);continue;}
                assert(v->handle && v->bytes && !(v->offset&3) && !(v->bytes&3));
                assert(v->bytes<=(i?16384u:4096u));
                assert(v->offset<=bos[v->handle].size && v->bytes<=bos[v->handle].size-v->offset);
                assert(v->offset+v->bytes<=65536);
                retain_job(&jobs[s],v->handle);
                jobs[s].uniforms[i]=(struct cs_vm_view){malloc(v->bytes),v->bytes,1};
                assert(jobs[s].uniforms[i].data);
                memcpy(jobs[s].uniforms[i].data,bos[v->handle].data+v->offset,v->bytes);
                uniform_snapshots++;
                if(uniform_mutate_after_snapshot)memset(bos[v->handle].data+v->offset,0x99,v->bytes);
            }
            if(uniform_capture)uniform_capture(gum,&jobs[s],shader);
            uniform_submissions++;
        }
        if(submission_capture)submission_capture(p,c,&jobs[s],gum);
        last_compute_context=p->launch.context_id;computes++;return 0;
    }
    if(request==DRM_IOCTL_OPENGPU_FILL){
        struct drm_opengpu_fill *p=arg;unsigned s=p->out_syncobj;assert(live_sync[s]);
        jobs[s]=(struct job){.live=1,.kind=2,.context=p->context_id,.fill=*p};retain_job(&jobs[s],p->destination_handle);fills++;return 0;
    }
    if(request==DRM_IOCTL_OPENGPU_INVALIDATE){
        const struct drm_opengpu_invalidate *p=arg;unsigned s=p->out_syncobj;
        assert(contexts[p->context_id].live && live_sync[s] && !jobs[s].live);
        assert(p->handle && p->handle<next_bo && bos[p->handle].handle);
        assert(!p->flags && !p->in_syncobj && !p->wait_event && !p->signal_event && !p->pad);
        assert(p->bytes && !(p->offset&63) && !(p->bytes&63));
        assert(p->offset<=bos[p->handle].size && p->bytes<=bos[p->handle].size-p->offset);
        jobs[s]=(struct job){.live=1,.kind=3,.context=p->context_id,.invalidate=*p,
            .status=status_error && (!status_error_kind || status_error_kind==3)?-EIO:0};
        retain_job(&jobs[s],p->handle);invalidates++;return 0;
    }
    if(request==DRM_IOCTL_SYNCOBJ_WAIT){
        struct drm_syncobj_wait *p=arg;unsigned s=*(uint32_t *)(uintptr_t)p->handles;
        assert(p->count_handles==1 && live_sync[s]);
        if (!jobs[s].live) { assert(sync_status[s]); return 0; }
        if(jobs[s].kind==3)invalidate_waits++;else waits++;
        for(unsigned h=1;h<next_bo;h++)if(jobs[s].refs[h])assert(bos[h].data && bos[h].jobs);
        if(jobs[s].kind==1){
            for(unsigned i=0;i<4;i++)if(held_resources[i])assert(held_resources[i]->reference.count>=2);
            held_checks++;
        }
        if(wait_error && (!wait_error_kind || jobs[s].kind==wait_error_kind)){errno=ETIME;return -1;}
        finish_job(s,1);return 0;
    }
    if(request==DRM_IOCTL_SYNCOBJ_HANDLE_TO_FD){struct drm_syncobj_handle *p=arg;assert(!jobs[p->handle].live);p->fd=20000+p->handle;exported[p->handle]++;return 0;}
    if(request==SYNC_IOC_FILE_INFO){struct sync_file_info *p=arg;unsigned s=(unsigned)fd-20000;assert(exported[s]);p->status=(int)sync_status[s];return 0;}
    fprintf(stderr,"unexpected ioctl %lx\n",request);abort();
}
int cs_fixture_dup(int fd){assert(fd==7);return 101;}
int cs_fixture_close(int fd){if(fd>=20000){unsigned s=(unsigned)fd-20000;assert(exported[s]);exported[s]--;}else{assert(fd==101);render_closes++;}return 0;}
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
void u_default_clear_buffer(struct pipe_context *p,struct pipe_resource *r,unsigned o,unsigned n,const void *v,int s)
{(void)p;(void)r;(void)o;(void)n;(void)v;(void)s;assert(!"unexpected generic clear");}
#ifndef OPENGPU_CS_REAL_MESA_SURFACES
void util_resource_copy_region(struct pipe_context *p,struct pipe_resource *d,unsigned dl,unsigned x,unsigned y,unsigned z,
                               struct pipe_resource *s,unsigned sl,const struct pipe_box *b)
{(void)p;(void)d;(void)dl;(void)x;(void)y;(void)z;(void)s;(void)sl;(void)b;assert(!"unexpected generic copy");}
void util_clear_render_target(struct pipe_context *p,struct pipe_surface *s,const union pipe_color_union *c,
                              unsigned x,unsigned y,unsigned w,unsigned h)
{(void)p;(void)s;(void)c;(void)x;(void)y;(void)w;(void)h;assert(!"unexpected generic render-target clear");}
#endif
#ifndef OPENGPU_CS_REAL_TGSI_DUMP
void tgsi_dump(const struct tgsi_token *t,unsigned f){(void)t;(void)f;assert(!"unexpected debug dump");}
#endif

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
static void insn3(struct builder *b,unsigned op,unsigned di,unsigned mask,
                  struct tgsi_src_register a,struct tgsi_src_register z,struct tgsi_src_register third)
{struct tgsi_full_instruction i=tgsi_default_full_instruction();i.Instruction.Opcode=op;
 i.Instruction.NumDstRegs=1;i.Instruction.NumSrcRegs=3;i.Dst[0].Register.File=TGSI_FILE_TEMPORARY;
 i.Dst[0].Register.Index=(int)di;i.Dst[0].Register.WriteMask=mask;
 i.Src[0].Register=a;i.Src[1].Register=z;i.Src[2].Register=third;
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
static uint32_t predicate_source(unsigned i)
{
 static const uint32_t values[]={0,1,0x7fffffffu,0x80000000u,UINT32_MAX,0x80000001u,0xfffffffeu,0x12345678u};
 return values[i%8];
}
/* Independent full-word oracle, deliberately expressed as C conditions rather
 * than native mask instructions. p[] is the original XYZW comparison tuple. */
static uint32_t predicate_expected(unsigned binding,unsigned i,uint32_t input,uint32_t seed)
{
 uint32_t value=input+seed;
 int p[]={value==0,value!=0,value<128,value>=0x80000000u};
 if(binding==1)return p[0]?0xdeadbeefu:value;
 if(binding==2)return (p[0]?p[3]:p[0]) | (p[0]?p[2]:p[1])<<1 |
     (p[0]?p[1]:p[2])<<2 | (p[0]?p[0]:p[3])<<3;
 assert(binding==3);return value?seed:i;
}
static void predicate_program(struct builder *b,uint32_t seed)
{
 begin(b,PIPE_SHADER_COMPUTE);append_decl(b,TGSI_FILE_TEMPORARY,0,3,-1);append_decl(b,TGSI_FILE_BUFFER,0,3,-1);
 append_decl(b,TGSI_FILE_SYSTEM_VALUE,0,0,TGSI_SEMANTIC_THREAD_ID);append_decl(b,TGSI_FILE_SYSTEM_VALUE,1,1,TGSI_SEMANTIC_BLOCK_ID);
 append_decl(b,TGSI_FILE_SYSTEM_VALUE,2,2,TGSI_SEMANTIC_BLOCK_SIZE);append_decl(b,TGSI_FILE_SYSTEM_VALUE,3,3,TGSI_SEMANTIC_GRID_SIZE);
 immediate(b,2,seed,0xdeadbeefu,7);immediate(b,1,2,4,8);immediate(b,0,128,0x80000000u,UINT32_MAX);
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
 insn(b,TGSI_OPCODE_USEQ,TGSI_FILE_TEMPORARY,3,1,T(2,0),src(TGSI_FILE_IMMEDIATE,2,0));
 insn(b,TGSI_OPCODE_USNE,TGSI_FILE_TEMPORARY,3,2,T(2,0),src(TGSI_FILE_IMMEDIATE,2,0));
 insn(b,TGSI_OPCODE_USLT,TGSI_FILE_TEMPORARY,3,4,T(2,0),src(TGSI_FILE_IMMEDIATE,2,1));
 insn(b,TGSI_OPCODE_USGE,TGSI_FILE_TEMPORARY,3,8,T(2,0),src(TGSI_FILE_IMMEDIATE,2,2));
 insn3(b,TGSI_OPCODE_UCMP,2,2,T(3,0),I(2),T(2,0));
 insn(b,TGSI_OPCODE_STORE,TGSI_FILE_BUFFER,1,1,T(1,0),T(2,1));
 insn3(b,TGSI_OPCODE_UCMP,2,2,T(2,0),I(1),T(0,0));
 insn(b,TGSI_OPCODE_STORE,TGSI_FILE_BUFFER,3,1,T(1,0),T(2,1));
 struct tgsi_src_register condition=T(3,0),a=T(3,0),z=T(3,0);
 a.SwizzleX=3;a.SwizzleY=2;a.SwizzleZ=1;a.SwizzleW=0;
 z.SwizzleY=1;z.SwizzleZ=2;z.SwizzleW=3;
 insn3(b,TGSI_OPCODE_UCMP,3,15,condition,a,z); /* all three sources alias dst */
 for(unsigned c=0;c<4;c++)insn(b,TGSI_OPCODE_AND,TGSI_FILE_TEMPORARY,2,1u<<c,T(3,c),src(TGSI_FILE_IMMEDIATE,1,c));
 for(unsigned c=1;c<4;c++)insn(b,TGSI_OPCODE_OR,TGSI_FILE_TEMPORARY,2,1,T(2,0),T(2,c));
 insn(b,TGSI_OPCODE_STORE,TGSI_FILE_BUFFER,2,1,T(1,0),T(2,0));
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
 for(unsigned c=1;c<next_context;c++)assert(!contexts[c].live && !contexts[c].closing);
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

static void predicate_full_words(void)
{
 unsigned checked=0;
 for(unsigned rhs=0;rhs<8;rhs++){
  struct builder b;begin(&b,PIPE_SHADER_COMPUTE);
  append_decl(&b,TGSI_FILE_TEMPORARY,0,2,-1);append_decl(&b,TGSI_FILE_BUFFER,0,1,-1);
  append_decl(&b,TGSI_FILE_SYSTEM_VALUE,0,0,TGSI_SEMANTIC_THREAD_ID);
  immediate(&b,2,4,predicate_source(rhs),0);
  insn(&b,TGSI_OPCODE_SHL,TGSI_FILE_TEMPORARY,0,1,S(0,0),I(0));
  insn(&b,TGSI_OPCODE_LOAD,TGSI_FILE_TEMPORARY,1,1,src(TGSI_FILE_BUFFER,0,0),T(0,0));
  const unsigned comparisons[]={TGSI_OPCODE_USEQ,TGSI_OPCODE_USNE,TGSI_OPCODE_USLT,TGSI_OPCODE_USGE};
  for(unsigned c=0;c<4;c++)insn(&b,comparisons[c],TGSI_FILE_TEMPORARY,2,1u<<c,T(1,0),I(2));
  insn(&b,TGSI_OPCODE_SHL,TGSI_FILE_TEMPORARY,0,1,S(0,0),I(1));
  struct tgsi_src_register values=T(2,0);values.SwizzleY=1;values.SwizzleZ=2;values.SwizzleW=3;
  insn(&b,TGSI_OPCODE_STORE,TGSI_FILE_BUFFER,1,15,T(0,0),values);
  insn(&b,TGSI_OPCODE_END,0,0,0,I(0),I(0));
  struct opengpu_cs_program out;assert(!opengpu_cs_emit(b.t,&out) && out.reads==1 && out.writes==2);
  assert(opengpu_compute_shader_validate_words_storage(out.code,out.words,12,128,false));
  uint32_t storage[4][1056],grid[]={1,1,1},group[]={0,0,0},local[]={128,1,1};
  struct cs_vm_view m[4];
  for(unsigned binding=0;binding<4;binding++){
   for(unsigned i=0;i<1056;i++)storage[binding][i]=0xdeadfaceu;
   if(!binding)for(unsigned i=0;i<1024;i++)storage[0][i+16]=predicate_source(i);
   m[binding]=(struct cs_vm_view){storage[binding]+16,4096,binding?3:1};
  }
  cs_vm_group(out.code,out.words,grid,group,local,m);
  for(unsigned binding=0;binding<4;binding++)for(unsigned i=0;i<1056;i++){
   uint32_t want=0xdeadfaceu;
   if(!binding && i>=16 && i<1040)want=predicate_source(i-16);
   if(binding==1 && i>=16 && i<16+128*4){
    unsigned word=i-16;uint32_t a=predicate_source(word/4),z=predicate_source(rhs);
    int conditions[]={a==z,a!=z,a<z,a>=z};
    want=conditions[word%4]?UINT32_MAX:0;checked++;
   }
   assert(storage[binding][i]==want);
  }
 }
 assert(checked==4096);
 printf("Gallium TGSI raw comparisons:8 real-validator shaders,%u directly stored 32-bit comparison results/guards PASS\n",checked);
}

static void predicate_tests(struct pipe_context *p)
{
 predicate_full_words();
 const uint32_t seeds[]={0,1,0x7fffffffu,0x80000000u,UINT32_MAX};
 const uint32_t shapes[][3]={{128,1,1},{8,4,4},{5,3,7},{5,5,5}};
 unsigned checked=0;
 for(unsigned s=0;s<5;s++)for(unsigned shape=0;shape<4;shape++){
  struct builder b;predicate_program(&b,seeds[s]);struct opengpu_cs_program out;
  assert(!opengpu_cs_emit(b.t,&out) && out.words<=512 && out.reads==1 && out.writes==14);
  unsigned local=shapes[shape][0]*shapes[shape][1]*shapes[shape][2];
  assert(opengpu_compute_shader_validate_words_storage(out.code,out.words,12,local,false));
  uint32_t storage[4][1056],grid[]={2,1,1};struct cs_vm_view m[4];
  for(unsigned binding=0;binding<4;binding++){
   for(unsigned i=0;i<1056;i++)storage[binding][i]=0xdeadfaceu;
   if(!binding)for(unsigned i=0;i<1024;i++)storage[0][i+16]=predicate_source(i);
   m[binding]=(struct cs_vm_view){storage[binding]+16,4096,binding?3:1};
  }
  for(unsigned group=0;group<2;group++){
   uint32_t index[]={group,0,0};cs_vm_group(out.code,out.words,grid,index,shapes[shape],m);
  }
  for(unsigned binding=0;binding<4;binding++)for(unsigned i=0;i<1056;i++){
   uint32_t want=0xdeadfaceu;
   if(!binding && i>=16 && i<1040)want=predicate_source(i-16);
   if(binding && i>=16 && i<16+local*2)want=predicate_expected(binding,i-16,predicate_source(i-16),seeds[s]);
   assert(storage[binding][i]==want);
  }
  checked+=local*2;
 }
 /* Third-source packet/typing failures must leave the caller's output intact.
  * They use actual Mesa-built tokens, not a second parser implementation. */
 unsigned before=rejected_programs;
 for(unsigned k=0;k<9;k++){
  struct builder b;predicate_program(&b,0);
  unsigned pos=find_token(&b,TGSI_TOKEN_TYPE_INSTRUCTION,TGSI_OPCODE_UCMP);
  struct tgsi_instruction *instruction=(struct tgsi_instruction *)(b.t+pos);
  struct tgsi_src_register *third=(struct tgsi_src_register *)(b.t+pos+4);
  switch(k){
   case 0:instruction->NumSrcRegs=2;break;
   case 1:third->File=TGSI_FILE_TEMPORARY;third->Index=31;break;
   case 2:third->File=TGSI_FILE_IMMEDIATE;third->Index=15;break;
   case 3:third->Indirect=1;break;
   case 4:third->Dimension=1;break;
   case 5:third->Absolute=1;break;
   case 6:third->Negate=1;break;
   case 7:instruction->Opcode=TGSI_OPCODE_UADD;break;
   default:instruction->NrTokens--;break;
  }
  reject_program(p,&b);
 }
 struct builder b;begin(&b,PIPE_SHADER_COMPUTE);append_decl(&b,TGSI_FILE_TEMPORARY,0,0,-1);immediate(&b,0,1,0x80000000u,UINT32_MAX);
 for(unsigned i=0;i<30;i++)insn3(&b,TGSI_OPCODE_UCMP,0,15,I(0),I(1),I(2));
 insn(&b,TGSI_OPCODE_END,0,0,0,I(0),I(0));reject_program(p,&b); /* expansion still bounded by 512 */
 assert(checked==4860 && rejected_programs==before+10);
 printf("Gallium TGSI predicates:20 real-validator/word-VM cases,%u work-items,full-word masks/nonzero UCMP/parallel alias,10 fail-closed cases PASS\n",checked);
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
  p->launch_grid(p,g);assert(computes==before+cases[k].accepted);
  if(wait_error){
   assert(canceled==old_canceled && live_contexts()==2);
   unsigned old_invalidates=invalidates;
   p->launch_grid(p,g); /* not even namespace deletion may replay an unsignaled job */
   assert(computes==before+1 && canceled==old_canceled && invalidates==old_invalidates && live_contexts()==2);
  }else if(status_error){
   /* The failed producer is signaled, but the separate publication fence
    * also reports failure. Keep the poisoned private namespace and BOs. */
   assert(canceled==old_canceled && live_contexts()==2);
  }else assert(live_contexts()==1);
  fail_request=fail_nth=fail_seen=map_error=wait_error=status_error=0;
  if(k==12 || k==13){
   p->launch_grid(p,g); /* observe original fence, publish, retire namespace; no new dispatch */
   assert(computes==before+1 && canceled==old_canceled && live_contexts()==1);
  }
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

static void artifact_tests(void)
{
 artifact_dir=getenv("OPENGPU_COMPUTE_ARTIFACTS");if(!artifact_dir)return;
 features=OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE|OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_BYTE_VIEWS;
 struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s);
 struct pipe_context *p=s->context_create(s,NULL,0);assert(p);
 struct pipe_resource *r[4];
 for(unsigned i=0;i<4;i++){r[i]=buffer(s,0x8000000u);assert(r[i]);held_resources[i]=r[i];}
 const char *names[]={"healthy-a","bounds","denied","healthy-b"};
 for(unsigned n=0;n<4;n++){
  struct builder b;uint32_t seed=n==3?UINT32_MAX:0x12345u;predicate_program(&b,seed);
  struct pipe_compute_state state={.ir_type=PIPE_SHADER_IR_TGSI,.prog=b.t};
  void *cs=p->create_compute_state(p,&state);assert(cs);p->bind_compute_state(p,cs);
  views(p,r,0,0x8000000u,14);
  if(n==1){struct pipe_shader_buffer v={r[0],0,3};p->set_shader_buffers(p,PIPE_SHADER_COMPUTE,0,1,&v,0);}
  struct pipe_grid_info g={.grid={n==0||n==3?2:1,1,1},.block={8,4,4}};
  if(n==3)for(unsigned axis=0;axis<3;axis++)g.block[axis]=5;
  for(unsigned j=0;j<4;j++){
   uint32_t *data=pipe_opengpu_resource_map(opengpu_resource(r[j])->gpu);assert(data);
   for(unsigned i=0;i<16384;i++)data[i]=j==0 && i<1024?predicate_source(i):0xdeadfaceu;
  }
  artifact_case=names[n];artifact_tokens=b.t;artifact_token_count=b.h->HeaderSize+b.h->BodySize;
  /* Fault metadata goes through the same real admission/validator boundary.
   * OS completion is modeled here; only the explicit RTL qualification may
   * claim a bounds or Sv32 fault. No CPU VM fabricates that GPU outcome. */
  status_error=n==1||n==2;unsigned before=computes;
  p->launch_grid(p,&g);assert(computes==before+1 && artifact_captures==n+1);
  status_error=0;
  if(n==0||n==3)for(unsigned j=0;j<4;j++){
   const uint32_t *data=pipe_opengpu_resource_map(opengpu_resource(r[j])->gpu);
   for(unsigned i=0;i<16384;i++){
    uint32_t want=j==0 && i<1024?predicate_source(i):0xdeadfaceu;
    unsigned items=g.grid[0]*g.block[0]*g.block[1]*g.block[2];
    if(j && i<items)want=predicate_expected(j,i,predicate_source(i),seed);
    assert(data[i]==want);
   }
  }
  p->delete_compute_state(p,cs);artifact_case=NULL;artifact_tokens=NULL;
 }
 p->destroy(p);for(unsigned i=0;i<4;i++){held_resources[i]=NULL;pipe_resource_reference(&r[i],NULL);}s->destroy(s);
 assert(artifact_captures==4);printf("Gallium compute artifacts:4 actual validated predicate submissions,full4x128MiB/128+125-item metadata,2 independent word-VM healthy results PASS\n");
}

static void publication_range(struct pipe_context *p,struct pipe_resource *r,
                              int x,int width,uint64_t offset,uint64_t bytes)
{
 struct pipe_transfer *t=NULL;struct pipe_box b={.x=x,.width=width,.height=1,.depth=1};
 unsigned before=invalidates,old_waits=invalidate_waits;
 uint64_t size=pipe_opengpu_resource_size(opengpu_resource(r)->gpu);
 unsigned logical=r->width0;
 uint8_t *base=pipe_opengpu_resource_map(opengpu_resource(r)->gpu);
 uint8_t *data=p->buffer_map(p,r,0,PIPE_MAP_WRITE,&b,&t);
 assert(data==base+x && t && !t->stride && !t->layer_stride);
 assert(t->resource==r && !t->level && t->usage==PIPE_MAP_WRITE && !memcmp(&t->box,&b,sizeof(b)));
 data[0]=0x5a;data[width-1]=0xa5;
 p->buffer_unmap(p,t);
 assert(invalidates==before+1 && invalidate_waits==old_waits+1);
 assert(last_invalidate.handle && bos[last_invalidate.handle].data==base);
 assert(last_invalidate.offset==offset && last_invalidate.bytes==bytes);
 assert(!jobs[last_invalidate.out_syncobj].live && !live_sync[last_invalidate.out_syncobj]);
 assert(!bos[last_invalidate.handle].jobs && r->width0==logical);
 assert(pipe_opengpu_resource_size(opengpu_resource(r)->gpu)==size);
}

static void publication_reject(struct pipe_context *p,struct pipe_resource *r,
                               unsigned level,const struct pipe_box *box)
{
 struct pipe_transfer *t=(void *)(uintptr_t)1;unsigned calls=ioctl_calls;
 errno=0;assert(!p->buffer_map(p,r,level,PIPE_MAP_WRITE,box,&t));
 assert(errno==EINVAL && !t && ioctl_calls==calls);
}

static void buffer_publication_tests(void)
{
 struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s);
 struct pipe_context *p=s->context_create(s,NULL,0);assert(p);
 page_pad_next=1;struct pipe_resource *r=buffer(s,4097);assert(r);
 assert(r->width0==4097 && pipe_opengpu_resource_size(opengpu_resource(r)->gpu)==8192);
 const struct {int x,width;uint64_t offset,bytes;} cases[]={
  {0,1,0,64},{63,2,0,128},{64,64,64,64},{127,2,64,128},
  {4095,2,4032,128},{4096,1,4096,64},{1,4096,0,4160},{0,4097,0,4160}
 };
 for(unsigned i=0;i<sizeof(cases)/sizeof(cases[0]);i++)
  publication_range(p,r,cases[i].x,cases[i].width,cases[i].offset,cases[i].bytes);

 struct pipe_box b={.width=1,.height=1,.depth=1};
 publication_reject(p,r,1,&b);publication_reject(p,r,0,NULL);
 const struct pipe_box bad[]={
  {.x=-1,.width=1,.height=1,.depth=1},{.width=0,.height=1,.depth=1},
  {.width=-1,.height=1,.depth=1},{.x=4097,.width=1,.height=1,.depth=1},
  {.x=4096,.width=2,.height=1,.depth=1},{.x=1,.width=INT32_MAX,.height=1,.depth=1},
  {.x=INT32_MAX,.width=INT32_MAX,.height=1,.depth=1},
  {.y=1,.width=1,.height=1,.depth=1},{.y=-1,.width=1,.height=1,.depth=1},
  {.z=1,.width=1,.height=1,.depth=1},{.z=-1,.width=1,.height=1,.depth=1},
  {.width=1,.height=0,.depth=1},{.width=1,.height=2,.depth=1},
  {.width=1,.height=1,.depth=0},{.width=1,.height=1,.depth=2}
 };
 for(unsigned i=0;i<sizeof(bad)/sizeof(bad[0]);i++)publication_reject(p,r,0,&bad[i]);
 unsigned calls=ioctl_calls;errno=0;
 assert(!p->buffer_map(p,r,0,PIPE_MAP_WRITE,&b,NULL) && errno==EINVAL && ioctl_calls==calls);
 /* GEM bounds are checked independently, even if logical metadata is bad. */
 r->width0=8193;b.x=8191;b.width=2;publication_reject(p,r,0,&b);r->width0=4097;

 struct pipe_transfer *t=NULL;b=(struct pipe_box){.x=4096,.width=1,.height=1,.depth=1};
 calls=ioctl_calls;assert(p->buffer_map(p,r,0,PIPE_MAP_READ,&b,&t));p->buffer_unmap(p,t);
 assert(ioctl_calls==calls);pipe_resource_reference(&r,NULL);

 sparse_next=1;r=buffer(s,0x8000000u);assert(r);
 publication_range(p,r,0x7ffffff,1,0x7ffffc0,64);
 publication_range(p,r,0,0x8000000,0,0x8000000); /* metadata, only two touched bytes */
 pipe_resource_reference(&r,NULL);

 /* The texture callback retains texel addressing and its existing whole-GEM
  * publication contract; the byte-range change is only for PIPE_BUFFER. */
 struct pipe_resource image={.target=PIPE_TEXTURE_2D,.format=PIPE_FORMAT_R8G8B8A8_UNORM,
  .width0=16,.height0=16,.depth0=1,.array_size=1,.bind=PIPE_BIND_RENDER_TARGET};
 r=s->resource_create(s,&image);assert(r);uint8_t *base=pipe_opengpu_resource_map(opengpu_resource(r)->gpu);
 b=(struct pipe_box){.x=3,.y=4,.width=2,.height=2,.depth=1};unsigned before=invalidates;
 assert(p->texture_map(p,r,0,PIPE_MAP_WRITE,&b,&t)==base+4*64+3*4 && t->stride==64);
 p->texture_unmap(p,t);assert(invalidates==before+1 && !last_invalidate.offset && last_invalidate.bytes==1024);
 calls=ioctl_calls;assert(p->texture_map(p,r,0,PIPE_MAP_READ,&b,&t));p->texture_unmap(p,t);assert(ioctl_calls==calls);
 pipe_resource_reference(&r,NULL);p->destroy(p);

 const struct {unsigned request;int error;} failures[]={
  {DRM_IOCTL_SYNCOBJ_CREATE,ENOMEM},{DRM_IOCTL_OPENGPU_INVALIDATE,ENOMEM},
  {DRM_IOCTL_SYNCOBJ_WAIT,ETIME},{DRM_IOCTL_SYNCOBJ_HANDLE_TO_FD,ENOMEM},
  {SYNC_IOC_FILE_INFO,ENOMEM},{0,EIO}
 };
 for(unsigned i=0;i<sizeof(failures)/sizeof(failures[0]);i++){
  p=s->context_create(s,NULL,0);assert(p);r=buffer(s,4097);assert(r);
  b=(struct pipe_box){.x=63,.width=2,.height=1,.depth=1};
  assert(p->buffer_map(p,r,0,PIPE_MAP_WRITE,&b,&t));
  unsigned sync=next_sync,accepted=invalidates,attempted=invalidate_attempts,old_canceled=canceled;
  fail_request=failures[i].request;fail_nth=1;fail_seen=0;
  if(i==2){fail_request=0;wait_error=1;destroy_errno=ENOSPC;}
  status_error=i==5;if(status_error)destroy_errno=ENOSPC;
  errno=0;p->buffer_unmap(p,t);assert(errno==failures[i].error);
  assert(invalidates==accepted+(i>=2) && invalidate_attempts==attempted+(i!=0));
  if(i!=0)assert(!last_invalidate.offset && last_invalidate.bytes==128);
  for(unsigned q=sync;q<next_sync;q++)assert(!live_sync[q] && !exported[q]);
  unsigned pending=i==2,h=pending?last_invalidate.handle:0;
  if(pending)assert(jobs[last_invalidate.out_syncobj].live && bos[h].jobs==1);
  else if(i>=2)assert(!jobs[last_invalidate.out_syncobj].live);
  fail_request=fail_nth=fail_seen=wait_error=status_error=destroy_errno=0;
  /* A timed-out accepted job owns its backing beyond the application map,
   * GEM handle and syncobj; context cancellation is its final release. */
  pipe_resource_reference(&r,NULL);
  if(pending)assert(!bos[h].handle && !bos[h].maps && bos[h].jobs==1 && bos[h].data);
  p->destroy(p);
  if(pending)assert(canceled==old_canceled+1 && !bos[h].data && !bos[h].jobs);
  p=s->context_create(s,NULL,0);assert(p);r=buffer(s,4097);assert(r);
  publication_range(p,r,4096,1,4096,64);pipe_resource_reference(&r,NULL);p->destroy(p);
 }
 s->destroy(s);no_leaks();
 printf("Gallium buffer publication:8 bounded ranges,128MiB sparse endpoints/full metadata,19 invalid maps/no ioctl,READ/texture isolation,6 fence failure/recovery paths PASS (%u invalidate attempts,%u accepted,%u waits)\n",
  invalidate_attempts,invalidates,invalidate_waits);
}

static void indirect_grid_tests(void)
{
 struct pipe_screen *s=opengpu_drm_screen_create(7,NULL);assert(s);
 struct pipe_context *p=s->context_create(s,NULL,0);assert(p);
 struct builder b;program(&b,0x12345u);
 struct pipe_compute_state state={.ir_type=PIPE_SHADER_IR_TGSI,.prog=b.t};
 void *cs=p->create_compute_state(p,&state);assert(cs);p->bind_compute_state(p,cs);
 struct pipe_resource *r[4];
 for(unsigned i=0;i<4;i++){r[i]=buffer(s,8192);assert(r[i]);held_resources[i]=r[i];}
 initialize(r,0);views(p,r,0,4096,14);
 struct pipe_resource *args=buffer(s,64);assert(args);
 uint32_t *words=pipe_opengpu_resource_map(opengpu_resource(args)->gpu);assert(words);
 words[13]=2;words[14]=1;words[15]=1;
 /* The actual counts, not unused direct-grid fields, control submission. */
 struct pipe_grid_info g={.work_dim=1,.block={4,1,1},.grid={0,0,0},
                          .indirect=args,.indirect_offset=52};
 struct pipe_grid_info expected={.block={4,1,1},.grid={2,1,1}};
 unsigned before=computes;p->launch_grid(p,&g);assert(computes==before+1);
 check(r,0,&expected,0x12345u);
 struct pipe_grid_info bad=g;
 bad.indirect_offset=UINT32_MAX-7u;expect_no_submit(p,&bad);
 bad.indirect_offset=56;expect_no_submit(p,&bad); /* only eight bytes remain */
 bad.indirect_offset=1;expect_no_submit(p,&bad);
 args->width0=128;bad.indirect_offset=60;expect_no_submit(p,&bad);args->width0=64;
 words[14]=2;expect_no_submit(p,&g);words[14]=1; /* unused axis */
 struct pipe_screen *other=opengpu_drm_screen_create(7,NULL);assert(other);
 struct pipe_resource *foreign=buffer(other,64);assert(foreign);
 bad=g;bad.indirect=foreign;bad.indirect_offset=0;expect_no_submit(p,&bad);
 pipe_resource_reference(&foreign,NULL);other->destroy(other);
 for(unsigned axis=0;axis<3;axis++) {
  uint32_t saved=words[13+axis];words[13+axis]=0;
  bad=g;bad.work_dim=3;bad.grid[0]=bad.grid[1]=bad.grid[2]=1;
  expect_no_submit(p,&bad);words[13+axis]=saved;
 }
 /* Full uint32 dimensions are metadata-only here, with no giant traversal. */
 words[13]=UINT32_MAX;semantic=0;before=computes;p->launch_grid(p,&g);
 assert(computes==before+1);semantic=1;words[13]=2;

 /* A prior compute producer owns r[1]. Make its mapped counts visible only
  * when the pending invalidation finishes; the consumer must read after it. */
 initialize(r,0);
 struct pipe_grid_info producer={.block={4,1,1},.grid={1,1,1}};
 before=computes;p->launch_grid(p,&producer);assert(computes==before+1);
 uint32_t *published=pipe_opengpu_resource_map(opengpu_resource(r[1])->gpu);
 published[0]=0;published[1]=published[2]=1;
 indirect_publish_handle=pipe_opengpu_resource_handle(opengpu_resource(r[1])->gpu);
 indirect_publish_grid[0]=2;indirect_publish_grid[1]=indirect_publish_grid[2]=1;
 unsigned publications=indirect_publications;
 g.indirect=r[1];g.indirect_offset=0;
 p->launch_grid(p,&g);
 assert(computes==before+2 && indirect_publications==publications+1 && !indirect_publish_handle);
 check(r,0,&expected,0x12345u);

 /* A publication failure must never submit a consumer or read a stale grid.
  * Once the failed context retires, a fresh launch can proceed. */
 initialize(r,0);p->launch_grid(p,&producer);before=computes;
 fail_request=DRM_IOCTL_OPENGPU_INVALIDATE;fail_nth=1;fail_seen=0;errno=0;
 p->launch_grid(p,&g);assert(computes==before && errno==ENOMEM);
 fail_request=fail_nth=fail_seen=0;
 published[0]=2;published[1]=published[2]=1;
 p->launch_grid(p,&g);assert(computes==before+1);
 check(r,0,&expected,0x12345u);

 p->set_shader_buffers(p,PIPE_SHADER_COMPUTE,0,4,NULL,0);
 p->delete_compute_state(p,cs);
 for(unsigned i=0;i<4;i++){held_resources[i]=NULL;pipe_resource_reference(&r[i],NULL);}
 pipe_resource_reference(&args,NULL);p->destroy(p);s->destroy(s);no_leaks();
 printf("Gallium indirect compute: bounded snapshots, zero grids, padding and producer publication PASS\n");
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
 predicate_tests(p);
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
 bad=g;bad.indirect=r[0];bad.indirect_offset=UINT32_MAX-7u;expect_no_submit(p,&bad);
 bad.indirect_offset=8184;expect_no_submit(p,&bad);
 bad=g;bad.work_dim=4;expect_no_submit(p,&bad);bad=g;bad.grid[0]=0;expect_no_submit(p,&bad);
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
 /* Two accepted-timeout cases each add three polls of the original fence:
  * immediate reset, repeated poisoned call, and explicit safe recovery. */
 if(computes!=51 || waits!=60)fprintf(stderr,"callback counts submissions=%u waits=%u\n",computes,waits);
 assert(computes==51 && waits==60);indirect_grid_tests();artifact_tests();no_leaks();buffer_publication_tests();
 printf("Gallium compute callbacks: actual TGSI/pipe/lib/storage validator,5 shapes/128 items,4x128MiB exact metadata,legacy+byte views,14 failure/recovery types in both modes,graphics isolation,%u rejected programs PASS (%u submissions,%u waits)\n",rejected_programs,computes,waits);
 return 0;
}
