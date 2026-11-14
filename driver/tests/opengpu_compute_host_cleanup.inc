/* Shared wire format, compute-only state. Synchronous real MMU shootdown. */
static FILE *compute_mmio_file(const char *path,char **temporary)
{
    struct stat st;if(!path || !*path || !lstat(path,&st) || errno!=ENOENT)return NULL;
    size_t size=strlen(path)+5;*temporary=malloc(size);if(!*temporary)return NULL;
    snprintf(*temporary,size,"%s.tmp",path);FILE *f=fopen(*temporary,"wbx");
    if(!f){free(*temporary);*temporary=NULL;}return f;
}
static int compute_mmio_close(FILE *f,const char *path,char *temporary)
{
    int error=ferror(f)?-EIO:0;if(fflush(f))error=-EIO;if(fclose(f))error=-EIO;
    if(!error && link(temporary,path))error=-errno;
    if(remove(temporary) && !error)error=-errno;
    free(temporary);return error;
}
static int compute_mmio_shadow_pte(void *opaque,dma_addr_t physical,const void *bytes,size_t size)
{
    struct compute_mmio_state *m=opaque;
    struct compute_mmio_pte_shadow *p=calloc(1,sizeof(*p));if(!p)return -ENOMEM;
    p->words=malloc(size);if(!p->words){free(p);return -ENOMEM;}
    memcpy(p->words,bytes,size);p->physical=physical;p->size=size;p->next=m->pte;m->pte=p;
    return 0;
}
static int compute_mmio_diff_pte(void *opaque,dma_addr_t physical,const void *bytes,size_t size)
{
    struct compute_mmio_state *m=opaque;struct compute_mmio_pte_shadow *p=m->pte;
    while(p && p->physical!=physical)p=p->next;
    if(!p || p->size!=size)return -EIO;
    const u32 *current=bytes;
    for(size_t n=0;n<size/4;n++)if(current[n]!=p->words[n]){
        /* Cleanup only revokes existing mappings. Rollback after transport
         * failure stays local and can never produce a successful receipt. */
        if(current[n] || !(p->words[n]&1) || physical+4*n>UINT32_MAX)return -EIO;
        compute_mmio_action(m,4,(u32)(physical+4*n),0,p->words[n]);p->words[n]=0;
    }
    return 0;
}
static void compute_mmio_dma_wmb(void)
{
    if(!compute_running || !compute_running->gpu.hw.compute_mmio)return;
    struct compute_mmio_state *m=compute_running->gpu.hw.compute_mmio;
    if(m->cleanup_active && !m->cleanup_error)
        m->cleanup_error=fixture_mmu_visit_pte_pages(&compute_running->gpu,compute_mmio_diff_pte,m);
}
static char *compute_mmio_cleanup_path(const struct compute_mmio_state *m,const char *suffix,unsigned sequence)
{
    size_t size=strlen(m->cleanup_directory)+80;char *path=malloc(size);if(!path)return NULL;
    if(sequence==UINT32_MAX)snprintf(path,size,"%s/%s",m->cleanup_directory,suffix);
    else snprintf(path,size,"%s/cleanup-%u.%s",m->cleanup_directory,sequence,suffix);
    return path;
}
static int compute_mmio_read_le(FILE *f,u32 *value)
{
    *value=0;for(unsigned n=0;n<4;n++){int c=fgetc(f);if(c==EOF)return -EIO;*value|=(u32)c<<(8*n);}return 0;
}
static int compute_mmio_cleanup_ack(const char *path,unsigned sequence)
{
    struct stat before,st;if(lstat(path,&before) || !S_ISREG(before.st_mode))return -EINVAL;
    int flags=O_RDONLY;
#ifdef O_NOFOLLOW
    flags|=O_NOFOLLOW;
#endif
    int fd=open(path,flags);if(fd<0)return -errno;
    if(fstat(fd,&st) || !S_ISREG(st.st_mode) || st.st_size!=28 ||
        before.st_dev!=st.st_dev || before.st_ino!=st.st_ino){close(fd);return -EINVAL;}
    FILE *f=fdopen(fd,"rb");if(!f){close(fd);return -EIO;}
    char magic[8];u32 v[5];int error=fread(magic,1,8,f)!=8 || memcmp(magic,"OGCLA001",8)?-EINVAL:0;
    for(unsigned n=0;n<5 && !error;n++)error=compute_mmio_read_le(f,&v[n]);
    if(!error && (v[0]!=sequence || v[1]!=GPU_UCMD_STATUS_READY || v[2]!=GPU_IRQ_ENABLE || v[3] || v[4]!=1))error=-EINVAL;
    if(fclose(f) && !error)error=-EIO;
    return error;
}
static int compute_mmio_cleanup_sync(struct opengpu_device *gpu)
{
    struct compute_mmio_state *m=compute_mmio_state(gpu);struct stat st;
    if(!m->cleanup_active || m->cleanup_done || m->cleanup_error || m->cleanup_sequence==UINT32_MAX)return -EINVAL;
    unsigned sequence=m->cleanup_sequence;
    char *trace=compute_mmio_cleanup_path(m,"trace",sequence),*ack=compute_mmio_cleanup_path(m,"observed",sequence);
    char *failed=compute_mmio_cleanup_path(m,"error",UINT32_MAX),*temporary=NULL;
    if(!trace || !ack || !failed){free(trace);free(ack);free(failed);return -ENOMEM;}
    int error=0;
    if(!lstat(ack,&st) || errno!=ENOENT){error=-EEXIST;goto out;}
    if(m->count-m->cleanup_start>UINT32_MAX){error=-ERANGE;goto out;}
    FILE *f=compute_mmio_file(trace,&temporary);if(!f){error=-EIO;goto out;}
    fwrite("OGCLN001",1,8,f);compute_mmio_le(f,m->count-m->cleanup_start,4);
    compute_mmio_actions(f,m,m->cleanup_start);error=compute_mmio_close(f,trace,temporary);if(error)goto out;
#ifdef OPENGPU_COMPUTE_MMIO_CLEANUP_TEST_PEER
    /* Explicit CPU regression peer only; never enabled by the Mesa producer. */
    OPENGPU_COMPUTE_MMIO_CLEANUP_TEST_PEER(gpu,m->cleanup_directory,sequence);
#endif
    unsigned timeout=compute_host_timeout_seconds();if(!timeout){error=-EINVAL;goto out;}
    struct timespec start,now,pause={.tv_nsec=10000000};
    if(clock_gettime(CLOCK_MONOTONIC,&start)){error=-EIO;goto out;}
    for(;;){
        if(!lstat(ack,&st)){error=compute_mmio_cleanup_ack(ack,sequence);break;}
        if(errno!=ENOENT){error=-errno;break;}
        if(!lstat(failed,&st)){error=-EIO;break;}
        if(clock_gettime(CLOCK_MONOTONIC,&now)){error=-EIO;break;}
        if(now.tv_sec-start.tv_sec>=(time_t)timeout){error=-ETIMEDOUT;break;}
        if(nanosleep(&pause,NULL) && errno!=EINTR){error=-errno;break;}
    }
    if(!error){++m->cleanup_sequence;m->cleanup_start=m->count;}
out:
    free(trace);free(ack);free(failed);return error;
}
