package physical

import circt.stage.ChiselStage
import java.io.PrintWriter

/** New for the physical-implementation project (spec-physical.md Phase 1).
  * Everything under `epilogue` is existing, already-verified RTL — this is
  * the one new file, and it does nothing but elaborate `Epilogue` (the
  * composed three-stage pipeline `EpilogueSpec` already exercises) to a
  * flat Verilog file for OpenLane, via ChiselStage's `emitVerilog`.
  *
  * `--disable-all-randomization` drops the simulation-only register-init
  * randomization firtool otherwise emits (guarded `` `ifdef RANDOMIZE ``
  * blocks calling `$random`) — synthesis-unfriendly, sim-only, and not
  * something a real Yosys run should have to parse around.
  *
  * `--default-layer-specialization=disable` drops firtool's default
  * verification-layer scaffolding (trailing `` `ifndef ``/`` `include ``
  * blocks referencing a separate `layers-Epilogue-Verification*.sv` file
  * that's never actually emitted alongside a single-file build) — empty
  * content either way (this design has no `assert`/`printf`), but
  * Verilator's linter chokes on the unresolvable `` `include `` regardless
  * of whether the layer is ever enabled. Caught running Phase 2's
  * synthesis-only pass, not by the earlier synthesizability scan.
  *
  * `--lowering-options=disallowLocalVariables`: after the MaxFinder fix,
  * firtool started emitting the tree's per-level combinational values as
  * `automatic logic` locals *inside* the register's `always` block rather
  * than top-level `wire`s — legal SystemVerilog, but OpenLane's
  * Yosys.JsonHeader step reads the source with a plain (non-SV) Verilog
  * frontend and chokes on `automatic`/`logic`. This flag forces firtool
  * back to flat top-level `wire` declarations, matching what OpenLane's
  * whole toolchain expects. Caught running Phase 2 a second time, after
  * the MaxFinder register fix changed firtool's emission shape.
  *
  * Chisel 7's `circt.stage.ChiselStage` only exposes `emitSystemVerilog`
  * (the older `emitVerilog` convenience method from pre-firtool Chisel is
  * gone) — with the above flag, the output is genuinely flat
  * Verilog-2005-style `always @`/`assign`/`wire`, not SV-only constructs.
  */
object EmitVerilog extends App {
  val verilog = ChiselStage.emitSystemVerilog(
    gen = new epilogue.Epilogue,
    firtoolOpts = Array(
      "--disable-all-randomization",
      "--default-layer-specialization=disable",
      "--lowering-options=disallowLocalVariables",
    ),
  )
  val outDir = new java.io.File("generated")
  outDir.mkdirs()
  val pw = new PrintWriter(new java.io.File(outDir, "Epilogue.v"))
  pw.write(verilog)
  pw.close()
  println(s"Wrote ${outDir.getPath}/Epilogue.v")
}
