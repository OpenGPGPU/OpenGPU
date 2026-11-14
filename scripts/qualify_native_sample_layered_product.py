#!/usr/bin/env python3
"""Run the original six ABI1 scenes on the exact complete 112-word sample owner.

This is same-host product qualification, not SampleID/Position builtin coverage.
"""
import qualify_native_layered_product as layered

CONTRACT = layered.SAMPLE_CONTRACT
common, flash, owner, ROOT = layered.common, layered.flash, layered.owner, layered.ROOT


class Runner(layered.Runner):
    contract = CONTRACT


def validate_rows(rows, product):
    return layered.validate_rows(rows, product, contract=CONTRACT)


def check_manifest(manifest, *, rtl_emitted=True):
    return layered.check_contract_manifest(manifest, CONTRACT, rtl_emitted=rtl_emitted)


def marker(product, tile_log2):
    return layered.marker(product, tile_log2, contract=CONTRACT)


def verify_marker(log, product, tile_log2):
    return layered.verify_marker(log, product, tile_log2, contract=CONTRACT)


def main():
    return layered.main(contract=CONTRACT, runner_type=Runner)


if __name__ == '__main__':
    main()
