#pragma once

// The fixed Simulation advances its VerilatedContext with timeInc(). The
// runtime consults this legacy callback only while that context is still zero.
// Calling context.time() here would recurse through the same fallback.
double sc_time_stamp() { return 0.0; }
