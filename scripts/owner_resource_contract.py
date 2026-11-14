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
