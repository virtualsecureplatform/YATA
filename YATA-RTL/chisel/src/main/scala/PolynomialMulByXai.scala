import chisel3._
import chisel3.util._

import math.log
import math.ceil

object PolynomialMulByXaiState extends ChiselEnum {
  val WAIT, RUN, FIN = Value
}

class PolynomialMulByXai(implicit val conf:Config) extends Module{
	val io = IO(new Bundle{
		val in = Input(UInt((conf.nttsize*conf.Qbit).W))
		val rreq = Output(Bool())
		
		val valid = Output(Bool())
		val out = Output(Vec(conf.nttsize,UInt(conf.Qbit.W)))

		val exponent = Input(UInt((conf.Nbit+1).W))

		val enable = Input(Bool())
	})
	io.valid := false.B
	
	val expabs = RegNext(io.exponent(conf.Nbit-1,0))
	val indexcnt = RegInit(0.U(conf.cyclebit.W))
	val explower = expabs(conf.cyclebit-1,0)
	val expupper = expabs(conf.Nbit-1,conf.cyclebit)
	val selreg = RegInit(0.U(log2Ceil(conf.k+1).W))

	val rotvalidwire = Wire(Bool())
	rotvalidwire := false.B
	io.rreq := false.B
	io.valid := ShiftRegister(rotvalidwire,2)
	val shiftamount = conf.Qbit.U*(expupper + Mux(RegNext(indexcnt)<explower,1.U,0.U))
	val rotated = RegNext(io.in.rotateLeft(shiftamount))
	val invrotated = Wire(Vec(conf.nttsize,UInt(conf.Qbit.W)))
	for(i <- 0 until conf.nttsize){
		invrotated(i) := Mux(RegNext(ShiftRegister(io.exponent(conf.Nbit),1)^(((i*conf.numcycle).U+RegNext(indexcnt))<RegNext(expabs))),-rotated((i+1)*conf.Qbit-1,i*conf.Qbit),rotated((i+1)*conf.Qbit-1,i*conf.Qbit))
	}
	io.out := invrotated
	
	val statereg = RegInit(PolynomialMulByXaiState.WAIT)
	switch(statereg){
		is(PolynomialMulByXaiState.WAIT){
			when(io.enable){
				rotvalidwire := true.B
				io.rreq := true.B
				statereg := PolynomialMulByXaiState.RUN
				indexcnt := indexcnt + 1.U
			}
		}
		is(PolynomialMulByXaiState.RUN){
			rotvalidwire := true.B
			io.rreq := true.B
			when(indexcnt =/= (conf.numcycle-1).U){
				indexcnt := indexcnt + 1.U
			}.otherwise{
				indexcnt := 0.U
				when(selreg=/=conf.k.U){
					selreg := selreg + 1.U
					statereg := PolynomialMulByXaiState.WAIT
				}.otherwise{
					selreg := 0.U
					statereg := PolynomialMulByXaiState.FIN
				}
			}
		}
	}
	when(~io.enable){
		indexcnt := 0.U
		selreg := 0.U
		statereg := PolynomialMulByXaiState.WAIT
	}
}

class PolynomialMulByXaiMinusOne(implicit val conf:Config) extends Module{
	val io = IO(new Bundle{
		val in = Input(UInt((conf.nttsize*conf.Qbit).W))
		val minusin = Input(UInt((conf.nttsize*conf.Qbit).W))
		val rreq = Output(Bool())
		
		val out = Output(Vec(conf.nttsize,UInt(conf.Qbit.W)))

		val exponent = Input(UInt((conf.Nbit+1).W))

		val enable = Input(Bool())
		val valid = Output(Bool())
	})
	val mulbyxai = Module(new PolynomialMulByXai)
	io.valid := mulbyxai.io.valid
	mulbyxai.io.enable := false.B
	mulbyxai.io.in := io.in
	io.rreq := mulbyxai.io.rreq
	mulbyxai.io.exponent := io.exponent
	mulbyxai.io.enable := io.enable

	for(i <- 0 until conf.nttsize){
		io.out(i) := mulbyxai.io.out(i) - ShiftRegister(io.minusin((i+1)*conf.Qbit-1,i*conf.Qbit),1)
	}
}
