#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <string.h>
#include "opengpu_drm.h"
struct drm_device { int unused; };
struct drm_file { int unused; };
#include "query_under_test.inc"
int main(void)
{
    struct drm_device drm={0}; struct drm_file file={0};
    struct drm_opengpu_query q={.type=0,.result=UINT64_C(0x123456789abcdef0),.available=77};
    struct drm_opengpu_query before=q;
    assert(opengpu_compute_query_ioctl(&drm,&q,&file)==-EOPNOTSUPP);
    assert(!memcmp(&q,&before,sizeof(q)));
    return 0;
}
