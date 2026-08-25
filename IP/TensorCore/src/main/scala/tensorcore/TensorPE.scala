//implementation of single tensor processing element (PE)
package tensorcore

import chisel3._
import chisel3.util._

import fudian.FMUL

class TensorPEIO(val expWidth: Int, val precision: Int) extends Bundle{
    val a = Input(UInt((expWidth + precision).W))       //input a
    val b = Input(UInt((expWidth + precision).W))       //input b
    val rm = Input(UInt(3.W))
    val valid = Input(UInt(1.W))    //input is vaild
    val result = Output(UInt((expWidth + precision).W))
    val ready = Output(UInt(1.W))   //result is ready
    //val fflags = Output(UInt(5.W))
}


class TensorPE(val expWidth: Int, val precision: Int) extends Module {

    val mulDelay = 2
    val addDelay = 0

    val io = IO(new TensorPEIO(expWidth, precision))
    val fmul = Module(new FMUL(expWidth, precision))
    val fadd = Module(new FCMA_ADD(expWidth, 2 * precision, precision))

    fmul.io.a := io.a    //src1
    fmul.io.b := io.b    //src2
    fmul.io.rm := io.rm
    fadd.io.rm := io.rm

    fadd.io.b_inter_valid := true.B
    fadd.io.b_inter_flags := fmul.io.to_fadd.inter_flags

    val psumWriteEn = ShiftRegister(io.valid.asBool, mulDelay + addDelay, 0.U, true.B)

    //val localReset = io.valid.asBool && !(ShiftRegister(io.valid.asBool, 1, 0.U, true.B))
    //withReset(localReset){
    val psum = RegInit(0.asUInt((expWidth+precision).W))
    val busy = RegInit(0.asUInt(1.W))

    when(psumWriteEn === 1.U){
        psum := fadd.io.result
    }

    when (psumWriteEn === 1.U && (ShiftRegister(psumWriteEn, 1, 0.U, true.B)) === 0.U){
        busy := 1.U
    }

    io.ready := !psumWriteEn && busy.asBool

    fadd.io.a := Cat(psum, 0.U(precision.W))
    fadd.io.b := fmul.io.to_fadd.fp_prod.asUInt

    io.result := psum

    //}

}