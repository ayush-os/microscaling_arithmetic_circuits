package epilogue

import chisel3._
import chisel3.util._

/** Top-level epilogue: MaxFinder -> ExponentExtract -> Requantize.
  * Wiring only — the actual logic lives in the three stage modules.
  *
  * All three stages are now purely combinational (see
  * `ARCHITECTURE.md`'s "Design space beyond the 6-cycle constraint"): the
  * `ShiftRegister(io.v, 5)` that used to keep the raw per-element `v`
  * values time-aligned with MaxFinder's old 5-cycle pipeline latency is
  * gone, since there's no latency left to align against — `io.v` feeds
  * Requantize directly, same cycle `e` becomes valid.
  *
  * Still `extends Module`, not `RawModule`, even though nothing in this
  * design is clocked: matches the existing precedent of
  * `ExponentExtract`/`Requantize`, which were already purely
  * combinational and kept the implicit (unused) `clock`/`reset` ports
  * rather than dropping to `RawModule`. Keeping it means the physical
  * design can still declare a `CLOCK_PORT` in OpenLane's config and reuse
  * the standard clocked-SDC input/output-delay machinery to get a real
  * timing number for the now-fully-combinational chain, the same way
  * this project already relies on that machinery for the
  * ExponentExtract->Requantize chain within the current composed design
  * — rather than building bespoke clockless SDC/`set_max_delay` tooling
  * for a one-off `RawModule` port list, which would be new toolchain risk
  * for no additional numerical insight. See the "Real physical numbers"
  * section below for how that plays out.
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
  requant.io.v    := io.v
  requant.io.e    := expExtract.io.e

  io.e := expExtract.io.e
  io.p := requant.io.p
}
