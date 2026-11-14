#!/usr/bin/env python3
"""Actual Mesa/Linux compute full-host producer. Requires external DUT receipts.

--capture-only is an explicit CPU build/capture check. It cannot produce a GPU
execution result or authorize retirement of resources in a live DUT.
"""
from test_gallium_compute_uniforms import main

if __name__ == '__main__':
    main(shared_memory=True, host_execution=True)
