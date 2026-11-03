module Leaf(input clock, input [15:0] d, output reg [15:0] q);
  always @(posedge clock) q <= d + 16'h35;
endmodule
