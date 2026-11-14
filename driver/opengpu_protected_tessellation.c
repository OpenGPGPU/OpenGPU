// SPDX-License-Identifier: GPL-2.0
/* Typed, untrusted GNQ/GNT4..6 graph -> immutable private owner. No ioctl. */
#include <linux/errno.h>
#include <linux/slab.h>
#include <linux/vmalloc.h>
#include <linux/string.h>
#include "opengpu_protected_tessellation.h"
#include "opengpu_tessellation_job_validate.h"
#include "opengpu_tessellation_uniform_sequence_abi.h"
#include "opengpu_tessellation_program_snapshot.h"
#include "opengpu_tessellation_uniform_snapshot.h"
#include "opengpu_native_program_snapshot.h"
#include "opengpu_native_uniform_snapshot.h"
#include "opengpu_native_advanced_blend_gfs.h"
#include "opengpu_typed_fragment_state_abi.h"
#include "opengpu_native_binned_abi.h"
#include "opengpu_private_snapshot.h"
#define PF(n) OPENGPU_TYPED_FRAGMENT_STATE_##n##_WORD
#define PW(n) OPENGPU_NATIVE_BINNED_FRAME_FRAME_##n##_WORD
struct pt_layout {
    const unsigned char *rules;
    u32 magic,version,state,bases,control_grid,control_helpers,control_private,control_arg,control_pc,control_local;
    u32 input_components,input_vertices,output_components,output_vertices,patch_components,task_id,spacing;
    u32 evaluation_grid,evaluation_helpers,evaluation_private,evaluation_arg,evaluation_pc,evaluation_local;
    u32 tes_outputs,selections,selection_first,vertex_stride,old_uniform,varyings,viewport;
};
#define PT_LAYOUT(P,R,M,V) { .rules=R,.magic=M,.version=V, \
 .state=P##_TEMPLATE_SOURCE_STATE_WORD,.bases=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_BASES0_WORD, \
 .control_grid=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_KERNEL_GRIDSIZE0_WORD, \
 .control_helpers=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_KERNEL_INVOCATIONS_HELPERS_WORD, \
 .control_private=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_KERNEL_INVOCATIONS_PRIVATEBASE_WORD, \
 .control_arg=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_KERNEL_KERNARGADDRESS_WORD, \
 .control_pc=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_KERNEL_KERNELPC_WORD, \
 .control_local=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_KERNEL_LOCALSIZE0_WORD, \
 .input_components=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_INPUTCOMPONENTS_WORD, \
 .input_vertices=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_INPUTVERTICES_WORD, \
 .output_components=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_OUTPUTCOMPONENTS_WORD, \
 .output_vertices=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_OUTPUTVERTICES_WORD, \
 .patch_components=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_PATCHCOMPONENTS_WORD, \
 .task_id=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_TASKID_WORD, \
 .spacing=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_SPACING_WORD, \
 .evaluation_grid=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_EVALUATIONKERNEL_GRIDSIZE0_WORD, \
 .evaluation_helpers=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_EVALUATIONKERNEL_INVOCATIONS_HELPERS_WORD, \
 .evaluation_private=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_EVALUATIONKERNEL_INVOCATIONS_PRIVATEBASE_WORD, \
 .evaluation_arg=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_EVALUATIONKERNEL_KERNARGADDRESS_WORD, \
 .evaluation_pc=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_EVALUATIONKERNEL_KERNELPC_WORD, \
 .evaluation_local=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_EVALUATIONKERNEL_LOCALSIZE0_WORD, \
 .tes_outputs=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_OUTPUTCOMPONENTS_WORD, \
 .selections=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_PATCHINPUTCOUNT_WORD, \
 .selection_first=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_PATCHINPUTS0_COMPONENT_WORD, \
 .vertex_stride=P##_TEMPLATE_STRIDE_WORD,.old_uniform=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_UNIFORMRESOURCES_WORD, \
 .varyings=P##_TEMPLATE_SOURCE_VIEWPORT_VARYINGSLOTS_WORD,.viewport=P##_TEMPLATE_SOURCE_VIEWPORT_VIEWPORT_EXTENTX_WORD }
static const struct pt_layout pt_layouts[]={
 PT_LAYOUT(OPENGPU_NATIVE_TESSELLATION_SEQUENCE,opengpu_tessellation_isoline_word_rules,0x474e5404u,4),
 PT_LAYOUT(OPENGPU_NATIVE_TESSELLATION_TRIANGLE_SEQUENCE,opengpu_tessellation_triangle_word_rules,0x474e5405u,5),
 PT_LAYOUT(OPENGPU_NATIVE_TESSELLATION_QUAD_SEQUENCE,opengpu_tessellation_quad_word_rules,0x474e5406u,6),
};
#undef PT_LAYOUT
static u32 pt_get(const void *p,u32 word)
{const u8 *b=(const u8 *)p+4u*word;return (u32)b[0]|(u32)b[1]<<8|(u32)b[2]<<16|(u32)b[3]<<24;}
static void pt_put(void *p,u32 word,u32 value)
{u8 *b=(u8 *)p+4u*word;b[0]=value;b[1]=value>>8;b[2]=value>>16;b[3]=value>>24;}
static bool pt_zero(const void *p,size_t bytes)
{const u8 *b=p;for(size_t i=0;i<bytes;i++)if(b[i])return false;return true;}
static bool pt_header(const void *p,u32 magic,u32 bytes)
{
 const u32 h[]={magic,bytes,1,16,16,16,4,4};
 for(u32 i=0;i<8;i++)if(pt_get(p,i)!=h[i])return false;
 return pt_zero((const u8 *)p+32,32);
}
bool opengpu_protected_tessellation_profile_valid(const struct opengpu_protected_tessellation_profile *p)
{return p->available && p->patch_zero_initialized && p->version==1 && p->domains==7 && (p->compute_units==2 || p->compute_units==4) &&
 p->lanes==16 && p->warps==8 && p->engines>=1 && p->engines<=4;}
static int pt_reloc(struct opengpu_private_snapshot *o,u32 from,u64 offset,u32 word,u32 to,u64 addend,bool limit)
{
 if(offset>(u64)(~(size_t)0)-4u*word)return -ERANGE;
 return opengpu_private_snapshot_relocate(o,from,(size_t)offset+4u*word,to,addend,
  limit?OPENGPU_PRIVATE_SNAPSHOT_LIMIT33:OPENGPU_PRIVATE_SNAPSHOT_VA32);
}
/* This complete kernel graph is captured before callbacks: caller mutation
 * cannot change later plans, identity, array lengths, scope or relocations. */
struct pt_capture {struct opengpu_protected_tessellation_source source;void *owned[7];};
static void pt_release(struct pt_capture *c)
{for(u32 i=0;i<7;i++)kvfree(c->owned[i]);}
static int pt_capture(struct pt_capture *c,const struct opengpu_protected_tessellation_source *s)
{
 u64 sizes[7]={512,2048,s->sequences_bytes,s->states_bytes,
  (u64)s->plan_count*sizeof(*s->plans),(u64)s->state_plan_count*sizeof(*s->state_plans),
  (u64)s->resource_count*sizeof(*s->resources)};
 const void *input[7]={s->job,s->frame,s->sequences,s->states,s->plans,s->state_plans,s->resources};
 c->source=*s;
 for(u32 i=0;i<7;i++){
  if(i==2){
   u32 count=pt_get(c->owned[0],17),stride=pt_get(c->owned[0],20);
   if(stride<2048 || (stride&63) || count!=s->plan_count || s->state_plan_count!=s->states_bytes/512 ||
    (count?((u64)(count-1u)*stride+2048!=s->sequences_bytes):s->sequences_bytes!=0))return -EINVAL;
  }
  if(sizes[i]>(u64)(~(size_t)0))return -ERANGE;
  if(sizes[i]){if(!input[i])return -EINVAL;c->owned[i]=kvzalloc((size_t)sizes[i],GFP_KERNEL);
   if(!c->owned[i])return -ENOMEM;memcpy(c->owned[i],input[i],(size_t)sizes[i]);}
 }
 c->source.job=c->owned[0];c->source.frame=c->owned[1];c->source.sequences=c->owned[2];c->source.states=c->owned[3];
 c->source.plans=c->owned[4];c->source.state_plans=c->owned[5];c->source.resources=c->owned[6];return 0;
}
/* Immutable original views may overlap each other. A frame writer may never
 * overlap any source cache line, even through a different GEM handle/ASID.
 * Frame-to-frame pairs are separately checked by the complete frame helper. */
struct pt_span {u64 first,end;bool source,write;};
static int pt_span(struct pt_span *spans,size_t *count,u32 handle,u64 offset,u64 bytes,bool source,bool write,
 const struct opengpu_native_job_copy *copy)
{
 struct opengpu_native_frame_backing backing={0};int ret;
 if(!handle)return offset || bytes?-EINVAL:0;
 if(!bytes || offset>~(u64)0-bytes)return -EINVAL;
 ret=copy->resolve(copy->context,handle,&backing);if(ret)return ret;
 if(!backing.bytes || ((backing.physical|backing.bytes)&4095u) || backing.bytes>(1ULL<<32) ||
  backing.physical>(1ULL<<32)-backing.bytes || offset+bytes>backing.bytes)return -EINVAL;
 u64 first=backing.physical+offset,end=first+bytes;
 spans[(*count)++]=(struct pt_span){first&~63ULL,(end+63u)&~63ULL,source,write};return 0;
}
static void pt_swap(struct pt_span *a,struct pt_span *b){struct pt_span t=*a;*a=*b;*b=t;}
static void pt_sift(struct pt_span *s,size_t root,size_t n)
{while(root<n/2){size_t child=root*2+1;if(child+1<n && s[child].first<s[child+1].first)child++;
 if(s[root].first>=s[child].first)break;pt_swap(s+root,s+child);root=child;}}
static int pt_alias(const struct opengpu_protected_tessellation_source *s,const struct opengpu_native_job_copy *copy)
{
 u64 max=(u64)s->plan_count*29u+(u64)s->state_plan_count*14u+s->resource_count;
 size_t n=0;struct pt_span *spans;int ret=0;
 if(max>(u64)(~(size_t)0)/sizeof(*spans))return -ERANGE;
 if(!max)return 0;spans=kvzalloc((size_t)max*sizeof(*spans),GFP_KERNEL);if(!spans)return -ENOMEM;
#define PT_SPAN(h,o,b,source,write) do {ret=pt_span(spans,&n,h,o,b,source,write,copy);if(ret)goto done;}while(0)
 for(u32 i=0;i<s->plan_count;i++){
  const struct opengpu_protected_tessellation_plan *p=&s->plans[i];
  const struct drm_opengpu_native_program_view *code[2]={&p->control_program,&p->evaluation_program};
  const struct drm_opengpu_native_uniform_stage *stage[2]={&p->control_uniforms,&p->evaluation_uniforms};
  for(u32 k=0;k<2;k++){
   PT_SPAN(code[k]->handle,code[k]->offset,code[k]->bytes,true,false);
   for(u32 j=0;j<13;j++){const struct drm_opengpu_compute_uniform_view *v=j<12?&stage[k]->blocks[j]:&stage[k]->defaults;
    PT_SPAN(v->handle,v->offset,v->bytes,true,false);}
  }
  PT_SPAN(p->input.handle,p->input.offset,p->input.bytes,true,false);
 }
 for(u32 i=0;i<s->state_plan_count;i++){
  const struct opengpu_protected_tessellation_state_plan *p=&s->state_plans[i];
  PT_SPAN(p->fragment_program.handle,p->fragment_program.offset,p->fragment_program.bytes,true,false);
  for(u32 j=0;j<13;j++){const struct drm_opengpu_compute_uniform_view *v=j<12?&p->fragment_uniforms.blocks[j]:&p->fragment_uniforms.defaults;
   PT_SPAN(v->handle,v->offset,v->bytes,true,false);}
 }
 for(u32 i=0;i<s->resource_count;i++){const struct opengpu_native_frame_resource *r=&s->resources[i];
  PT_SPAN(r->gem_handle,r->offset,r->bytes,false,!!(r->access&OPENGPU_NATIVE_FRAME_RESOURCE_WRITE));}
 for(size_t i=n/2;i;i--)pt_sift(spans,i-1,n);
 for(size_t i=n;i>1;i--){pt_swap(spans,spans+i-1);pt_sift(spans,0,i-1);}
 u64 source_end=0,writer_end=0;
 for(size_t i=0;i<n;i++){
  const struct pt_span *v=&spans[i];
  if((v->source && writer_end>v->first) || (v->write && source_end>v->first)){ret=-EINVAL;goto done;}
  if(v->source && source_end<v->end)source_end=v->end;
  if(v->write && writer_end<v->end)writer_end=v->end;
 }
 done:kvfree(spans);return ret;
#undef PT_SPAN
}
static bool pt_nan(u32 f){return (f&0x7f800000u)==0x7f800000u && (f&0x7fffffu);}
static void pt_program_profiles(const u8 *d,const struct pt_layout *l,
 const struct opengpu_protected_tessellation_source *s,struct opengpu_tessellation_program_profile *control,
 struct opengpu_tessellation_program_profile *evaluation)
{
 *control=(struct opengpu_tessellation_program_profile){.version=1,.stage=4,.lanes=s->profile.lanes,.warps=s->profile.warps,
  .input_vertices=pt_get(d,l->input_vertices),.input_components=pt_get(d,l->input_components),
  .output_vertices=pt_get(d,l->output_vertices),.output_components=pt_get(d,l->output_components),
  .patch_components=pt_get(d,l->patch_components),.available=true,.scalar_fpu=s->profile.scalar_fpu};
 *evaluation=(struct opengpu_tessellation_program_profile){.version=1,.stage=5,.lanes=s->profile.lanes,.warps=s->profile.warps,
  .patch_words=pt_get(d,l->selections),.output_components=pt_get(d,l->tes_outputs),.available=true,.scalar_fpu=s->profile.scalar_fpu};
}
static int pt_sequence(const u8 *d,const struct pt_layout *l,const struct opengpu_protected_tessellation_source *s,
 const struct opengpu_protected_tessellation_plan *plan,struct opengpu_tessellation_program_profile *control,
 struct opengpu_tessellation_program_profile *evaluation)
{
 if(pt_get(d,0)!=l->magic || pt_get(d,1)!=2048 || pt_get(d,2)!=l->version ||
  !opengpu_tessellation_canonical_words_n(d,2048,l->rules,496) || !pt_zero(d+496*4,16*4) ||
  pt_get(d,184)!=s->context || pt_get(d,185)!=s->generation || pt_get(d,186)!=s->pass ||
  pt_get(d,191) || pt_get(d,190) || pt_get(d,l->old_uniform) || pt_get(d,l->state)!=plan->state_handle ||
  pt_get(d,l->spacing)>2)return -EINVAL;
 pt_program_profiles(d,l,s,control,evaluation);
 if(!opengpu_tessellation_program_profile_valid(control) || !opengpu_tessellation_program_profile_valid(evaluation))return -EINVAL;
 for(u32 i=0;i<8;i++){
  u32 base=i<s->profile.warps && i*s->profile.lanes<control->output_vertices?i*s->profile.lanes:0;
  if(pt_get(d,l->bases+i)!=base)return -EINVAL;
 }
 for(u32 i=0;i<3;i++)if(pt_get(d,l->control_grid+i)!=1 || pt_get(d,l->evaluation_grid+i)!=1 ||
  pt_get(d,l->control_local+i)!=(i?1:control->output_vertices) || pt_get(d,l->evaluation_local+i)!=1)return -EINVAL;
 if(!pt_zero(d+l->control_helpers*4,16) || !pt_zero(d+l->evaluation_helpers*4,16) ||
  !pt_zero(d+l->control_private*4,8) || !pt_zero(d+l->evaluation_private*4,8) ||
  pt_get(d,l->control_arg) || pt_get(d,l->evaluation_arg) || pt_get(d,l->control_pc) || pt_get(d,l->evaluation_pc) ||
  !pt_zero(d+18*4,6*4) || plan->input.reserved)return -EINVAL;
 u32 count=pt_get(d,16),vertex_stride=pt_get(d,l->vertex_stride),patch_stride=pt_get(d,17);
 if((u64)pt_get(d,l->task_id)+(count?count-1u:0)>0xffffffffu ||
  (count && (u64)(count-1u)*patch_stride>=1ULL<<32) || ((vertex_stride|patch_stride)&3))return -EINVAL;
 if(count && control->input_components){
  u64 needed=(u64)(count-1u)*patch_stride+(u64)(control->input_vertices-1u)*vertex_stride+4u*control->input_components;
  if(!plan->input.handle || !plan->input.bytes || (plan->input.offset&3) ||
   plan->input.offset>~(u64)0-plan->input.bytes || needed>plan->input.bytes)return -EINVAL;
 }else if(plan->input.handle || plan->input.offset || plan->input.bytes)return -EINVAL;
 for(u32 i=0;i<61;i++){
  u32 w=l->selection_first+4*i,component=pt_get(d,w),per_patch=pt_get(d,w+1),level=pt_get(d,w+2),vertex=pt_get(d,w+3);
  if(i>=evaluation->patch_words){if(component || per_patch || level || vertex)return -EINVAL;continue;}
  if(level?(!per_patch || vertex || component>=6):per_patch?(vertex || component>=control->patch_components):
   (vertex>=control->output_vertices || component>=control->output_components))return -EINVAL;
 }
 u32 varyings=pt_get(d,l->varyings),out=evaluation->output_components;
 if(varyings>16 || (varyings<16 && ((pt_get(d,182)|(u64)pt_get(d,183)<<32)>>(varyings*4))))return -EINVAL;
 for(u32 i=0;i<68;i++)if(pt_get(d,44+2*i) && pt_get(d,43+2*i)>=out)return -EINVAL;
 if(pt_get(d,189) && pt_get(d,188)>=out)return -EINVAL;
 u32 viewport=l->viewport;
 if((pt_get(d,viewport)|pt_get(d,viewport+1))&0x80000000u || pt_get(d,viewport+4)!=1 ||
  pt_nan(pt_get(d,viewport+2)) || pt_nan(pt_get(d,viewport+3)))return -EINVAL;
 u32 raster=pt_get(d,39),factor=pt_get(d,29),units=pt_get(d,30),point=pt_get(d,27),line=pt_get(d,26);
 if((raster==1?(pt_get(d,28) || factor || units):raster!=2 || (factor&0x7f800000u)==0x7f800000u ||
  (units&0x7f800000u)==0x7f800000u) || pt_get(d,24)>2 || pt_get(d,31)>2 ||
  (point && ((point>>31) || pt_nan(point))) || (line && ((line>>31) || pt_nan(line))))return -EINVAL;
 return 0;
}
static int pt_input(struct opengpu_private_snapshot *owner,u32 sequence,u64 offset,
 const struct opengpu_protected_tessellation_input *v,const struct opengpu_native_job_copy *copy)
{
 struct opengpu_native_frame_backing backing={0};u32 resource;int ret;
 if(!v->handle)return 0;
 ret=copy->resolve(copy->context,v->handle,&backing);if(ret)return ret;
 if(!v->bytes || v->offset>~(u64)0-v->bytes || v->offset+v->bytes>backing.bytes ||
  ((backing.physical|backing.bytes)&4095) || !backing.bytes || backing.bytes>(1ULL<<32) ||
  backing.physical>(1ULL<<32)-backing.bytes)return -EINVAL;
 u64 page=v->offset&~4095ULL,delta=v->offset-page,span=((delta+v->bytes+4095u)&~4095ULL);
 if(span>(u64)(~(size_t)0))return -ERANGE;
 ret=opengpu_private_snapshot_resource(owner,(size_t)span,&resource);
 if(!ret)ret=opengpu_private_snapshot_resource_map(owner,resource,0,backing.physical+page,(size_t)span,false);
 const u32 words[]={18,19,21,22};const u64 addends[]={delta&~63ULL,(delta+v->bytes+63u)&~63ULL,delta,delta+v->bytes};
 for(u32 i=0;!ret && i<4;i++)ret=opengpu_private_snapshot_relocate_resource(owner,sequence,(size_t)offset+4u*words[i],resource,
  addends[i],i&1?OPENGPU_PRIVATE_SNAPSHOT_LIMIT33:OPENGPU_PRIVATE_SNAPSHOT_VA32);
 return ret;
}
int opengpu_protected_tessellation_build(struct opengpu_private_snapshot *owner,
 const struct opengpu_protected_tessellation_source *source,const struct opengpu_native_job_copy *copy,u32 *out)
{
 struct pt_capture capture={0};const struct opengpu_protected_tessellation_source *s;
 struct opengpu_native_job_copy captured_copy;
 const struct opengpu_protected_tessellation_plan **plans=NULL;
 const struct opengpu_protected_tessellation_state_plan **state_plans=NULL;
 u32 count,stride,domain,state_count,sequences=0,states,frame,job;int ret=-EINVAL;
 if(!owner || !source || !copy || !copy->copy || !copy->resolve || !out)return -EINVAL;
 /* No callback, allocation or owner mutation precedes the availability gate. */
 if(!opengpu_protected_tessellation_profile_valid(&source->profile))return -EOPNOTSUPP;
 captured_copy=*copy;copy=&captured_copy;
 if(opengpu_private_snapshot_build_context(owner,source->context))return -EINVAL;
 if(!source->job || !source->frame || !source->states || !source->states_bytes || (source->states_bytes&511u) ||
  source->states_bytes>(1ULL<<32) || source->sequences_bytes>(1ULL<<32) ||
  !source->resources || !source->resource_count || source->resource_count>27)return -EINVAL;
 ret=pt_capture(&capture,source);if(ret)goto done;s=&capture.source;
 domain=pt_get(s->job,0)-0x474e5101u;
 if(domain>2 || !pt_header(s->job,0x474e5101u+domain,512) || !pt_header(s->frame,0x474e4601u,2048))goto invalid;
 count=pt_get(s->job,17);stride=pt_get(s->job,20);state_count=s->states_bytes/512;
 if(s->plan_count!=count || s->state_plan_count!=state_count || stride<2048 || (stride&63) ||
  (count?((u64)(count-1u)*stride+2048!=s->sequences_bytes):s->sequences_bytes!=0) ||
  pt_get(s->job,24)!=s->context || pt_get(s->job,25)!=s->generation || pt_get(s->job,26)!=s->pass)goto invalid;
 for(u32 i=16;i<128;i++)if(i!=17 && i!=20 && i!=24 && i!=25 && i!=26 && pt_get(s->job,i))goto invalid;
 if(!opengpu_tessellation_canonical_words(s->frame,2048,opengpu_tessellation_frame_word_rules))goto invalid;
 if((u64)count*sizeof(*plans)>(u64)(~(size_t)0) || (u64)state_count*sizeof(*state_plans)>(u64)(~(size_t)0)){ret=-ERANGE;goto done;}
 if(count){plans=kvzalloc((size_t)count*sizeof(*plans),GFP_KERNEL);if(!plans){ret=-ENOMEM;goto done;}}
 state_plans=kvzalloc((size_t)state_count*sizeof(*state_plans),GFP_KERNEL);if(!state_plans){ret=-ENOMEM;goto done;}
 for(u32 i=0;i<count;i++){
  const struct opengpu_protected_tessellation_plan *p=&s->plans[i];
  if(p->sequence_index>=count || plans[p->sequence_index] || p->state_handle>=state_count)goto invalid;
  plans[p->sequence_index]=p;
 }
 for(u32 i=0;i<state_count;i++){
  const struct opengpu_protected_tessellation_state_plan *p=&s->state_plans[i];
  if(p->reserved || p->state_handle>=state_count || state_plans[p->state_handle])goto invalid;state_plans[p->state_handle]=p;
  const u8 *f=(const u8 *)s->states+(size_t)p->state_handle*512;
  if(!opengpu_tessellation_canonical_words_n(f,512,opengpu_tessellation_fragment_state_word_rules,128) ||
   pt_get(f,0)!=0x47465301u || pt_get(f,1)!=512 || pt_get(f,2)!=1 || pt_get(f,3)!=4 ||
   pt_get(f,PF(HEADER_HANDLE))!=p->state_handle || pt_get(f,PF(HEADER_CONTEXT))!=s->context ||
   pt_get(f,PF(HEADER_PASS))!=s->pass || pt_get(f,PF(HEADER_GENERATION))!=s->generation ||
   pt_get(f,PF(FRAGMENT_IDENTITY_CONTEXT))!=s->context || pt_get(f,PF(FRAGMENT_IDENTITY_PASS))!=s->pass ||
   pt_get(f,PF(FRAGMENT_IDENTITY_GENERATION))!=s->generation || pt_get(f,PF(FRAGMENT_SHADERPC)) ||
   pt_get(f,PF(FRAGMENT_SAMPLER_BASE)) || pt_get(f,PF(FRAGMENT_UNIFORMS_VERSION)) || pt_get(f,PF(FRAGMENT_UNIFORMS_ADDRESS)) ||
   pt_get(f,PF(FRAGMENT_RESOURCES_VERSION)) || pt_get(f,PF(FRAGMENT_RESOURCES_ADDRESS)) ||
   pt_get(f,PF(IMAGE_VERSION)) || pt_get(f,PF(IMAGE_ADDRESS)) ||
   !opengpu_native_advanced_blend_gfs_precheck(s->frame,2048,f,512,s->profile.advanced_blend))goto invalid;
 }
 /* Preflight every sequence before original bytes are copied or owner writes. */
 for(u32 i=0;i<count;i++){
  struct opengpu_tessellation_program_profile c,e;const u8 *d=(const u8 *)s->sequences+(size_t)i*stride;
  ret=pt_sequence(d,&pt_layouts[domain],s,plans[i],&c,&e);if(ret)goto done;
  const u8 *f=(const u8 *)s->states+(size_t)plans[i]->state_handle*512;
  if(pt_get(d,pt_layouts[domain].varyings)!=pt_get(f,PF(FRAGMENT_VARYINGSLOTS)))goto invalid;
  if(i+1<count && !pt_zero(d+2048,stride-2048))goto invalid;
 }
 ret=pt_alias(s,copy);if(ret)goto done;
 /* Only typed plans choose version/presence bits. Invalid uniform plans still
  * fail in the ordinary encoder; no partially built owner can be published. */
 for(u32 i=0;i<state_count;i++)if(state_plans[i]->fragment_uniforms.active_mask || state_plans[i]->fragment_uniforms.defaults.handle)
  pt_put((u8 *)s->states+(size_t)i*512,PF(FRAGMENT_UNIFORMS_VERSION),1);
 for(u32 i=0;i<count;i++){
  const struct opengpu_protected_tessellation_plan *p=plans[i];u8 *d=(u8 *)s->sequences+(size_t)i*stride;
  bool c=p->control_uniforms.active_mask || p->control_uniforms.defaults.handle;
  bool e=p->evaluation_uniforms.active_mask || p->evaluation_uniforms.defaults.handle;
  if(c || e){pt_put(d,496,s->context);pt_put(d,497,s->pass);pt_put(d,498,s->generation);}
  if(c)pt_put(d,499,3);if(e)pt_put(d,504,3);
 }

 ret=opengpu_private_snapshot_append(owner,s->states,s->states_bytes,&states);if(ret)goto done;
 if(count){ret=opengpu_private_snapshot_append(owner,s->sequences,s->sequences_bytes,&sequences);if(ret)goto done;}
 for(u32 i=0;i<state_count;i++){
  const struct opengpu_protected_tessellation_state_plan *p=state_plans[i];
  const u8 *f=(const u8 *)s->states+(size_t)i*512;
  struct drm_opengpu_native_uniform_stage fs[2]={{0},p->fragment_uniforms};struct opengpu_native_uniform_roots roots;
  struct opengpu_native_program_snapshot program;
  ret=opengpu_native_uniform_snapshot_append(owner,fs,s->context,s->pass,s->generation,copy->copy,copy->context,&roots);if(ret)goto done;
  struct opengpu_native_program_profile profile={.capacity=s->profile.lanes*s->profile.warps,.stage=1,
   .version=pt_get(f,PF(FRAGMENT_VERSION)),.varyings=pt_get(f,PF(FRAGMENT_VARYINGSLOTS)),.colors=pt_get(f,PF(FRAGMENT_COLORSLOTS)),
   .staging_bytes=pt_get(s->frame,PW(SCRATCHSTRIDE)),.scalar_fpu=s->profile.scalar_fpu,.owned_uniforms=roots.present[1]};
  ret=opengpu_native_program_snapshot_append(owner,&p->fragment_program,&profile,copy->copy,copy->context,&program);
  if(!ret)ret=pt_reloc(owner,states,(u64)i*512,PF(FRAGMENT_SHADERPC),program.segment,program.entry_offset,false);
  if(!ret && roots.present[1])ret=pt_reloc(owner,states,(u64)i*512,PF(FRAGMENT_UNIFORMS_ADDRESS),roots.segment,roots.root_offset[1],false);
  if(ret)goto done;
 }
 for(u32 i=0;i<count;i++){
  const struct opengpu_protected_tessellation_plan *p=plans[i];const struct pt_layout *l=&pt_layouts[domain];
  const u8 *d=(const u8 *)s->sequences+(size_t)i*stride;struct opengpu_tessellation_program_profile c,e;
  pt_program_profiles(d,l,s,&c,&e);
  struct drm_opengpu_native_uniform_stage stages[2]={p->control_uniforms,p->evaluation_uniforms};
  struct opengpu_tessellation_uniform_roots roots;struct opengpu_native_program_snapshot program;
  ret=opengpu_tessellation_uniform_snapshot_append(owner,stages,s->context,s->pass,s->generation,copy->copy,copy->context,&roots);if(ret)goto done;
  for(u32 stage=0;stage<2;stage++){
   struct opengpu_tessellation_program_profile *profile=stage?&e:&c;profile->owned_uniforms=roots.present[stage];
   ret=opengpu_tessellation_program_snapshot_append(owner,stage?&p->evaluation_program:&p->control_program,profile,copy->copy,copy->context,&program);
   if(!ret)ret=pt_reloc(owner,sequences,(u64)i*stride,stage?l->evaluation_pc:l->control_pc,program.segment,program.entry_offset,false);
   if(ret)goto done;
   if(roots.present[stage]){
    u32 address=stage?505:500,base=stage?506:501,limit=stage?507:502;
    ret=pt_reloc(owner,sequences,(u64)i*stride,address,roots.segment,roots.root_offset[stage],false);
    if(!ret)ret=pt_reloc(owner,sequences,(u64)i*stride,base,roots.segment,0,false);
    if(!ret)ret=pt_reloc(owner,sequences,(u64)i*stride,limit,roots.segment,roots.bytes,true);
    if(ret)goto done;
   }
  }
  ret=pt_input(owner,sequences,(u64)i*stride,&p->input,copy);if(ret)goto done;
 }
 ret=opengpu_native_frame_snapshot_append_complete(owner,s->frame,states,s->states_bytes,s->resources,s->resource_count,
  s->profile.engines,s->context,s->pass,s->generation,copy->resolve,copy->context,&frame);if(ret)goto done;
 ret=opengpu_private_snapshot_append(owner,s->job,512,&job);
 if(!ret && count)ret=pt_reloc(owner,job,0,16,sequences,0,false);
 if(!ret && count)ret=pt_reloc(owner,job,0,18,sequences,s->sequences_bytes,true);
 if(!ret)ret=pt_reloc(owner,job,0,21,frame,0,false);
 if(!ret)ret=pt_reloc(owner,job,0,22,frame,2048,true);
 if(!ret)*out=job;goto done;
 invalid:ret=-EINVAL;
 done:pt_release(&capture);kvfree(plans);kvfree(state_plans);return ret;
}
#undef PF
#undef PW
