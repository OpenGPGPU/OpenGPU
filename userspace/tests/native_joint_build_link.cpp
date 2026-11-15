/* SPDX-License-Identifier: MIT */
/* Link and call the actual C API from the real Mesa C++ frontend binary. */
#include "opengpu_native_joint_build_source.h"
#include <cassert>
#include <cstring>
extern "C" void native_joint_build_linkage_test(void)
{
    assert(!opengpu_native_joint_build_source_create(nullptr));
    opengpu_native_joint_build_source_destroy(nullptr);
    opengpu_native_joint_build_description d,before;
    std::memset(&d,0xa7,sizeof(d));before=d;
    assert(opengpu_native_joint_build_source_describe(nullptr,&d)==-1);
    assert(!std::memcmp(&d,&before,sizeof(d)));
}
