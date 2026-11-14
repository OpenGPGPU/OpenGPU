"""Intermediate complete-owner resource composition, not execution evidence.

The old v1 table remains frozen. The compute stage adds checked shared atomics
on both CUs while preserving all its native/compute transports. Final v2 GS,
layered draws, query/TF and image qualification remain separate open gates;
this intermediate identity cannot be relabeled as the final product.
"""
from prepare_native_owner_flashsim import EXPECTED_WORDS as V1_WORDS

VERSION = 2
PROFILE = 'two-cu-vga60-owner-v2'
COMPUTE_STAGE = PROFILE + '-compute-stage'
COMPUTE_SCHEMA = 'opengpu-compute-owner-profile-v2'
COMPUTE_WORDS = [dict(word, value=word['value'] | (1 << 18))
                 if word['address'] == 0x368 else dict(word) for word in V1_WORDS]

# GNJ2/GNF3/GNY2 is a different packet factory. In particular the actual layered
# memory namespaces use 11 native transaction-ID bits instead of the old nine.
FULL_STAGE = 'geometry-compute'
FULL_WORDS = [dict(word, value={0x368: 0xfffff, 0x160: 0x474e4305,
    0x168: 0x474e4a02, 0x17c: 0x8813db02}.get(word['address'], word['value']))
    for word in V1_WORDS] + [dict(address=0x500+4*i, value=value) for i,value in enumerate(
        (0x47475301, 8, 31, 0x474e4a02, 0x474e4603, 0x474e5902, 0xff840206, 0x04001000))]
