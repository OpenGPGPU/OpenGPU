/* SPDX-License-Identifier: MIT */
#include "opengpu_native_joint_internal.h"
extern "C" opengpu_native_joint_program *opengpu_native_joint_program_create(pipe_context *pipe,const opengpu_native_joint_link *link,unsigned vertices){
    opengpu_native_joint_input input={};
    if(opengpu_native_joint_link_capture(link,&input))return nullptr;
    return opengpu_native_joint_compile(pipe,&input,vertices);
}
