module HierarchyProbe(input clock, input [15:0] a, input [15:0] b, output [15:0] y);
  wire [15:0] first;
  Leaf u0(.clock(clock), .d(a), .q(first), .unused());
  Leaf u1(.clock(clock), .d(first ^ b), .q(y), .unused());
endmodule
