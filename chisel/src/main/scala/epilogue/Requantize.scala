package epilogue

import chisel3._
import chisel3.util._

/** Stage 3 — Requantization.
  *
  * Formula (phase1_pipeline_spec.md Stage 3, notes.md §7.1):
  *   shared_exp = E - 127                          // unbias E8M0
  *   P_i = Q_E4M3(V_i * 2^(-shared_exp))            // for i = 0..31
  *
  * Architecture decided (notes.md §7.2): 32 parallel shifters/paths, not
  * 1 shared/serial.
  *
  * Rounding decided (notes.md §7.3): round-to-nearest-even.
  *
  * Implementation note — this does NOT physically shift each V_i's
  * magnitude by shared_exp and then re-normalize a second time. Key
  * fact: multiplying/dividing by a power of two only moves the
  * EXPONENT, never changes the mantissa bit pattern. So each element's
  * magnitude is normalized once against its OWN leading bit (mantissa +
  * guard/round/sticky extracted right there), and shared_exp is applied
  * as a pure arithmetic adjustment to the exponent field afterward. This
  * avoids a second dynamic renormalizing shift on an already
  * dynamically-widened intermediate signal.
  *
  * Simplification, stated explicitly rather than silently assumed: exponent
  * under/overflow after the shared_exp adjustment clamps to E4M3's min/max
  * exponent field (flush-to-zero-exponent / saturate-to-max), not a full
  * IEEE-style subnormal encoding. Neither was pinned down anywhere in the
  * spec.
  *
  * Output packing: 1 sign + 4-bit exponent (bias 7) + 3-bit mantissa,
  * MSB-to-LSB, into the 8-bit UInt.
  */
class Requantize extends Module {
  val io = IO(new Bundle {
    val v = Input(Vec(32, SInt(20.W))) // raw accumulator elements
    val e = Input(UInt(8.W))           // E from Stage 2
    val p = Output(Vec(32, UInt(8.W))) // E4M3 elements
  })

  val shared_exp = io.e.zext - 127.S

  def quantize(shared_exp: SInt, in: SInt): UInt = {
    val mag = Mux(in < 0.S, (-in).asUInt, in.asUInt) // 20-bit magnitude
    val sign_bit = in < 0.S
    val isZero = mag === 0.U

    // Normalize mag to its own leading bit (meaningless when mag==0;
    // guarded by isZero below). Left-shift so the leading 1 always lands
    // at bit 19, then everything below is at fixed, known positions.
    val magLeadingBit = Log2(mag) // 0-19
    val normAmt = 19.U - magLeadingBit
    val magNorm = (mag << normAmt)(19, 0)

    val mantissa = magNorm(18, 16) // 3 bits right below the implicit leading 1
    val guard = magNorm(15)
    val sticky = magNorm(14, 0).orR

    // Round to nearest even: round up when strictly more than halfway
    // (guard & sticky), or exactly halfway landing on an odd kept
    // mantissa (guard & mantissa's own LSB) — ties go to even.
    val roundUp = guard && (sticky || mantissa(0))
    val mantissaRounded = mantissa +& roundUp.asUInt // 4 bits — catches 111 -> 1000
    val mantissaOverflow = mantissaRounded(3)
    val mantissaFinal = Mux(mantissaOverflow, 0.U(3.W), mantissaRounded(2, 0))

    // True (unbiased) exponent = mag's own leading-bit position, adjusted
    // by shared_exp, plus one more if rounding overflowed the mantissa
    // (111 -> 1000 means the true exponent bumped up by one).
    val expBeforeRound = magLeadingBit.zext -& shared_exp
    val trueExp = expBeforeRound +& Mux(mantissaOverflow, 1.S, 0.S)
    val biasedExp = trueExp +& 7.S // E4M3 bias

    val expHighClamped = Mux(biasedExp > 15.S, 15.S, biasedExp)
    val expClamped = Mux(expHighClamped < 0.S, 0.S, expHighClamped)
    val expField = expClamped.asUInt(3, 0)

    val packed = Cat(sign_bit, expField, mantissaFinal)
    Mux(isZero, 0.U(8.W), packed)
  }

  io.p := VecInit(io.v.map(v => quantize(shared_exp, v)))
}
