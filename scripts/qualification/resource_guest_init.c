/* Local qualification init; no device, compiler or application behavior is mocked. */
#include <sys/mount.h>
#include <sys/reboot.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <linux/reboot.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static int load_module(const char *path)
{
    int fd=open(path,O_RDONLY|O_CLOEXEC);
    if(fd<0) return -1;
    int rc=(int)syscall(SYS_finit_module,fd,"",0);
    int saved=errno;
    if(close(fd) && !rc) return -1;
    errno=saved;
    if(!rc) printf("OPENGPU_QUAL_INIT module=%s loaded\n",path);
    return rc;
}

static int load_dependencies(void)
{
    char text[4096];
    int fd=open("/arti_driver_deps",O_RDONLY|O_CLOEXEC);
    if(fd<0) return -1;
    ssize_t n=read(fd,text,sizeof(text)-1);
    if(close(fd) || n<=0 || n==(ssize_t)sizeof(text)-1) return -1;
    text[n]=0;
    char *save=NULL;
    for(char *p=strtok_r(text,"\n",&save);p;p=strtok_r(NULL,"\n",&save)) {
        if(p[0]!='/' || load_module(p)) return -1;
    }
    return load_module("/arti_driver.ko");
}

static const char *select_app(void)
{
    char text[4096];
    int fd=open("/proc/cmdline",O_RDONLY|O_CLOEXEC);
    if(fd<0) return NULL;
    ssize_t n=read(fd,text,sizeof(text)-1);
    if(close(fd) || n<=0 || n==(ssize_t)sizeof(text)-1) return NULL;
    text[n]=0;
    const char *selection=NULL;
    char *save=NULL;
    for(char *p=strtok_r(text," \t\r\n",&save);p;p=strtok_r(NULL," \t\r\n",&save)) {
        if(strncmp(p,"opengpu.qual=",13)) continue;
        if(selection) return NULL;
        if(!strcmp(p,"opengpu.qual=h7")) selection="h7";
        else if(!strcmp(p,"opengpu.qual=h8")) selection="h8";
        else return NULL;
    }
    return selection;
}

static int run_app(const char *selection)
{
    const int h7=!strcmp(selection,"h7");
    const char *path=h7?"/opengpu/gallium_compute_glsl":"/opengpu/gallium_buffer_glsl";
    const char *sources=h7?"/opengpu/glsl-compute":"/opengpu/glsl-buffer";
    printf("OPENGPU_QUAL_INIT app=%s ramBase=0x40000000 ramEnd=0x100000000 pagemap=1 started\n",selection);
    fflush(NULL);
    pid_t child=fork();
    if(child<0) return -1;
    if(!child) {
        char *const argv[]={(char *)path,"--sources",(char *)sources,NULL};
        char *const envp[]={"PATH=/usr/bin:/bin","LANG=C","LC_ALL=C","TZ=UTC",
            "OPENGPU_QUALIFICATION_PAGEMAP=1",
            "OPENGPU_QUALIFICATION_RAM_BASE=0x40000000",
            "OPENGPU_QUALIFICATION_RAM_END=0x100000000",NULL};
        execve(path,argv,envp);
        perror("OPENGPU_QUAL_INIT execve");
        _exit(127);
    }
    int status;
    pid_t waited;
    do { waited=waitpid(child,&status,0); } while(waited<0 && errno==EINTR);
    if(waited!=child || !WIFEXITED(status) || WEXITSTATUS(status)) return -1;
    return 0;
}

int main(void)
{
    setvbuf(stdout,NULL,_IOLBF,0);
    int ok=!mount("proc","/proc","proc",0,NULL);
    ok &= !mount("sysfs","/sys","sysfs",0,NULL);
    ok &= !mount("devtmpfs","/dev","devtmpfs",0,NULL);
    int console=open("/dev/console",O_WRONLY|O_CLOEXEC);
    if(console>=0) {
        if(dup2(console,STDOUT_FILENO)<0 || dup2(console,STDERR_FILENO)<0) ok=0;
        close(console);
    } else ok=0;
    const char *selection=ok?select_app():NULL;
    if(!selection) ok=0;
    if(ok && load_dependencies()) ok=0;
    if(ok && run_app(selection)) ok=0;
    printf("OPENGPU_QUAL_INIT app=%s status=%s\n",selection?selection:"invalid",ok?"PASS":"FAIL");
    sync();
    sleep(1);
    puts("OPENGPU_QUAL_INIT done powering off");
    fflush(NULL);
    reboot(LINUX_REBOOT_CMD_POWER_OFF);
    for(;;) pause();
}
