#pragma once
#include "native_api_frame_fixture.hpp"
#include <filesystem>
#include <map>

namespace native_frame {
inline Fixture topology_fixture_contract(const std::filesystem::path &fixtures, bool two_cu_vga, bool alignment_v2 = false) {
  require(!(two_cu_vga && alignment_v2), "select one topology host profile");
  if (alignment_v2)
    return ApiFixture::read((fixtures / "native-api-extent-1x.commands").string(), true, true);
  if (!two_cu_vga)
    return ApiFixture::read((fixtures / "native-api-extent-1x.commands").string(), true);
  auto contract = Fixture::read((fixtures / "desktop-composition.commands").string());
  require(contract.two_cu_vga() && !contract.alignment_v2() && contract.width == 640 && contract.height == 480 &&
    contract.id == 1 && contract.samples == 1, "two-CU VGA product fixture required");
  return contract;
}

inline void check_topology_discovery(const std::map<uint32_t, uint32_t> &words,
                                    bool two_cu_vga, bool alignment_v2 = false) {
  require(!(two_cu_vga && alignment_v2), "select one topology host profile");
  if (alignment_v2) {
    require(words.size() == 72, "complete integrated 72-word discovery required");
    Fixture discovered{};
    for (const auto &block : std::array<std::pair<uint32_t, unsigned>, 8>{{
        {0x360,16}, {0x160,8}, {0x340,8}, {0x240,8}, {0x260,8}, {0x280,8}, {0x3a0,8}, {0x3c0,8}}})
      for (unsigned n = 0; n < block.second; ++n)
        discovered.resources.push_back({block.first + 4*n, words.at(block.first + 4*n)});
    require(discovered.two_cu_vga(), "integrated topology requires two CUs");
    discovered.validate_resources();
    return;
  }
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
