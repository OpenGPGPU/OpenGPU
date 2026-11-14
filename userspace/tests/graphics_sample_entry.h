/* SPDX-License-Identifier: MIT */
#ifndef GRAPHICS_SAMPLE_ENTRY_H
#define GRAPHICS_SAMPLE_ENTRY_H
#include <errno.h>
#include <stdlib.h>
#include <string.h>

struct graphics_sample_entry {
    const char *sources, *output, *profile;
};

/* Compile-time mode selection only. Discovery is subsequently decoded through
 * the strict112 kernel reader; these paths never authorize a device profile. */
static inline int graphics_sample_entry_options(int argc,char *const argv[],struct graphics_sample_entry *out)
{
    const char *old_directory=getenv("OPENGPU_NATIVE_HOST_DIRECTORY");
    const char *old_profile=getenv("OPENGPU_NATIVE_HOST_PROFILE");
    const char *directory=getenv("OPENGPU_SAMPLE_GRAPHICS_HOST_DIRECTORY");
    const char *profile=getenv("OPENGPU_SAMPLE_GRAPHICS_HOST_PROFILE");
    struct graphics_sample_entry value;
    if(!out || !argv || old_directory || old_profile)return -EINVAL;
#ifdef OPENGPU_NATIVE_SAMPLE_GRAPHICS_LIVE
#if OPENGPU_NATIVE_SAMPLE_GRAPHICS_LIVE != 1
#error "ordinary112 live entry requires an explicit value of one"
#endif
    if(argc!=2 || !directory || !profile)return -EINVAL;
    value=(struct graphics_sample_entry){argv[1],directory,profile};
#else
    if(argc!=3 || directory || profile)return -EINVAL;
    value=(struct graphics_sample_entry){argv[1],argv[1],argv[2]};
#endif
    if(!value.sources || !*value.sources || strlen(value.sources)>4032 ||
        !value.output || !*value.output || strlen(value.output)>4032 ||
        !value.profile || !*value.profile || strlen(value.profile)>=4096)return -EINVAL;
    *out=value;return 0;
}
#endif
