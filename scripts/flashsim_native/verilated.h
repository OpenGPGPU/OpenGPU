#pragma once
#include <cstdint>
class VerilatedContext {
public:
  void commandArgs(int, char **) {}
  void timeInc(uint64_t) {}
  bool gotFinish() const { return false; }
};
