package epilogue

import chisel3._
import chisel3.util._

/** Top-level epilogue pipeline: MaxFinder -> ExponentExtract -> Requantize.
  * Wiring only — the actual logic lives in the three stage modules.
  *
  * MaxFinder is a real 5-cycle registered pipeline now, but
  * ExponentExtract and Requantize are purely combinational, riding
  * directly on MaxFinder's output — so `e` (and everything downstream of
  * it) only becomes valid 5 cycles after `v` arrives. Requantize also
  * needs the *original* per-element `v` values (not just the block max),
  * so those need to be delayed by the same 5 cycles to stay time-aligned
  * with `e` — otherwise Requantize would combine a stale/live `v` with a
  * 5-cycles-old `e` on the same clock edge.
  */
class Epilogue extends Module {
  val io = IO(new Bundle {
    val v = Input(Vec(32, SInt(20.W)))
    val e = Output(UInt(8.W))
    val p = Output(Vec(32, UInt(8.W)))
  })

  val maxFinder  = Module(new MaxFinder)
  val expExtract = Module(new ExponentExtract)
  val requant    = Module(new Requantize)

  maxFinder.io.in := io.v
  expExtract.io.m := maxFinder.io.out
  requant.io.v    := ShiftRegister(io.v, 5) // matches MaxFinder's 5-cycle pipeline latency
  requant.io.e    := expExtract.io.e

  io.e := expExtract.io.e
  io.p := requant.io.p
}
