#include "../opengpu_native_binned_host_abi.h"
#include "../opengpu_native_completion.h"
_Static_assert(sizeof(struct opengpu_native_binned_host_caps)==32,"GNC words");
_Static_assert(sizeof(struct opengpu_native_binned_completion_info)==48,"GNX words");
_Static_assert(_Alignof(struct opengpu_native_binned_host_caps)==_Alignof(unsigned int),"GNC alignment");
_Static_assert(_Alignof(struct opengpu_native_binned_completion_info)==_Alignof(unsigned int),"GNX alignment");
_Static_assert(sizeof(((struct opengpu_native_binned_host_caps *)0)->words[0])==4,"GNC word");
_Static_assert(sizeof(((struct opengpu_native_binned_completion_info *)0)->words[0])==4,"GNX word");
_Static_assert(sizeof(struct opengpu_native_completion)==56,"GNX copied snapshot");
#include "../opengpu_native_binned_abi.h"
_Static_assert(sizeof(opengpu_native_binned_word)==4,"native word width");
_Static_assert(sizeof(struct opengpu_native_binned_job)==512,"GNJ record");
_Static_assert(sizeof(struct opengpu_native_binned_frame)==2048,"GNF record");
_Static_assert(sizeof(struct opengpu_native_binned_draw)==2048,"GND record");
_Static_assert(OPENGPU_NATIVE_BINNED_JOB_JOB_FRAMEBASE_WORD==21,"frame root");
_Static_assert(OPENGPU_NATIVE_BINNED_JOB_JOB_DRAWBASE_WORD==16,"draw root");
_Static_assert(OPENGPU_NATIVE_BINNED_FRAME_FRAME_STATES_BASE_WORD==292,"state root");
_Static_assert(OPENGPU_NATIVE_BINNED_FRAME_FRAME_STATES_LIMIT_WORD==296,"33 bit state end");

#include "../opengpu_typed_fragment_state_abi.h"
_Static_assert(sizeof(opengpu_fragment_state_word)==4,"GFS kernel word width");
_Static_assert(sizeof(struct opengpu_typed_fragment_state)==512,"GFS record unchanged");
