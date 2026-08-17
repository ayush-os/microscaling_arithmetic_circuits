package epilogue

import chisel3._
import chisel3.util._

/** Stage 2 — Exponent extraction.
  *
  * Phase 0's circuit (fully derived and verified on paper — notes.md
  * §4, phase1_pipeline_spec.md Stage 2). Combinational, no clocked
  * stage of its own:
  *
  *   isZero = NOR-reduce(M)                     // wide zero-detect over 20 bits
  *   raw    = leading_zero_count(M)              // priority encoder, 20-bit in -> 5-bit out
  *   x      = raw + 119                          // 119 = 127 (E8M0 bias) - 8 (emax_elem)
  *   x      = if (x > 254) 254 else x            // high clamp; 255 reserved for NaN
  *   E      = if (isZero) 0 else x
  *
  * (Low clamp `x < 0` is provably dead code for this design's numeric
  * range — notes.md §4.6 — not built.)
  *
  * Verified against notes.md §4's table: M=0->0, M=1->119, M=200,000->136,
  * M=262,144->137 (`ExponentExtractSpec`).
  *
  * Note: `raw +& 119.U`, not `+` — plain `+` on UInt is the non-growing
  * add (truncates to the wider operand's width, 7 bits here), which
  * silently wrapped x=136 to 8 before this was caught. `+&` grows by the
  * needed carry bit.
  */
class ExponentExtract extends Module {
  val io = IO(new Bundle {
    val m = Input(UInt(20.W))  // M from Stage 1
    val e = Output(UInt(8.W))  // E: E8M0-biased shared exponent
  })

  val isZero = !io.m.orR
  val raw = Log2(io.m)
  val x = raw +& 119.U
  val xClamped = Mux(x > 254.U, 254.U, x)
  io.e := Mux(isZero, 0.U, xClamped)
}
