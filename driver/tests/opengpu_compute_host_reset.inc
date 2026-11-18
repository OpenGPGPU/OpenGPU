/* Original Linux abort/reset capture. Only native observations authorize
 * reset progress; neither completion data nor shader output is created here. */
static char *compute_mmio_reset_path(const struct compute_mmio_state *m,const char *suffix)
{
 size_t size=strlen(m->reset_directory)+80;char *path=malloc(size);
 if(path)snprintf(path,size,"%s/record-0.reset.%s",m->reset_directory,suffix);
 return path;
}
static int compute_mmio_reset_busy(const char *path,u32 command,struct compute_host_reset_receipt *receipt)
{
 struct stat before,st;if(lstat(path,&before) || !S_ISREG(before.st_mode))return -EINVAL;
 int flags=O_RDONLY;
#ifdef O_NOFOLLOW
 flags|=O_NOFOLLOW;
#endif
 int fd=open(path,flags);if(fd<0)return -errno;
 if(fstat(fd,&st) || !S_ISREG(st.st_mode) || st.st_size!=32 ||
    before.st_dev!=st.st_dev || before.st_ino!=st.st_ino){close(fd);return -EINVAL;}
 FILE *f=fdopen(fd,"rb");if(!f){close(fd);return -EIO;}
 char magic[8];u32 v[6];int error=fread(magic,1,8,f)!=8 || memcmp(magic,"OGCRB001",8)?-EINVAL:0;
 for(unsigned n=0;n<6 && !error;n++)error=compute_mmio_read_le(f,&v[n]);
 if(!error && (v[0]!=command || v[1] ||
    (v[2]&~GPU_UCMD_STATUS_READY)!=(GPU_UCMD_STATUS_RESET_BUSY|GPU_UCMD_STATUS_VM_BUSY) ||
    (v[3]&~GPU_IRQ_PENDING) || v[4] || v[5]))error=-EINVAL;
 if(!error)*receipt=(struct compute_host_reset_receipt){v[0],v[1],v[2],v[3],v[4],v[5]};
 if(fclose(f) && !error)error=-EIO;
 return error;
}
static int compute_mmio_reset_trace(struct compute_mmio_state *m,const char *path,const char *magic,u32 command,size_t first)
{
 if(m->count<first || m->count-first>1024)return -ERANGE;
 char *temporary=NULL;FILE *f=compute_mmio_file(path,&temporary);if(!f)return -EIO;
 fwrite(magic,1,8,f);compute_mmio_le(f,command,4);compute_mmio_le(f,m->count-first,4);
 compute_mmio_actions(f,m,first);return compute_mmio_close(f,path,temporary);
}
static int compute_mmio_reset_sync(struct opengpu_device *gpu)
{
 struct compute_mmio_state *m=compute_mmio_state(gpu);struct stat st;
 if(!m->reset_active || !m->reset_issued || m->reset_busy_observed || m->reset_error)return -EINVAL;
 char *trace=compute_mmio_reset_path(m,"request"),*ack=compute_mmio_reset_path(m,"busy.response");
 char *failed=compute_mmio_reset_path(m,"error");int error=0;
 if(!trace || !ack || !failed){error=-ENOMEM;goto out;}
 if(!lstat(ack,&st) || errno!=ENOENT){error=-EEXIST;goto out;}
 error=compute_mmio_reset_trace(m,trace,"OGCRQ001",gpu->hw.unified_command_id,m->reset_start);
 if(error)goto out;
#ifdef OPENGPU_COMPUTE_MMIO_RESET_TEST_PEER
 /* Explicit CPU transport peer, absent from every actual Mesa producer. */
 OPENGPU_COMPUTE_MMIO_RESET_TEST_PEER(gpu,m->reset_directory);
#endif
 unsigned timeout=compute_host_timeout_seconds();if(!timeout){error=-EINVAL;goto out;}
 struct timespec start,now,pause={.tv_nsec=10000000};
 if(clock_gettime(CLOCK_MONOTONIC,&start)){error=-EIO;goto out;}
 struct compute_host_reset_receipt receipt={0};
 for(;;){
  if(!lstat(ack,&st)){error=compute_mmio_reset_busy(ack,gpu->hw.unified_command_id,&receipt);break;}
  if(errno!=ENOENT){error=-errno;break;}
  if(!lstat(failed,&st)){error=-EIO;break;}
  if(clock_gettime(CLOCK_MONOTONIC,&now)){error=-EIO;break;}
  if(now.tv_sec-start.tv_sec>=(time_t)timeout){error=-ETIMEDOUT;break;}
  if(nanosleep(&pause,NULL) && errno!=EINTR){error=-errno;break;}
 }
 if(!error){
  m->regs[GPU_REG_UCMD_STATUS/4]=receipt.status;m->regs[GPU_REG_IRQ/4]=receipt.irq;
  m->reset_busy_observed=true;m->reset_tail_start=m->count;
 }
out:
 free(trace);free(ack);free(failed);return error;
}
int compute_host_abort_captured(struct compute_host_session *s,const struct compute_host_reset_receipt *held,const char *directory)
{
 if(s && s->head && opengpu_sched_job_from_base(s->head)->kernel.indirect_enabled)return -EOPNOTSUPP;
 struct stat st;
 if(!s || !held || !directory || lstat(directory,&st) || !S_ISDIR(st.st_mode) ||
    !s->captured || s->drained || s->observed || s->cancelled || !s->head || !s->hardware ||
    dma_fence_is_signaled(s->hardware) || s->gpu.hw.unified_fence!=s->hardware ||
    !(s->gpu.hw.capabilities&GPU_CAP_UNIFIED_RESET) || s->gpu.hw.active_fence || s->gpu.hw.wedged ||
    s->gpu.hw.reset_pending || s->gpu.hw.aborting ||
    held->command_id!=s->gpu.hw.unified_command_id || held->legacy_status || held->external_drained ||
    !(held->status&GPU_UCMD_STATUS_VM_BUSY) ||
    (held->status&~(GPU_UCMD_STATUS_VM_BUSY|GPU_UCMD_STATUS_READY)) ||
    !(held->irq&GPU_IRQ_ENABLE) || (held->irq&~(GPU_IRQ_ENABLE|GPU_IRQ_PENDING)) ||
    held->irq_asserted!=!!(held->irq&GPU_IRQ_PENDING))return -EINVAL;
 struct compute_mmio_state *m=compute_mmio_state(&s->gpu);
 if(!m->kernel_waiting || m->receipt_loaded || m->reset_directory || m->reset_issued || m->cleanup_active)return -EBUSY;
 m->reset_directory=strdup(directory);if(!m->reset_directory)return -ENOMEM;
 m->regs[GPU_REG_STATUS/4]=held->legacy_status;m->regs[GPU_REG_UCMD_STATUS/4]=held->status;
 m->regs[GPU_REG_IRQ/4]=held->irq;m->reset_start=m->count;m->reset_active=true;s->cancelled=true;
 compute_running=s;compute_mmio_abort_actual(&s->gpu,-ECANCELED);m->reset_active=false;
 const struct opengpu_fault_snapshot *fault=&s->gpu.hw.last_fault;
 if(m->reset_error || !m->reset_busy_observed || !m->reset_issued || !s->gpu.hw.reset_pending ||
    s->gpu.hw.aborting || s->gpu.hw.unified_fence || !dma_fence_is_signaled(s->hardware) ||
    s->hardware->error!=-ECANCELED || !fault->sequence || fault->error!=-ECANCELED ||
    fault->flags!=(OPENGPU_FAULT_VALID|OPENGPU_FAULT_ABORTED|OPENGPU_FAULT_RESET_ISSUED) ||
    fault->command_id!=held->command_id || fault->expected_command_id!=held->command_id ||
    fault->opcode!=GPU_UCMD_OP_KERNEL || fault->expected_opcode!=GPU_UCMD_OP_KERNEL ||
    fault->status || fault->bytes_processed){s->gpu.hw.wedged=true;return m->reset_error?m->reset_error:-EIO;}
 char *tail=compute_mmio_reset_path(m,"tail");if(!tail)return -ENOMEM;
 int error=compute_mmio_reset_trace(m,tail,"OGCRL001",held->command_id,m->reset_tail_start);free(tail);
 if(!error)s->completion_error=-ECANCELED;
 return error;
}
static bool compute_reset_ack_valid(struct compute_host_session *s,const struct compute_host_reset_receipt *receipt,bool retired)
{
 return receipt && receipt->command_id==s->gpu.hw.unified_command_id && !receipt->legacy_status &&
  receipt->status==GPU_UCMD_STATUS_READY && receipt->external_drained==1 &&
  receipt->irq==(retired?GPU_IRQ_ENABLE:GPU_IRQ_ENABLE|GPU_IRQ_PENDING) && receipt->irq_asserted==(retired?0u:1u);
}
int compute_host_drain_reset(struct compute_host_session *s,const struct compute_host_reset_receipt *receipt,const char *path)
{
 if(s && s->head && opengpu_sched_job_from_base(s->head)->kernel.indirect_enabled)return -EOPNOTSUPP;
 if(!s || !path || !s->cancelled || !s->captured || s->drained || s->observed || !s->hardware || !s->head ||
    s->gpu.hw.wedged || !s->gpu.hw.reset_pending || !dma_fence_is_signaled(s->hardware) ||
    s->hardware->error!=-ECANCELED || s->completion_error!=-ECANCELED || !compute_reset_ack_valid(s,receipt,false))return -EINVAL;
 struct compute_mmio_state *m=compute_mmio_state(&s->gpu);
 if(!m->kernel_waiting || !m->reset_busy_observed || m->reset_error)return -EBUSY;
 char *temporary=NULL;FILE *f=compute_mmio_file(path,&temporary);if(!f)return -EIO;
 size_t first=m->count;unsigned pops=m->pops;compute_running=s;
 m->regs[GPU_REG_STATUS/4]=receipt->legacy_status;m->regs[GPU_REG_UCMD_STATUS/4]=receipt->status;
 m->regs[GPU_REG_IRQ/4]=receipt->irq;m->kernel_waiting=false;
 opengpu_irq_handler(0,&s->gpu);
 if(s->gpu.hw.reset_pending || m->pops!=pops || s->hardware->error!=-ECANCELED ||
    m->regs[GPU_REG_IRQ/4]!=GPU_IRQ_ENABLE){fclose(f);remove(temporary);free(temporary);return -EIO;}
 fwrite("OGCRV001",1,8,f);compute_mmio_le(f,receipt->command_id,4);compute_mmio_le(f,m->count-first,4);
 compute_mmio_actions(f,m,first);int error=compute_mmio_close(f,path,temporary);
 if(!error)s->drained=true;
 return error;
}
int compute_host_observe_reset_retired(struct compute_host_session *s,const struct compute_host_reset_receipt *receipt)
{
 if(s && s->head && opengpu_sched_job_from_base(s->head)->kernel.indirect_enabled)return -EOPNOTSUPP;
 if(!s || !s->cancelled || !s->drained || s->observed || !s->hardware || !s->head ||
    s->gpu.hw.reset_pending || s->gpu.hw.wedged || !dma_fence_is_signaled(s->hardware) ||
    s->hardware->error!=-ECANCELED || s->completion_error!=-ECANCELED || !compute_reset_ack_valid(s,receipt,true))return -EINVAL;
 struct compute_mmio_state *m=compute_mmio_state(&s->gpu);if(m->kernel_waiting || m->reset_error)return -EBUSY;
 m->regs[GPU_REG_UCMD_STATUS/4]=receipt->status;m->regs[GPU_REG_IRQ/4]=receipt->irq;
 dma_fence_set_error(&s->head->s_fence->finished,-ECANCELED);dma_fence_signal(&s->head->s_fence->finished);
 s->observed=true;return 0;
}
