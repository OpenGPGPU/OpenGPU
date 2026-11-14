/* Production typed GNQ/GNT builder, actual opaque owner/MMU and originals.
 * No shader VM, Scala, RTL, generated evidence or GPU-computed result. */
#define OPENGPU_NATIVE_JOB_SNAPSHOT_TEST_MAIN pt_old_native_graph_main
#include "opengpu_native_job_snapshot_test.c"
#undef OPENGPU_NATIVE_JOB_SNAPSHOT_TEST_MAIN
#include "../opengpu_tessellation_uniform_snapshot.c"
#include "../opengpu_tessellation_shader_validator.c"
#include "../opengpu_tessellation_program_snapshot.c"
#ifndef OPENGPU_PROTECTED_TESS_SOURCE
#define OPENGPU_PROTECTED_TESS_SOURCE "../opengpu_protected_tessellation.c"
#endif
#include OPENGPU_PROTECTED_TESS_SOURCE
struct pt_records {
 struct graph_records original;
 u32 sequences[3*512];
 struct opengpu_protected_tessellation_plan plans[3];
 struct opengpu_protected_tessellation_state_plan states[2];
 struct opengpu_protected_tessellation_source source;
 u32 evaluation_handle;
};
static struct pt_records *pt_mutate;
static int pt_test_resolve(void *opaque,u32 handle,struct opengpu_native_frame_backing *out)
{
 if(!graph_producers_complete)return -EAGAIN;
 int ret=graph_resolve(opaque,handle,out);
 if(pt_mutate){memset(pt_mutate,0xa7,sizeof(*pt_mutate));pt_mutate=NULL;}
 return ret;
}
static int pt_test_copy(void *opaque,const struct drm_opengpu_compute_uniform_view *view,void *out)
{if(!graph_producers_complete)return -EAGAIN;return graph_copy(opaque,view,out);}
static const struct opengpu_native_job_copy pt_copy={pt_test_copy,pt_test_resolve,graph_original};
static void pt_init(struct pt_records *t,struct opengpu_device *gpu,u32 domain)
{
 memset(t,0,sizeof(*t));graph_handles=0;graph_copies=graph_resolves=0;graph_copy_failure=graph_resolve_failure=0;
 graph_init(&t->original,gpu);t->evaluation_handle=graph_alloc(gpu,4096);
 word(graph_original[t->evaluation_handle].cpu,64,0xffffffff);word(graph_original[t->evaluation_handle].cpu,68,35u<<25|0xb);
 word(graph_original[t->evaluation_handle].cpu,72,36u<<25|0xb);word(graph_original[t->evaluation_handle].cpu,76,0x30500073);
 t->original.job[0]=0x474e5101u+domain;t->original.job[20]=2048;
 const struct pt_layout *l=&pt_layouts[domain];
 for(u32 i=0;i<3;i++){
  u32 *d=t->sequences+i*512;
  d[0]=0x474e5404u+domain;d[1]=2048;d[2]=4+domain;d[16]=2;d[17]=32;d[40]=i==2?1:0;
  d[184]=7;d[185]=9;d[186]=8;d[l->input_components]=4;d[l->input_vertices]=2;
  d[l->output_components]=8;d[l->output_vertices]=2;d[l->patch_components]=0;
  for(u32 j=0;j<3;j++){d[l->control_grid+j]=1;d[l->evaluation_grid+j]=1;d[l->control_local+j]=j?1:2;d[l->evaluation_local+j]=1;}
  d[l->tes_outputs]=8;d[l->selections]=8;
  for(u32 j=0;j<8;j++){d[l->selection_first+4*j]=j%8;d[l->selection_first+4*j+3]=j/8;}
  d[l->varyings]=16;d[l->vertex_stride]=16;d[l->viewport]=4;d[l->viewport+1]=4;
  d[l->viewport+2]=0x3f800000;d[l->viewport+4]=1;d[39]=1;d[37]=4;d[38]=4;
 }
 const u32 order[]={2,0,1};
 for(u32 i=0;i<3;i++){
  const struct drm_opengpu_native_draw_plan *p=&t->original.plans[i];
  struct opengpu_protected_tessellation_plan *plan=&t->plans[i];
  plan->sequence_index=order[i];plan->state_handle=order[i]==2?1:0;
  plan->control_program=p->vertex_program;
  plan->evaluation_program=(struct drm_opengpu_native_program_view){.handle=t->evaluation_handle,.offset=64,.bytes=16,.entry_offset=4};
  plan->control_uniforms=p->vertex_uniforms;plan->evaluation_uniforms=p->fragment_uniforms;
  plan->input=(struct opengpu_protected_tessellation_input){.handle=graph_inputs[order[i]],.offset=64,.bytes=64};
 }
 for(u32 i=0;i<2;i++){
  const struct drm_opengpu_native_draw_plan *p=&t->original.plans[i?0:1];
  t->states[i]=(struct opengpu_protected_tessellation_state_plan){.state_handle=i,.fragment_program=p->fragment_program,.fragment_uniforms=p->fragment_uniforms};
 }
 t->source=(struct opengpu_protected_tessellation_source){.job=t->original.job,.frame=&t->original.encoded.frame,
  .sequences=t->sequences,.sequences_bytes=sizeof(t->sequences),.states=t->original.states,.states_bytes=sizeof(t->original.states),
  .plans=t->plans,.plan_count=3,.state_plans=t->states,.state_plan_count=2,
  .resources=t->original.encoded.resources,.resource_count=t->original.encoded.resource_count,.context=7,.pass=8,.generation=9,
  .profile={.available=true,.version=1,.domains=7,.compute_units=2,.lanes=16,.warps=8,.engines=4,.scalar_fpu=true,.patch_zero_initialized=true}};
 graph_producers_complete=true;
}
static void pt_free_originals(struct opengpu_device *gpu)
{for(u32 h=1;h<=graph_handles;h++)opengpu_buffer_free(gpu,&graph_original[h]);graph_handles=0;}
static void pt_published_oracle(struct opengpu_private_snapshot *owner,struct opengpu_render_context *context,u32 segment,u32 domain)
{
 u32 root;assert(!opengpu_private_snapshot_protected_tessellation_job_address(owner,segment,&root));
 assert(opengpu_private_snapshot_native_job_address(owner,segment,&root)==-EINVAL);
 assert(opengpu_private_snapshot_native_layered_job_address(owner,segment,&root)==-EINVAL);
 assert(graph_read(&context->vm,root,3)==0x474e5101u+domain);
 assert(graph_read(&context->vm,root+17*4,3)==3);
 u32 seq=graph_read(&context->vm,root+16*4,3),frame=graph_read(&context->vm,root+21*4,3);
 assert(graph_read(&context->vm,root+18*4,3)==seq+3*2048 && graph_read(&context->vm,root+19*4,3)==0);
 assert(graph_read(&context->vm,root+22*4,3)==frame+2048);
 const struct pt_layout *l=&pt_layouts[domain];
 for(u32 i=0;i<3;i++){
  u32 base=seq+i*2048;
  assert(graph_read(&context->vm,base,3)==0x474e5404u+domain && graph_read(&context->vm,base+8,3)==4+domain);
  u32 pc=graph_read(&context->vm,base+l->control_pc*4,3);
  assert(graph_read(&context->vm,pc,11)==0x30500073 && graph_read(&context->vm,pc-4,11)==0x93000000u);
  pc=graph_read(&context->vm,base+l->evaluation_pc*4,3);
  assert(graph_read(&context->vm,pc,11)==(35u<<25|0xb) && graph_read(&context->vm,pc-4,11)==0xffffffff);
  u32 croot=graph_read(&context->vm,base+500*4,3),eroot=graph_read(&context->vm,base+505*4,3);
  assert(graph_read(&context->vm,croot,3)==0x47555203 && graph_read(&context->vm,eroot,3)==0x47555203);
  u32 table=graph_read(&context->vm,croot+10*4,3),data=graph_read(&context->vm,table+8*4,3);
  assert(graph_read(&context->vm,data,3)==source_word(graph_ubo[0],64,0));
  assert(graph_read(&context->vm,croot+4*6,3)==4 && graph_read(&context->vm,eroot+4*6,3)==5);
  u32 private_base=graph_read(&context->vm,base+501*4,3);
  assert(graph_read(&context->vm,base+502*4,3)==private_base+403200 && graph_read(&context->vm,base+503*4,3)==0);
  assert(graph_read(&context->vm,base+506*4,3)==private_base && graph_read(&context->vm,base+507*4,3)==private_base+403200);
  assert(graph_read(&context->vm,base+499*4,3)==3 && graph_read(&context->vm,base+504*4,3)==3);
  assert(graph_read(&context->vm,base+496*4,3)==7 && graph_read(&context->vm,base+497*4,3)==8 && graph_read(&context->vm,base+498*4,3)==9);
  u32 input=graph_read(&context->vm,base+21*4,3);
  assert(graph_translate(&context->vm,input,3)==graph_original[graph_inputs[i]].dma+64);
  assert(graph_read(&context->vm,base+22*4,3)==input+64);
 }
 u32 states=graph_read(&context->vm,frame+292*4,3);
 for(u32 i=0;i<2;i++){
  u32 pc=graph_read(&context->vm,states+i*512+4*OPENGPU_TYPED_FRAGMENT_STATE_FRAGMENT_SHADERPC_WORD,3);
  assert(graph_read(&context->vm,pc,11)==0x30500073);
  u32 uniform=graph_read(&context->vm,states+i*512+4*OPENGPU_TYPED_FRAGMENT_STATE_FRAGMENT_UNIFORMS_ADDRESS_WORD,3);
  assert(graph_read(&context->vm,uniform,3)==0x47555201);
 }
}
static void pt_expect(struct opengpu_device *gpu,struct opengpu_render_context *context,
 const struct opengpu_protected_tessellation_source *source,int error)
{
 struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(owner));
 u32 output=0x5a5a5a5a;int result=opengpu_protected_tessellation_build(owner,source,&pt_copy,&output);
 if(result!=error)fprintf(stderr,"expected %d got %d\n",error,result);
 assert(result==error && output==0x5a5a5a5a);
 assert(!opengpu_private_snapshot_retire(owner) && context->references==1 && !gpu->mmu.snapshot_leases);
}
static void pt_negatives(struct pt_records *t,struct opengpu_device *gpu,struct opengpu_render_context *context,u32 domain)
{
 struct pt_records *saved=malloc(sizeof(*saved));assert(saved);*saved=*t;const struct pt_layout *l=&pt_layouts[domain];
 for(u32 kind=0;kind<58;kind++){
  *t=*saved;u32 *d=t->sequences;int error=-EINVAL;
  switch(kind){
  case 0:t->source.profile.available=false;error=-EOPNOTSUPP;break;
  case 1:t->source.profile.version=0;error=-EOPNOTSUPP;break;
  case 2:t->source.profile.domains=1;error=-EOPNOTSUPP;break;
  case 3:t->source.context=8;break;case 4:t->source.pass=9;break;case 5:t->source.generation=10;break;
  case 6:t->plans[0].sequence_index=t->plans[1].sequence_index;break;
  case 7:t->plans[0].state_handle=2;break;case 8:t->source.plan_count--;break;
  case 9:t->states[1].state_handle=0;break;case 10:t->states[0].reserved=1;break;
  case 11:t->source.state_plan_count--;break;case 12:t->source.sequences_bytes--;break;
  case 13:t->source.states_bytes--;break;case 14:t->source.state_plans=NULL;break;
  case 15:d[0]=0x474e5401u+domain;d[2]=1+domain;break;
  case 16:d[0]=0x474e5404u+(domain+1)%3;break;
  case 17:d[2]++;break;case 18:d[18]=64;break;case 19:d[l->control_pc]=64;break;
  case 20:d[l->control_arg]=64;break;case 21:d[l->evaluation_pc]=64;break;
  case 22:d[l->control_helpers]=1;break;case 23:d[l->control_private]=64;break;
  case 24:d[l->old_uniform]=1;break;case 25:d[190]=1;break;
  case 26:d[l->bases]=1;break;case 27:d[l->control_local]=3;break;
  case 28:d[l->evaluation_grid]=2;break;case 29:d[184]=8;break;
  case 30:d[l->output_vertices]=32;d[l->control_local]=32;d[l->bases+1]=16;d[l->output_components]=64;d[l->patch_components]=120;break;
  case 31:d[l->selections]=62;break;case 32:d[l->selection_first+8*4]=1;break;
  case 33:d[l->selection_first]=8;break;case 34:d[l->selection_first+1]=1;break;
  case 35:d[43]=8;d[44]=1;break;case 36:d[188]=8;d[189]=1;break;
  case 37:d[l->viewport+4]=0;break;case 38:d[l->viewport+2]=0x7fc00000;break;
  case 39:d[39]=0;break;case 40:d[24]=3;break;
  case 41:t->plans[0].input.handle=0;break;case 42:t->plans[0].input.reserved=1;break;
  case 43:t->plans[0].input.offset=~(u64)0;break;case 44:t->plans[0].input.bytes=63;break;
  case 45:d[l->vertex_stride]=17;break;case 46:d[16]=0xffffffff;break;
  case 47:t->plans[0].control_program.pad=1;break;case 48:t->plans[0].evaluation_program.entry_offset=0;break;
  case 49:t->plans[0].control_uniforms.pad=1;break;case 50:t->plans[0].evaluation_uniforms.active_mask=0x1fff;break;
  case 51:t->states[0].fragment_program.entry_offset=0;break;
  case 52:t->original.states[OPENGPU_TYPED_FRAGMENT_STATE_FRAGMENT_SHADERPC_WORD]=64;break;
  case 53:t->original.states[OPENGPU_TYPED_FRAGMENT_STATE_FRAGMENT_UNIFORMS_ADDRESS_WORD]=64;break;
  case 54:t->source.resource_count--;break;case 55:t->original.job[127]=1;break;
  case 56:t->source.profile.patch_zero_initialized=false;error=-EOPNOTSUPP;break;
  case 57:d[16]=3;d[17]=0x80000000u;d[l->input_components]=0;
   for(u32 k=0;k<3;k++)t->plans[k].input=(struct opengpu_protected_tessellation_input){0};
   break;
  }
  pt_expect(gpu,context,&t->source,error);
 }
 *t=*saved;
 /* Every reserved or narrow-padding word is checked using the committed
  * layout catalog, including the independent extension's complete 16 words. */
 for(u32 i=3;i<512;i++){
  u32 bits=i<496?(l->rules[i]&127u):0;
  if(bits==32)continue;
  u32 old=t->sequences[i];t->sequences[i]=old^(bits?1u<<bits:1u);
  pt_expect(gpu,context,&t->source,-EINVAL);t->sequences[i]=old;
 }
 /* Source and publication metadata cannot share a physical writer cache line,
  * even when two local handles name the same original object. */
 struct opengpu_protected_tessellation_plan *plan=&t->plans[0];
 u32 alias=++graph_handles;assert(alias<96);graph_original[alias]=graph_original[t->original.request.scratch[0].handle];
 struct opengpu_protected_tessellation_input input=plan->input;
 plan->input=(struct opengpu_protected_tessellation_input){.handle=alias,.offset=64,.bytes=64};pt_expect(gpu,context,&t->source,-EINVAL);plan->input=input;
 struct drm_opengpu_compute_uniform_view view=plan->control_uniforms.blocks[0];
 plan->control_uniforms.blocks[0]=(struct drm_opengpu_compute_uniform_view){.handle=alias,.offset=64,.bytes=4};pt_expect(gpu,context,&t->source,-EINVAL);
 plan->control_uniforms.blocks[0]=view;memset(&graph_original[alias],0,sizeof(graph_original[alias]));graph_handles--;
 alias=++graph_handles;graph_original[alias]=graph_original[t->original.request.attachments[0].image.gem.handle];
 /* Logical [1028,1032) does not overlap image [3,1027), but their actual
  * cache-line ownership overlaps. Compare physical rounded ranges. */
 plan->control_uniforms.blocks[0]=(struct drm_opengpu_compute_uniform_view){.handle=alias,.offset=1028,.bytes=4};
 pt_expect(gpu,context,&t->source,-EINVAL);plan->control_uniforms.blocks[0]=view;
 memset(&graph_original[alias],0,sizeof(graph_original[alias]));graph_handles--;
 /* Availability cannot allocate, resolve/copy, append, or consume caller output. */
 struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(owner));
 t->source.profile.available=false;u32 output=0x55;unsigned copies=graph_copies,resolves=graph_resolves;
 private_alloc_fail_after=0;
 assert(opengpu_protected_tessellation_build(owner,&t->source,&pt_copy,&output)==-EOPNOTSUPP && output==0x55);
 assert(!owner->segments && graph_copies==copies && graph_resolves==resolves && private_alloc_fail_after==0);
 private_alloc_fail_after=-1;assert(!opengpu_private_snapshot_retire(owner));*t=*saved;
 graph_producers_complete=false;pt_expect(gpu,context,&t->source,-EAGAIN);graph_producers_complete=true;
 free(saved);
}
static void pt_failures(struct pt_records *t,struct opengpu_device *gpu,struct opengpu_render_context *context)
{
 /* Count the real copy/resolve calls, then inject an error at every position. */
 struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(owner));u32 segment;
 graph_copies=graph_resolves=0;assert(!opengpu_protected_tessellation_build(owner,&t->source,&pt_copy,&segment));
 unsigned copies=graph_copies,resolves=graph_resolves;assert(copies>70 && resolves>100);assert(!opengpu_private_snapshot_retire(owner));
 for(u32 i=1;i<=copies;i++){graph_copies=0;graph_copy_failure=i;pt_expect(gpu,context,&t->source,-EIO);}graph_copy_failure=0;
 for(u32 i=1;i<=resolves;i++){graph_resolves=0;graph_resolve_failure=i;pt_expect(gpu,context,&t->source,-EIO);}graph_resolve_failure=0;
 /* Every allocation/segment/relocation failure retires the entire owner. */
 int reached=-1;
 for(int i=0;i<2048;i++){
  owner=opengpu_private_snapshot_create(gpu,context);assert(!IS_ERR(owner));segment=0x55;
  private_alloc_fail_after=i;int ret=opengpu_protected_tessellation_build(owner,&t->source,&pt_copy,&segment);private_alloc_fail_after=-1;
  if(ret)assert((ret==-ENOMEM || ret==-EINVAL) && segment==0x55);else reached=i;
  assert(!opengpu_private_snapshot_retire(owner) && context->references==1);
  if(!ret)break;
 }assert(reached>100 && reached<2048);
}
int main(void)
{
 for(u32 domain=0;domain<3;domain++){
  struct opengpu_device gpu={0};struct opengpu_render_context context={.gpu=&gpu,.references=1,.id=7};
  gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;assert(!opengpu_mmu_init(&gpu) && !opengpu_mmu_vm_create(&gpu,&context.vm));copy_gpu=&gpu;
  struct pt_records *records=calloc(1,sizeof(*records));assert(records);pt_init(records,&gpu,domain);
  pt_negatives(records,&gpu,&context,domain);
  if(!domain)pt_failures(records,&gpu,&context);
  struct opengpu_private_snapshot *owner=opengpu_private_snapshot_create(&gpu,&context);assert(!IS_ERR(owner));u32 segment=0x5a;
  if(domain==2)pt_mutate=records; /* Changes all original caller graph bytes during first lookup. */
  int ret=opengpu_protected_tessellation_build(owner,&records->source,&pt_copy,&segment);
  if(ret)fprintf(stderr,"protected domain%u build=%d\n",domain,ret);
  assert(!ret);
  /* Later original-GEM writes cannot alter captured RX or GUR payloads. */
  for(u32 i=0;i<2;i++)memset(graph_original[graph_code[i]].cpu,0x99,graph_original[graph_code[i]].size);
  for(u32 i=0;i<26;i++)memset(graph_original[graph_ubo[i]].cpu,0x99,graph_original[graph_ubo[i]].size);
  assert(!opengpu_private_snapshot_prepare(owner) && !opengpu_private_snapshot_publish(owner));
  pt_published_oracle(owner,&context,segment,domain);
  int before=live_allocations;idle_error=-ETIMEDOUT;
  assert(opengpu_private_snapshot_retire(owner)==-ETIMEDOUT && live_allocations==before && context.references==2);
  idle_error=0;gpu.hw.wedged=false;asid_flush_failures=1;
  assert(opengpu_private_snapshot_retire(owner)==-EIO && live_allocations==before && context.references==2);
  gpu.hw.wedged=false;assert(!opengpu_private_snapshot_retire(owner));pt_free_originals(&gpu);
  /* Reuse the same context after quarantine recovery with a fresh original
   * graph, without resetting its ASID or the MMU. */
  pt_init(records,&gpu,domain);owner=opengpu_private_snapshot_create(&gpu,&context);assert(!IS_ERR(owner));
  assert(!opengpu_protected_tessellation_build(owner,&records->source,&pt_copy,&segment));
  assert(!opengpu_private_snapshot_prepare(owner) && !opengpu_private_snapshot_publish(owner));
  pt_published_oracle(owner,&context,segment,domain);assert(!opengpu_private_snapshot_retire(owner));
  pt_free_originals(&gpu);free(records);
  opengpu_mmu_vm_destroy(&gpu,&context.vm);opengpu_mmu_fini(&gpu);assert(context.references==1 && !live_allocations && !fixture_buffers);
 }
 puts("protected tessellation typed graph: three exact domains/RX/GUR3/GUR1/original-input/MMU PASS (CPU only)");return 0;
}
