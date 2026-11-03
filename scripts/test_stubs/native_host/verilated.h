#pragma once
#include <cstdint>

class VerilatedContext {
 public:
  void commandArgs(int, char **) {}
  void timeInc(uint64_t value) { now += value; }
  uint64_t time() const { return now; }
  bool gotFinish() const { return finished; }
  bool finished = false;
 private:
  uint64_t now = 0;
};
