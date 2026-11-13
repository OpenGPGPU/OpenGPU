#pragma once
#include "native_api_frame_fixture.hpp"
#include <filesystem>
#include <map>

namespace native_frame {
inline Fixture topology_fixture_contract(const std::filesystem::path &fixtures, bool two_cu_vga) {
  if (!two_cu_vga)
    return ApiFixture::read((fixtures / "native-api-extent-1x.commands").string(), true);
  auto contract = Fixture::read((fixtures / "desktop-composition.commands").string());
  require(contract.two_cu_vga() && contract.width == 640 && contract.height == 480 &&
    contract.id == 1 && contract.samples == 1, "two-CU VGA product fixture required");
  return contract;
}

inline void check_topology_discovery(const std::map<uint32_t, uint32_t> &words,
                                    bool two_cu_vga) {
  const uint32_t units = two_cu_vga ? 2u : 4u;
  const std::array<uint32_t, 16> compute{0x47435201,16,15,units,16,8,128,128,
    0xffffffff,16384,16,8,0x10000000,32,131072,4};
  const std::array<uint32_t, 8> native{0x474e4303,8,0x474e4a01,0x08000200,
    0xc2100800,0x08000800,0xe4908000,0x8813d900u | units};
  require(words.size() == compute.size() + native.size(), "complete GCR1/GNC3 discovery required");
  for (unsigned n = 0; n < compute.size(); ++n)
    require(words.at(0x360 + 4*n) == compute[n], "full compute profile required");
  for (unsigned n = 0; n < native.size(); ++n)
    require(words.at(0x160 + 4*n) == native[n], "full native storage and interface profile required");
}
} // namespace native_frame
