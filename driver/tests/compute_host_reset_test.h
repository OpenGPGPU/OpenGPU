/* Explicit CPU transport peer only. No hardware BUSY/ACK is qualified here. */
static bool test_cancelled_image_receipts;
static int test_reset_bad;

static void test_reset_trace(const char *directory,const char *suffix,const char *magic,
 u32 command,const struct compute_mmio_state *m,size_t first,size_t count)
{
 char path[4096];assert(snprintf(path,sizeof(path),"%s/record-0.reset.%s",directory,suffix)>0);
 FILE *f=fopen(path,"rb");assert(f);char header[8];u32 value;
 assert(fread(header,1,8,f)==8 && !memcmp(header,magic,8));
 assert(!compute_mmio_read_le(f,&value) && value==command);
 assert(!compute_mmio_read_le(f,&value) && value==count);
 for(size_t n=first;n<first+count;n++){
  const struct compute_mmio_action *a=&m->actions[n];
  const u32 fields[]={a->kind,a->address,a->value,a->mask};
  for(unsigned i=0;i<4;i++)assert(!compute_mmio_read_le(f,&value) && value==fields[i]);
 }
 assert(fgetc(f)==EOF && !ferror(f) && !fclose(f));
}

static void compute_host_test_reset_peer(struct opengpu_device *gpu,const char *directory)
{
 struct compute_mmio_state *m=compute_mmio_state(gpu);
 assert(test_cancelled_image_receipts && m->reset_active && m->reset_issued && !m->reset_busy_observed);
 assert(gpu->hw.reset_pending && gpu->hw.aborting==1 && !gpu->hw.unified_fence && m->kernel_waiting);
 assert(compute_running->hardware->error==-ECANCELED && dma_fence_is_signaled(compute_running->hardware));
 assert(!(m->regs[GPU_REG_UCMD_STATUS/4]&GPU_UCMD_STATUS_RESET_BUSY));
 assert(!(m->regs[GPU_REG_IRQ/4]&GPU_IRQ_ENABLE));
 assert(m->count-m->reset_start==3);
 const struct compute_mmio_action *a=&m->actions[m->reset_start];
 assert(a[0].kind==1 && a[0].address==GPU_REG_IRQ && !a[0].value);
 assert(a[1].kind==2 && a[1].address==GPU_REG_UCMD_STATUS &&
  (a[1].value&GPU_UCMD_STATUS_VM_BUSY) && !(a[1].value&GPU_UCMD_STATUS_COMPLETION));
 assert(a[2].kind==1 && a[2].address==GPU_REG_UCMD_RESET && a[2].value==GPU_UCMD_RESET_REQUEST);
 test_reset_trace(directory,"request","OGCRQ001",gpu->hw.unified_command_id,m,m->reset_start,3);
 char path[4096];assert(snprintf(path,sizeof(path),"%s/record-0.reset.%s",directory,
  test_reset_bad==17?"error":"busy.response")>0);
 if(test_reset_bad==18){assert(!setenv("OPENGPU_COMPUTE_HOST_TIMEOUT","0",1));return;}
 if(test_reset_bad==19){assert(!setenv("OPENGPU_COMPUTE_HOST_TIMEOUT","1",1));return;}
 u32 v[]={gpu->hw.unified_command_id,0,GPU_UCMD_STATUS_RESET_BUSY|GPU_UCMD_STATUS_VM_BUSY,
  m->regs[GPU_REG_IRQ/4],0,0};
 if(test_reset_bad==1)v[0]^=1;
 if(test_reset_bad==2)v[1]=1;
 if(test_reset_bad==3)v[2]&=~GPU_UCMD_STATUS_RESET_BUSY;
 if(test_reset_bad==4)v[2]|=GPU_UCMD_STATUS_COMPLETION;
 if(test_reset_bad==5)v[2]|=GPU_UCMD_STATUS_READY;
 if(test_reset_bad==6)v[2]&=~GPU_UCMD_STATUS_VM_BUSY;
 if(test_reset_bad==7)v[3]|=GPU_IRQ_ENABLE;
 if(test_reset_bad==8)v[3]|=GPU_IRQ_VBLANK_PENDING;
 if(test_reset_bad==9)v[4]=1;
 if(test_reset_bad==10)v[5]=1;
 if(test_reset_bad==15){
  char target[4096];assert(snprintf(target,sizeof(target),"%s/record-0.reset.target",directory)>0);
  FILE *f=fopen(target,"wx");assert(f && !fclose(f));assert(!symlink(target,path));return;
 }
 assert(test_reset_bad!=16);
 FILE *f=fopen(path,"wx");assert(f);
 assert(fwrite(test_reset_bad==11?"OGCRB000":"OGCRB001",1,8,f)==8);
 for(unsigned i=0;i<(test_reset_bad==12?5u:6u);i++)assert(!compute_mmio_le(f,v[i],4));
 if(test_reset_bad==13)assert(fputc(0,f)!=EOF);
 if(test_reset_bad==14){assert(!fclose(f));assert(!truncate(path,0));return;}
 assert(!fclose(f));
}

static void test_reset_pinned(struct compute_host_session *s,const char *directory,unsigned slot)
{
 assert(compute_host_unbind(s,slot)==-EBUSY && compute_host_context_destroy(s,s->context_id)==-EBUSY);
 assert(compute_host_begin_cleanup(s,directory)==-EBUSY && compute_host_destroy(s)==-EBUSY);
 assert(compute_host_finish_observed(s)==-EINVAL && compute_host_finish_failed_observed(s)==-EINVAL);
 assert(compute_host_finish_cancelled_observed(s)<0 && !s->freed && s->head && s->context);
 assert(s->render.originals[3]->refs && s->render.originals[3]->backing.cpu &&
  opengpu_sched_job_from_base(s->head)->image_records.cpu);
}

static void test_image_cancel_retire(struct compute_host_session *s,const char *directory)
{
 struct compute_mmio_state *m=compute_mmio_state(&s->gpu);unsigned slot=0;
 for(unsigned i=39;i<=42;i++)if(s->context->bindings[i-1])slot=i;
 assert(slot && !s->cancelled && !s->gpu.hw.reset_pending && !m->reset_issued);
 int32_t status=0;assert(compute_host_completion_status(s,&status)==-EINVAL && !status);
 test_reset_pinned(s,directory,slot);
 struct compute_host_reset_receipt held={.command_id=s->gpu.hw.unified_command_id,
  .status=GPU_UCMD_STATUS_VM_BUSY|((slot&1)?GPU_UCMD_STATUS_READY:0),
  .irq=GPU_IRQ_ENABLE|((slot&1)?GPU_IRQ_PENDING:0),.irq_asserted=slot&1};
 for(unsigned bad=0;bad<12;bad++){
  struct compute_host_reset_receipt wrong=held;
  if(bad==0)wrong.command_id^=1;
  if(bad==1)wrong.legacy_status=1;
  if(bad==2)wrong.external_drained=1;
  if(bad==3)wrong.status&=~GPU_UCMD_STATUS_VM_BUSY;
  if(bad==4)wrong.status|=GPU_UCMD_STATUS_COMPLETION;
  if(bad==5)wrong.status|=GPU_UCMD_STATUS_RESET_BUSY;
  if(bad==6)wrong.irq&=~GPU_IRQ_ENABLE;
  if(bad==7)wrong.irq|=GPU_IRQ_VBLANK_PENDING;
  if(bad==8)wrong.irq_asserted^=1;
  if(bad==9)s->gpu.hw.capabilities&=~GPU_CAP_UNIFIED_RESET;
  if(bad==10)s->gpu.hw.reset_pending=true;
  if(bad==11)s->gpu.hw.aborting=1;
  assert(compute_host_abort_captured(s,&wrong,directory)==-EINVAL && !s->cancelled && !dma_fence_is_signaled(s->hardware));
  s->gpu.hw.capabilities|=GPU_CAP_UNIFIED_RESET;
  s->gpu.hw.reset_pending=false;s->gpu.hw.aborting=0;
 }
 if(test_reset_bad==16){
  char path[4096];assert(snprintf(path,sizeof(path),"%s/record-0.reset.busy.response",directory)>0);
  FILE *f=fopen(path,"wx");assert(f && !fclose(f));
 }
 unsigned pops=m->pops;
 /* Unowned completion DATA can be stale. Neither abort nor ACK may POP it. */
 m->regs[GPU_REG_UCMD_COMPLETION/4]=0xdeadbeefu;
 int error=compute_host_abort_captured(s,&held,directory);
 if(test_reset_bad){
  assert(error<0 && s->gpu.hw.wedged && s->cancelled && s->gpu.hw.reset_pending && m->kernel_waiting);
  assert(dma_fence_is_signaled(s->hardware) && s->hardware->error==-ECANCELED && m->pops==pops);
  assert(!s->observed && !s->drained && !m->cleanup_active && !s->freed);
  test_reset_pinned(s,directory,slot);
  puts("typed image invalid/missing reset BUSY retained original job/GEM/PTEs: PASS (CPU transport only)");
  fflush(stdout);_Exit(0);
 }
 assert(!error && s->cancelled && s->gpu.hw.reset_pending && m->kernel_waiting && m->reset_busy_observed);
 assert(s->hardware->error==-ECANCELED && !s->head->s_fence->finished.signaled && m->pops==pops);
 assert(m->regs[GPU_REG_UCMD_COMPLETION/4]==0xdeadbeefu && m->count-m->reset_tail_start==1);
 assert(m->actions[m->reset_tail_start].kind==1 && m->actions[m->reset_tail_start].address==GPU_REG_IRQ &&
  m->actions[m->reset_tail_start].value==GPU_IRQ_ENABLE);
 test_reset_trace(directory,"tail","OGCRL001",held.command_id,m,m->reset_tail_start,1);
 assert(compute_host_abort_captured(s,&held,directory)==-EINVAL);
 test_reset_pinned(s,directory,slot);
 struct compute_host_reset_receipt ack={.command_id=held.command_id,.status=GPU_UCMD_STATUS_READY,
  .irq=GPU_IRQ_ENABLE|GPU_IRQ_PENDING,.irq_asserted=1,.external_drained=1};
 char path[4096];assert(snprintf(path,sizeof(path),"%s/record-0.reset.retire",directory)>0);
 assert(compute_host_drain_reset(s,&ack,NULL)==-EINVAL && s->gpu.hw.reset_pending && m->kernel_waiting);
 for(unsigned bad=0;bad<8;bad++){
  struct compute_host_reset_receipt wrong=ack;
  if(bad==0)wrong.command_id^=1;
  if(bad==1)wrong.legacy_status=1;
  if(bad==2)wrong.status|=GPU_UCMD_STATUS_RESET_BUSY;
  if(bad==3)wrong.status|=GPU_UCMD_STATUS_VM_BUSY;
  if(bad==4)wrong.status|=GPU_UCMD_STATUS_COMPLETION;
  if(bad==5)wrong.irq=GPU_IRQ_ENABLE;
  if(bad==6)wrong.irq_asserted=0;
  if(bad==7)wrong.external_drained=0;
  assert(compute_host_drain_reset(s,&wrong,path)==-EINVAL && s->gpu.hw.reset_pending && m->kernel_waiting);
 }
 assert(compute_host_observe_reset_retired(s,&ack)==-EINVAL);
 size_t first=m->count;assert(!compute_host_drain_reset(s,&ack,path));
 assert(s->drained && !s->observed && !s->gpu.hw.reset_pending && !m->kernel_waiting && m->pops==pops);
 test_reset_trace(directory,"retire","OGCRV001",held.command_id,m,first,m->count-first);
 assert(compute_host_drain_reset(s,&ack,path)==-EINVAL && compute_host_completion_status(s,&status)==-EINVAL);
 test_reset_pinned(s,directory,slot);
 struct compute_host_reset_receipt retired=ack;retired.irq=GPU_IRQ_ENABLE;retired.irq_asserted=0;
 for(unsigned bad=0;bad<8;bad++){
  struct compute_host_reset_receipt wrong=retired;
  if(bad==0)wrong.command_id^=1;
  if(bad==1)wrong.legacy_status=1;
  if(bad==2)wrong.status|=GPU_UCMD_STATUS_RESET_BUSY;
  if(bad==3)wrong.status|=GPU_UCMD_STATUS_VM_BUSY;
  if(bad==4)wrong.status|=GPU_UCMD_STATUS_COMPLETION;
  if(bad==5)wrong.irq|=GPU_IRQ_PENDING;
  if(bad==6)wrong.irq_asserted=1;
  if(bad==7)wrong.external_drained=0;
  assert(compute_host_observe_reset_retired(s,&wrong)==-EINVAL && !s->observed);
 }
 assert(!compute_host_observe_reset_retired(s,&retired) && compute_host_observe_reset_retired(s,&retired)==-EINVAL);
 assert(!compute_host_completion_status(s,&status) && status==-ECANCELED && m->pops==pops);
 assert(m->regs[GPU_REG_UCMD_COMPLETION/4]==0xdeadbeefu && !compute_host_begin_cleanup(s,directory));
 assert(compute_host_finish_observed(s)==-EINVAL && compute_host_finish_failed_observed(s)==-EINVAL);
 s->hardware->error=-EIO;assert(compute_host_finish_cancelled_observed(s)==-EINVAL && s->head && !s->freed);
 s->hardware->error=-ECANCELED;
 int cleanup=compute_host_finish_cancelled_observed(s);
 if(test_fail_phase){
  assert(test_fail_phase==1 && test_failure_injected && cleanup<0 && s->gpu.hw.wedged);
  assert(s->head && opengpu_sched_job_from_base(s->head)->image_records.cpu && s->context);
  assert(s->render.originals[3]->refs && s->render.originals[3]->backing.cpu);
  assert(compute_host_end_cleanup(s)==-EBUSY && compute_host_destroy(s)<0);
  puts("typed image cancelled job bad cleanup ACK retained original GEM/descriptor: PASS (CPU transport only)");
  fflush(stdout);_Exit(0);
 }
 assert(!cleanup && !compute_host_end_cleanup(s));
}
