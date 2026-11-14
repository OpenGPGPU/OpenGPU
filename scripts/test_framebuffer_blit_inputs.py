#!/usr/bin/env python3
"""Actual caller capture + strict host-reader CPU checks, never a GPU result."""
import os
from pathlib import Path
import shlex
import struct
import subprocess
import sys
import tempfile

from framebuffer_blit_fixture import BlitFixture, NAMES, checked_inputs, digest

ROOT = Path(__file__).resolve().parents[1]


def main():
    (ROOT / ".artifacts").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="blit-inputs-", dir=ROOT / ".artifacts") as temporary:
        tmp = Path(temporary)
        env = os.environ.copy()
        env.update(TMPDIR=temporary, XDG_CACHE_HOME=str(tmp / "cache"), CCACHE_DIR=str(tmp / "ccache"),
                   ASAN_OPTIONS="halt_on_error=1" + (":detect_leaks=1" if sys.platform == "linux" else ""),
                   UBSAN_OPTIONS="halt_on_error=1")
        subprocess.run([sys.executable, "-B", str(ROOT / "scripts/test_gallium_compute.py"),
                        "--fixture", "framebuffer-blit", "--artifacts", str(tmp / "inputs")], check=True, env=env)
        pin = digest(tmp / "inputs/manifest.json")
        assert set(checked_inputs(tmp / "inputs", ROOT, pin)) == set(NAMES)
        source = tmp / "reader.cpp"
        source.write_text('''#include "native_frame_axi.hpp"
#include "native_framebuffer_blit_fixture.hpp"
#include "native_framebuffer_blit_lease.hpp"
#include "../driver/opengpu_storage_descriptor.h"
#include <iostream>
int main(int argc,char **argv) {
  try {
    using namespace framebuffer_blit;
    using Lease=framebuffer_blit::Lease<native_frame::Memory>;
    if(argc!=2)return 2;const auto f=Input::read(argv[1]);Lease lease(f);const auto initial=lease.memory;
    lease.verify(initial,f,false);
    auto walk=[&](uint32_t root,uint32_t va,uint32_t physical,uint32_t flags) {
      const auto link=Lease::get(lease.memory,root+(va>>22)*4);
      require((link&1023)==1,"root permission differs");
      const auto pte=Lease::get(lease.memory,((link>>10)<<12)+((va>>12)&1023)*4);
      require((pte&1023)==flags && ((pte>>10)<<12)==physical,"leaf translation/permission differs");
    };
    walk(lease.instruction_root,Lease::pc,lease.code_physical,0x4b);
    walk(lease.vector_root,Lease::kernarg,lease.kernarg_physical,0x43);
    walk(lease.vector_root,Lease::table,lease.table_physical,0x43);
    for(unsigned i=0;i<3;i++) {
      for(uint32_t n=0;n<f.views[i].allocation;n+=4096)
        walk(lease.vector_root,lease.virtual_base[i]+n,lease.physical_base[i]+n,i==1?0xc7:0x43);
      const uint32_t record=lease.table_physical+i*64;
      opengpu_storage_descriptor descriptor{};
      descriptor.context=f.context;descriptor.pass=7;descriptor.generation=1;descriptor.binding=i;
      descriptor.base=descriptor.allocation_base=lease.virtual_base[i];
      descriptor.limit=uint64_t(lease.virtual_base[i])+f.views[i].limit;
      descriptor.allocation_limit=(descriptor.limit+63)&~uint64_t(63);
      descriptor.readable=bool(f.views[i].access&1);descriptor.writable=bool(f.views[i].access&2);
      uint32_t encoded[16];require(opengpu_encode_storage_descriptor(encoded,&descriptor),"real GSB encoder rejected");
      for(unsigned n=0;n<16;n++)require(Lease::get(lease.memory,record+n*4)==encoded[n],
        "wire GSB differs from actual kernel encoder");
      require(Lease::get(lease.memory,record)==0x47534201 && Lease::get(lease.memory,record+12)==f.context &&
        Lease::get(lease.memory,record+24)==i && Lease::get(lease.memory,record+32)==1 &&
        Lease::get(lease.memory,record+36)==(i==1) &&
        Lease::get(lease.memory,record+44)==lease.virtual_base[i]+f.views[i].limit &&
        Lease::get(lease.memory,record+56)==lease.virtual_base[i]+((f.views[i].limit+63)&~63u),
        "GSB identity/authority differs from actual caller");
    }
    require((f.views[0].handle==f.views[1].handle)==(lease.physical_base[0]==lease.physical_base[1]),
      "GEM backing identity differs");
    for(size_t n=0;n<f.expected.size();n++)lease.memory.byte(lease.physical_base[1]+n,f.expected[n]);
    lease.verify(initial,f,true);
    lease.memory.byte(lease.code_physical,lease.memory.byte(lease.code_physical)^1);
    bool rejected=false;try{lease.verify(initial,f,true);}catch(const std::exception &){rejected=true;}
    require(rejected,"read-only program mutation accepted");return 0;
  }
  catch(const std::exception &e){std::cerr<<e.what()<<'\\n';return 1;}
}
''')
        binary = tmp / "reader"
        subprocess.run(shlex.split(os.environ.get("CXX", "c++")) + ["-std=c++17", "-O1", "-g",
            "-Wall", "-Wextra", "-Werror", "-Wno-missing-field-initializers", "-Wno-unused-function",
            "-Wno-missing-braces",
            "-fsanitize=address,undefined", "-fno-omit-frame-pointer", "-I" + str(ROOT / "scripts"),
            "-I" + str(ROOT / "driver"),
            str(source), "-o", str(binary)], check=True, env=env)
        for name in NAMES:
            path = tmp / "inputs" / (name + ".blit")
            BlitFixture.read(path.read_bytes())
            subprocess.run([str(binary), str(path)], check=True, env=env)
        original = (tmp / "inputs/linear-0.blit").read_bytes()
        parsed = BlitFixture.read(original)
        coordinate = 96 + 2048 + 64 + parsed.views[0].allocation + parsed.views[1].allocation
        expected = coordinate + parsed.views[2].allocation
        mutations = []

        def word(offset, value):
            copy = bytearray(original)
            struct.pack_into("<I", copy, offset, value)
            mutations.append(bytes(copy))

        # Format/ownership bounds must fail before a model or VM is created.
        for offset, value in ((8, 2), (12, 0), (16, 2044), (20, 60), (24, 0), (28, 9),
                              (32, 2), (36, 2), (48, 0), (52, 63), (56, 0xffffffff), (60, 3),
                              (64, parsed.views[2].handle), (76, 1), (92, 3),
                              (coordinate, 4), (coordinate + 4, 92), (coordinate + 8, 0x80),
                              (coordinate + 12, 0x8000), (coordinate + 16, 99),
                              (coordinate + 32, 1), (coordinate + 128, 0xffffffff),
                              (96, 0), (96 + 2048, 99)):
            word(offset, value)
        copy = bytearray(original)
        copy[expected] ^= 1
        mutations += [bytes(copy), original[:-1], original + b"\0"]
        for i, data in enumerate(mutations):
            path = tmp / "invalid.blit"
            path.write_bytes(data)
            result = subprocess.run([str(binary), str(path)], env=env, capture_output=True, text=True)
            if result.returncode != 1:
                raise RuntimeError(f"host reader accepted mutation {i}: {result.returncode}: {result.stderr}")
        victim = tmp / "inputs/linear-0.blit"
        victim.write_bytes(mutations[0])
        try:
            checked_inputs(tmp / "inputs", ROOT, pin)
        except ValueError:
            pass
        else:
            raise RuntimeError("original manifest accepted changed caller input")
        victim.write_bytes(original)
        try:
            checked_inputs(tmp / "inputs", ROOT, "0" * 64)
        except ValueError:
            pass
        else:
            raise RuntimeError("caller inputs accepted a wrong manifest pin")
        print(f"37 original-caller C++/ASan/UBSan inputs and {len(mutations)} rejection cases PASS; GPU execution pending")


if __name__ == "__main__":
    main()
