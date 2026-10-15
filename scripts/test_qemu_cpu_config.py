"""Automatic acceleration must respect backend, host, and user CPU choices."""
from qemu_cpu_config import select_cpu


def test_automatic_hvf_requires_matching_host_backend_and_signature():
    assert select_cpu("auto", "", "flashsim", "Darwin", "arm64", True) == ("hvf", "host")
    for sim, system, arch, signed in [
        ("verilator", "Darwin", "arm64", True),
        ("flashsim", "Linux", "aarch64", True),
        ("flashsim", "Darwin", "x86_64", True),
        ("flashsim", "Darwin", "arm64", False),
    ]:
        assert select_cpu("auto", "", sim, system, arch, signed) == ("tcg", "cortex-a53")


def test_explicit_acceleration_and_cpu_preferences_are_preserved():
    assert select_cpu("auto", "cortex-a53", "flashsim", "Darwin", "arm64", True) == ("tcg", "cortex-a53")
    assert select_cpu("tcg,thread=multi", "max", "flashsim", "Darwin", "arm64", True) == ("tcg,thread=multi", "max")
    assert select_cpu("hvf", "host,pauth=off", "flashsim", "Darwin", "arm64", False) == ("hvf", "host,pauth=off")
    assert select_cpu("kvm", "", "flashsim", "Linux", "aarch64", False) == ("kvm", "host")
