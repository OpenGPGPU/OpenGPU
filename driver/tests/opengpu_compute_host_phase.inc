/* Source-side continuous owner transport. No shader execution or expected
 * output generation. A native peer must check every returned byte, publication
 * command and shootdown before acknowledging this distinct phase protocol. */
static unsigned compute_phase_original_count(struct compute_host_session *s)
{
 unsigned count=0;
 for(unsigned h=1;h<512;h++)if(s->render.originals[h] && s->render.originals[h]->refs)++count;
 return count;
}
static int compute_phase_read(FILE *f,u64 *value,unsigned bytes)
{
 *value=0;for(unsigned n=0;n<bytes;n++){int c=fgetc(f);if(c==EOF)return -EINVAL;*value|=(u64)c<<(8*n);}
 return 0;
}
static u32 compute_phase_opcode(struct compute_host_session *s)
{return opengpu_sched_job_from_base(s->head)->kernel.indirect_enabled?GPU_UCMD_OP_KERNEL_INDIRECT:GPU_UCMD_OP_KERNEL;}
int compute_host_export_originals(struct compute_host_session *s,const char *path)
{
 if(!s || !path || !s->indirect_declared || !s->captured || !s->exported || !s->head ||
    compute_mmio_state(&s->gpu)->cleanup_active)return -EBUSY;
 char *temporary=NULL;FILE *f=compute_mmio_file(path,&temporary);if(!f)return -EIO;
 fwrite("OGCOB001",1,8,f);compute_mmio_le(f,s->gpu.hw.unified_command_id,4);
 compute_mmio_le(f,compute_phase_opcode(s),4);compute_mmio_le(f,s->phase_epoch,4);
 compute_mmio_le(f,compute_phase_original_count(s),4);
 for(unsigned h=1;h<512;h++){
  const struct compute_original *o=s->render.originals[h];if(!o || !o->refs)continue;
  compute_mmio_le(f,h,4);compute_mmio_le(f,0,4);
  compute_mmio_le(f,o->gem.dma_addr,8);compute_mmio_le(f,o->gem.base.size,8);
  if(fwrite(o->gem.vaddr,1,o->gem.base.size,f)!=o->gem.base.size){
   fclose(f);remove(temporary);free(temporary);return -EIO;
  }
 }
 return compute_mmio_close(f,path,temporary);
}
int compute_host_import_originals(struct compute_host_session *s,const char *path)
{
 if(!s || !path || !s->indirect_declared || !s->observed || !s->captured || !s->exported ||
    !s->head || s->freed || s->phase_readback || compute_mmio_state(&s->gpu)->cleanup_active)return -EBUSY;
 struct stat before,st;if(lstat(path,&before) || !S_ISREG(before.st_mode))return -EINVAL;
 int flags=O_RDONLY;
#ifdef O_NOFOLLOW
 flags|=O_NOFOLLOW;
#endif
 int fd=open(path,flags);if(fd<0)return -errno;
 if(fstat(fd,&st) || !S_ISREG(st.st_mode) || before.st_dev!=st.st_dev || before.st_ino!=st.st_ino){close(fd);return -EINVAL;}
 FILE *f=fdopen(fd,"rb");if(!f){close(fd);return -EIO;}
 u8 *data[512]={0};char magic[8];u64 value=0;int error=-EINVAL;
 if(fread(magic,1,8,f)!=8 || memcmp(magic,"OGCOB001",8))goto out;
 if(compute_phase_read(f,&value,4) || value!=s->gpu.hw.unified_command_id)goto out;
 if(compute_phase_read(f,&value,4) || value!=compute_phase_opcode(s))goto out;
 if(compute_phase_read(f,&value,4) || value!=s->phase_epoch)goto out;
 if(compute_phase_read(f,&value,4) || value!=compute_phase_original_count(s))goto out;
 for(unsigned h=1;h<512;h++){
  const struct compute_original *o=s->render.originals[h];if(!o || !o->refs)continue;
  if(compute_phase_read(f,&value,4) || value!=h || compute_phase_read(f,&value,4) || value)goto out;
  if(compute_phase_read(f,&value,8) || value!=o->gem.dma_addr)goto out;
  if(compute_phase_read(f,&value,8) || value!=o->gem.base.size)goto out;
  data[h]=malloc(o->gem.base.size);if(!data[h]){error=-ENOMEM;goto out;}
  if(fread(data[h],1,o->gem.base.size,f)!=o->gem.base.size)goto out;
  const u8 *old=o->gem.vaddr;unsigned range=0;
  for(size_t n=0;n<o->gem.base.size;n++)if(data[h][n]!=old[n]){
   const u64 address=o->gem.dma_addr+n;
   while(range<s->captured_writable_count && s->captured_writable[range].end<=address)++range;
   if(s->completion_error || range==s->captured_writable_count ||
      address<s->captured_writable[range].first)goto out;
  }
 }
 if(fgetc(f)!=EOF || ferror(f))goto out;
 error=0;
out:
 if(fclose(f) && !error)error=-EIO;
 /* Validate the complete transfer before changing any original allocation. */
 if(!error){
  for(unsigned h=1;h<512;h++)if(data[h])memcpy(s->render.originals[h]->gem.vaddr,data[h],s->render.originals[h]->gem.base.size);
  s->phase_readback=true;
 }
 for(unsigned h=1;h<512;h++)free(data[h]);
 return error;
}
int compute_host_sync_status(struct compute_host_session *s,uint32_t handle,int32_t *status)
{
 if(!s || !status || !handle || handle>=ARRAY_SIZE(s->sync) || !s->sync[handle].fence)return -ENOENT;
 *status=dma_fence_get_status(s->sync[handle].fence);return 0;
}
int compute_host_begin_phase_cleanup(struct compute_host_session *s,const char *directory)
{
 struct stat st;if(!s || !directory || lstat(directory,&st) || !S_ISDIR(st.st_mode))return -EINVAL;
 struct compute_mmio_state *m=compute_mmio_state(&s->gpu);
 if(!s->indirect_declared || !s->observed || !s->phase_readback || !s->head || s->cancelled || s->phase_epoch==UINT32_MAX ||
    !s->context || !s->captured || s->freed || !s->exported || !m->pte || m->cleanup_active || m->cleanup_done ||
    m->phase_cleanup || m->cleanup_error || m->kernel_waiting || s->gpu.hw.unified_fence ||
    s->gpu.hw.active_fence || s->gpu.hw.wedged)return -EBUSY;
 m->cleanup_directory=strdup(directory);if(!m->cleanup_directory)return -ENOMEM;
 char *path=compute_mmio_cleanup_path(m,"cleanup.begin",UINT32_MAX),*temporary=NULL;
 if(!path){free(m->cleanup_directory);m->cleanup_directory=NULL;return -ENOMEM;}
 FILE *f=compute_mmio_file(path,&temporary);
 if(!f){free(path);free(m->cleanup_directory);m->cleanup_directory=NULL;return -EIO;}
 s->phase_context=s->context_id;s->phase_command=s->held.command_id;s->phase_opcode=compute_phase_opcode(s);
 fwrite("OGCPB001",1,8,f);
 for(unsigned n=0;n<5;n++)compute_mmio_le(f,((u32[]){1,s->phase_command,s->phase_context,s->phase_opcode,s->phase_epoch})[n],4);
 int error=compute_mmio_close(f,path,temporary);free(path);
 if(error){free(m->cleanup_directory);m->cleanup_directory=NULL;return error;}
 /* Caller-side context/resource teardown may close public handles. Retain
  * every original physical allocation through the final native quiet ACK. */
 for(unsigned h=1;h<512;h++)if(s->render.originals[h] && s->render.originals[h]->refs){
  drm_gem_object_get(&s->render.originals[h]->gem.base);s->phase_original_pinned[h]=true;
 }
 compute_running=s;m->cleanup_start=m->count;m->phase_cleanup=true;m->cleanup_active=true;return 0;
}
int compute_host_release_phase_job(struct compute_host_session *s)
{
 if(!s || !s->observed || !s->phase_readback || s->phase_job_freed || !s->head || !s->hardware ||
    s->hardware->error!=s->completion_error)return -EBUSY;
 struct compute_mmio_state *m=compute_mmio_state(&s->gpu);
 if(!m->phase_cleanup || !m->cleanup_active || m->cleanup_error)return -EBUSY;
 compute_running=s;
 if(s->completion_error){
  if(!s->context || !s->context->last_fence ||
     dma_fence_get_status(s->context->last_fence)!=s->completion_error)return -EINVAL;
  int error=opengpu_context_quiesce(&s->render,s->context);
  if(error!=s->completion_error || s->context->last_fence)return error?error:-EIO;
 }
 opengpu_sched_free_job(s->head);
 if(s->gpu.hw.wedged || m->cleanup_error)return -EIO;
 s->head=s->tail=NULL;s->queued=0;dma_fence_put(s->hardware);s->hardware=NULL;
 s->phase_job_freed=true;return 0;
}
int compute_host_phase_invalidate(struct compute_host_session *s,const struct drm_opengpu_invalidate *request)
{
 if(!s || !request || !s->phase_job_freed || s->head || !request->bytes ||
    request->context_id!=s->phase_context || request->flags || request->wait_event || request->signal_event)return -EBUSY;
 struct compute_mmio_state *m=compute_mmio_state(&s->gpu);
 if(!m->phase_cleanup || !m->cleanup_active || m->cleanup_error)return -EBUSY;
 struct compute_original *o=request->handle<512?s->render.originals[request->handle]:NULL;
 if(!o || !o->refs || request->offset>o->gem.base.size || request->bytes>o->gem.base.size-request->offset ||
    ((request->offset|request->bytes)&63))return -EINVAL;
 /* Publication may include only the cache lines intersecting admitted writes,
  * including their within-GEM padding. It can never expand to an arbitrary BO. */
 const u64 first=o->gem.dma_addr+request->offset,end=first+request->bytes;u64 covered=first;
 /* Walk the sorted interval union, independent of a full-size BO's line
  * count. Adjacent writable views may share their publication cache line. */
 for(unsigned n=0;n<s->captured_writable_count && covered<end;n++){
  const struct compute_writable_range *r=&s->captured_writable[n];
  if(r->first<o->gem.dma_addr || r->end>o->gem.dma_addr+o->gem.base.size)continue;
  const u64 begin=r->first&~63ull,limit=(r->end+63)&~63ull;
  if(limit<=covered)continue;
  if(begin>covered)return -EINVAL;
  covered=limit;
 }
 if(covered<end)return -EINVAL;
 compute_running=s;struct drm_opengpu_invalidate copy=*request;
 int error=opengpu_compute_invalidate_ioctl(&s->drm,&copy,&s->file);if(error)return error;
 assert(s->head && s->head==s->tail);
 struct opengpu_sched_job *job=opengpu_sched_job_from_base(s->head);
 for(struct fixture_dependency *d=job->base.dependencies;d;d=d->next)if(!dma_fence_is_signaled(d->fence))return -EBUSY;
 struct dma_fence *fence=opengpu_job_run(job);
 if((intptr_t)fence<0 && (intptr_t)fence>=-4095)return (int)(intptr_t)fence;
 if(!fence)return -EIO;
 compute_mmio_progress(&s->gpu);
 /* Polling the actual completion drain does not acknowledge the sticky IRQ.
  * Capture the original IRQ handler after that poll; the native peer must
  * observe the real pending bit and replay its W1C before acknowledging this
  * batch. No completion or IRQ from this control peer is GPU evidence. */
 m->regs[GPU_REG_IRQ/4]|=GPU_IRQ_PENDING;
 opengpu_irq_handler(0,&s->gpu);
 if(dma_fence_get_status(fence)!=1){dma_fence_put(fence);return -EIO;}
 dma_fence_signal(&s->head->s_fence->finished);opengpu_sched_free_job(s->head);
 s->head=s->tail=NULL;s->queued=0;dma_fence_put(fence);
 /* These are source-control observations. The native phase ACK still has to
  * prove the identical opcode5 command/completion/POP and unchanged RAM. */
 return m->cleanup_error?m->cleanup_error:0;
}
int compute_host_end_phase_cleanup(struct compute_host_session *s)
{
 if(!s || !s->phase_job_freed || !s->phase_readback || s->head || s->hardware ||
    s->context || idr_find(&s->render.contexts,s->phase_context) || s->gpu.hw.wedged ||
    s->gpu.hw.unified_fence || s->gpu.hw.active_fence || s->vm_destroyed)return -EBUSY;
 struct compute_mmio_state *m=compute_mmio_state(&s->gpu);
 if(!m->phase_cleanup || !m->cleanup_active || m->cleanup_done || m->cleanup_error)return -EBUSY;
 int error=compute_host_empty_contexts(s,NULL);if(error)return error;
 if(m->count!=m->cleanup_start)m->cleanup_error=compute_mmio_cleanup_sync(&s->gpu);
 if(m->cleanup_error)return m->cleanup_error;
 if(!m->cleanup_sequence)return -EINVAL;
 char *path=compute_mmio_cleanup_path(m,"cleanup.done",UINT32_MAX),*temporary=NULL;if(!path)return -ENOMEM;
 FILE *f=compute_mmio_file(path,&temporary);if(!f){free(path);return -EIO;}
 fwrite("OGCPD001",1,8,f);compute_mmio_le(f,m->cleanup_sequence,4);
 compute_mmio_le(f,s->phase_command,4);compute_mmio_le(f,s->phase_context,4);compute_mmio_le(f,s->phase_epoch,4);
 error=compute_host_empty_contexts(s,f);
 int close_error=compute_mmio_close(f,path,temporary);free(path);if(!error)error=close_error;
 if(!error){m->cleanup_active=false;m->cleanup_done=true;}return error;
}
int compute_host_resume_phase(struct compute_host_session *s,const struct compute_host_phase_receipt *receipt)
{
 if(!s || !receipt || !s->phase_job_freed || s->head || s->context || s->hardware || s->gpu.hw.wedged ||
    s->phase_epoch==UINT32_MAX || idr_find(&s->render.contexts,s->phase_context))return -EBUSY;
 struct compute_mmio_state *m=compute_mmio_state(&s->gpu);
 if(!m->phase_cleanup || !m->cleanup_done || m->cleanup_active || m->cleanup_error)return -EBUSY;
 if(receipt->command_id!=s->phase_command || receipt->context_id!=s->phase_context ||
    receipt->epoch!=s->phase_epoch || receipt->batches!=m->cleanup_sequence ||
    receipt->status!=GPU_UCMD_STATUS_READY || receipt->irq!=GPU_IRQ_ENABLE || receipt->irq_asserted ||
    receipt->external_drained!=1 || receipt->quiet_cycles!=32 || receipt->read_only_guards!=1)return -EINVAL;
 int error=compute_host_empty_contexts(s,NULL);if(error)return error;
 for(unsigned h=1;h<512;h++)if(s->phase_original_pinned[h]){
  compute_original_drop(s->render.originals[h]);s->phase_original_pinned[h]=false;
 }
 while(m->pte){struct compute_mmio_pte_shadow *p=m->pte;m->pte=p->next;free(p->words);free(p);}
 free(m->cleanup_directory);m->cleanup_directory=NULL;m->count=m->cleanup_start=0;m->cleanup_sequence=0;
 m->cleanup_done=m->phase_cleanup=false;m->kernel_submissions=m->invalidates=m->pops=0;
 s->exported=s->captured=s->drained=s->observed=s->prepared_execution=false;
 s->phase_job_freed=s->phase_readback=false;s->retained_execution=true;
 s->context_id=s->phase_context=s->phase_command=s->phase_opcode=0;++s->phase_epoch;
 s->completion_error=0;s->held=(struct compute_host_held_receipt){0};
 memset(&s->submitted,0,sizeof(s->submitted));memset(&s->submitted_indirect,0,sizeof(s->submitted_indirect));
 free(s->captured_writable);s->captured_writable=NULL;s->captured_writable_count=0;
 return 0;
}
