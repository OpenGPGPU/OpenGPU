module Leaf(input clock, input [15:0] d, output reg [15:0] q, output reg [15:0] unused);
  always @(posedge clock) begin
    q <= d + 16'h35;
    unused <= d ^ 16'h5a5a;
  end
endmodule
