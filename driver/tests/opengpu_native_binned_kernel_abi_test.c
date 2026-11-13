#include "../opengpu_native_binned_host_abi.h"
_Static_assert(sizeof(struct opengpu_native_binned_host_caps)==32,"GNC words");
_Static_assert(sizeof(struct opengpu_native_binned_completion_info)==48,"GNX words");
_Static_assert(_Alignof(struct opengpu_native_binned_host_caps)==_Alignof(unsigned int),"GNC alignment");
_Static_assert(_Alignof(struct opengpu_native_binned_completion_info)==_Alignof(unsigned int),"GNX alignment");
_Static_assert(sizeof(((struct opengpu_native_binned_host_caps *)0)->words[0])==4,"GNC word");
_Static_assert(sizeof(((struct opengpu_native_binned_completion_info *)0)->words[0])==4,"GNX word");
