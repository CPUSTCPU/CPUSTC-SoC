//This is an output-stationary tensor core implementation
package tensorcore

import chisel3._
import chisel3.util._

class TensorCoreIO(val expWidth: Int, val precision: Int, val M: Int, val N: Int) extends Bundle{
    val a = Input(Vec(M, UInt((expWidth + precision).W)))               //input a
    val b = Input(Vec(N, UInt((expWidth + precision).W)))               //input b
    val valid = Input(UInt(1.W))
    val rm = Input(UInt(3.W))

    val result = Output(Vec(M, Vec(N, UInt((expWidth + precision).W))))
    val ready = Output(Vec(M, Vec(N, UInt(1.W))))

    //val fflags = Output(UInt(5.W))
}

class TensorCore(val expWidth: Int, val precision: Int, val M: Int, val N: Int) extends Module {
    val io = IO(new TensorCoreIO(expWidth, precision, M, N))

    val tensorCore = Seq.tabulate(M, N){ (i, j) =>
        Module(new TensorPE(expWidth, precision))
    }

    tensorCore(0)(0).io.valid := io.valid
    tensorCore(0)(0).io.rm := io.rm
    tensorCore(0)(0).io.a := io.a(0)
    tensorCore(0)(0).io.b := io.b(0)
    for(i <- 1 until M){
        tensorCore(i)(0).io.a := ShiftRegister(io.a(i), i, 0.U, true.B)
    }
    for(j <- 1 until N){
        tensorCore(0)(j).io.b := ShiftRegister(io.b(j), j, 0.U, true.B)
    }

    for(i <- 0 until M-1){
        tensorCore(i+1)(0).io.valid := ShiftRegister(tensorCore(i)(0).io.valid, 1, 0.U, true.B)
        tensorCore(i+1)(0).io.rm := ShiftRegister(tensorCore(i)(0).io.rm, 1, 0.U, true.B)
        for(j <- 0 until N){
            tensorCore(i+1)(j).io.b := ShiftRegister(tensorCore(i)(j).io.b, 1, 0.U, true.B)
        }
    }

    for(i <- 0 until M){
        for(j <- 0 until N-1){
            tensorCore(i)(j+1).io.a := ShiftRegister(tensorCore(i)(j).io.a, 1, 0.U, true.B)
            tensorCore(i)(j+1).io.valid := ShiftRegister(tensorCore(i)(j).io.valid, 1, 0.U, true.B)
            tensorCore(i)(j+1).io.rm := ShiftRegister(tensorCore(i)(j).io.rm, 1, 0.U, true.B)
        }
    }

    for(i <- 0 until M){
        for(j <- 0 until N){
            io.result(i)(j) := tensorCore(i)(j).io.result
            io.ready(i)(j) := tensorCore(i)(j).io.ready
        }
    }
}