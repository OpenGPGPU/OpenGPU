#!/usr/bin/env python3
"""Actual Mesa continuous producer/opcode14 source; needs external DUT receipts.

--capture-only compiles all original shaders and captures the first direct
producer. It cannot supply GPU-produced counts or complete the live chain.
"""
from test_gallium_compute_uniforms import main

if __name__ == '__main__':
    main(continuous_compute=True)
