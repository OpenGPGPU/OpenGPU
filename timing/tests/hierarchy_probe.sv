module Leaf(input clock, input [15:0] d, output reg [15:0] q);
  always @(posedge clock) q <= d + 16'h35;
endmodule

module HierarchyProbe(input clock, input [15:0] a, input [15:0] b, output [15:0] y);
  wire [15:0] first;
  Leaf u0(clock, a, first);
  Leaf u1(clock, first ^ b, y);
endmodule
