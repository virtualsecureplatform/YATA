import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

import math.ceil
import math.log

class UpSizer(val outputwidth: Int, val buswidth: Int) extends Module{
	val io = IO(new Bundle{
		val axi4 = new AXI4StreamSubordinate(buswidth)
		val out = Output(UInt(outputwidth.W))
		val ready = Output(Bool())
		val req = Input(Bool())
	})

	val numreg = ceil(outputwidth.toFloat/buswidth).toInt
	val shiftreg = Reg(Vec(numreg,UInt(buswidth.W)))
	val cntreg = RegInit(0.U(log2Ceil(numreg).W))

	io.out := Cat(shiftreg.reverse)
	io.axi4.TREADY := true.B
	val readyreg = RegInit(false.B)
	io.ready := readyreg
	
	when(readyreg & io.req){
		readyreg := false.B
	}

	when((~readyreg)&io.axi4.TVALID){
		shiftreg(numreg-1) := io.axi4.TDATA
		for(i<-0 until numreg-1){
			shiftreg(i) := shiftreg(i+1)
		}
		when(cntreg=/=(numreg-1).U){
			cntreg := cntreg + 1.U
			readyreg := false.B
		}.otherwise{
			readyreg  := true.B
		}
	}
}

class MultiIFUpSizer(val outputwidth: Int, val buswidth: Int, val numbus: Int) extends Module{
	val io = IO(new Bundle{
		val axi4 = Vec(numbus,new AXI4StreamSubordinate(buswidth))
		val out = Output(UInt(outputwidth.W))
		val ready = Output(Bool())
		val req = Input(Bool())
	})

	val numreg = ceil(outputwidth.toFloat/(buswidth*numbus)).toInt
	val shiftreg = Reg(Vec(numreg,UInt((numbus*buswidth).W)))
	val cntreg = RegInit(0.U(log2Ceil(numreg).W))

	io.out := Cat(shiftreg.reverse)
	val tvalidvec = Wire(Vec(numbus,Bool()))
	val tdatavec = Wire(Vec(numbus,UInt(buswidth.W)))
	val readyreg = RegInit(false.B)
	io.ready := readyreg

	when(readyreg & io.req){
		readyreg := false.B
	}

	for(i <- 0 until numbus){
		io.axi4(i).TREADY := ~readyreg | io.req
		tvalidvec(i) := io.axi4(i).TVALID
		tdatavec(i) := io.axi4(i).TDATA
	}

	when((~readyreg | io.req)&Cat(tvalidvec).andR){
		shiftreg(numreg-1) := Cat(tdatavec.reverse)
		for(i<-0 until numreg-1){
			shiftreg(i) := shiftreg(i+1)
		}
		when(cntreg=/=(numreg-1).U){
			cntreg := cntreg + 1.U
			readyreg := false.B
		}.otherwise{
			cntreg := 0.U
			readyreg  := true.B
		}
	}
}

class TLWE2Index(val buswidth: Int, val n: Int, qbit: Int) extends Module{
	val io = IO(new Bundle{
		val axi4 = new AXI4StreamSubordinate(buswidth)
		val a = Output(UInt(qbit.W))
		val b = Output(UInt(qbit.W))
		val ready = Input(Bool())
		val validout = Output(Bool())

		val enable = Input(Bool())
		val maxpower = Input(Bool())
	})
	val numreg = qbit/buswidth
	val queuedepth = qbit * n
	val posb = n % numreg

	val validreg = RegInit(false.B)
	val breg = Reg(UInt(qbit.W))
	val shiftreg = Reg(Vec(numreg,UInt(buswidth.W)))
	val cntreg = Reg(UInt(log2Ceil(numreg).W))
	val aqueue = Module(new Buffer(qbit, n, false,true))
	when(io.maxpower){
		val prng = Module(new xoshiro128())
		prng.io.seed := 1.U
		prng.io.seedwrite := io.maxpower & RegNext(io.maxpower)
		prng.io.axi4sout.TREADY := ~validreg
		aqueue.io.axi4sin.TDATA := prng.io.axi4sout.TDATA
		aqueue.io.axi4sin.TVALID := ~validreg
	}.otherwise{
		aqueue.io.axi4sin.TDATA := Cat(shiftreg.reverse)
		aqueue.io.axi4sin.TVALID := RegNext(cntreg === (numreg-1).U) & (cntreg === 0.U)
	}
	io.axi4.TREADY := true.B
	aqueue.io.flush.get := false.B

	io.validout := validreg
	aqueue.io.axi4sout.TREADY := validreg & io.ready
	when(~aqueue.io.axi4sin.TREADY & aqueue.io.axi4sin.TVALID){
		validreg := true.B
		breg := Cat(shiftreg.reverse)
	}
	
	io.b := breg
	io.a := aqueue.io.axi4sout.TDATA
	when(io.axi4.TVALID){
		cntreg := cntreg + 1.U
		shiftreg(numreg-1) := io.axi4.TDATA
		for(i<-0 until numreg-1){
			shiftreg(i) := shiftreg(i+1)
		}
		when(cntreg === (numreg-1).U){
			cntreg := 0.U
		}
	}

	when(~io.enable){
		validreg := false.B
		aqueue.io.flush.get := true.B
		cntreg := 0.U
	}
}

class DownSizer(val inbuswidth:Int, val outbuswidth: Int) extends Module{
	val io = IO(new Bundle{
		val axi4sin = new AXI4StreamSubordinate(inbuswidth)
		val axi4sout = new AXI4StreamManager(outbuswidth)

		val flush = Input(Bool())
	})

	val numreg = ceil(inbuswidth.toFloat/outbuswidth).toInt
	val shiftreg = Reg(Vec(numreg,UInt(outbuswidth.W)))
	val cntreg = RegInit(0.U(log2Ceil(numreg).W))
	val validreg = RegInit(false.B)

	io.axi4sin.TREADY := ~validreg
	io.axi4sout.TDATA := shiftreg(0)
	io.axi4sout.TVALID := validreg

	when(validreg){
		when(io.axi4sout.TREADY){
			shiftreg(numreg-1) := DontCare
			for(i<-0 until numreg-1){
				shiftreg(i) := shiftreg(i+1)
			}
			when(cntreg=/=(numreg-1).U){
				cntreg := cntreg + 1.U
			}.otherwise{
				cntreg := 0.U
				when(io.axi4sin.TVALID){
					shiftreg(numreg-1) := io.axi4sin.TDATA(inbuswidth-1,outbuswidth*(numreg-1))
					for(i<-0 until numreg-1){
						shiftreg(i) := io.axi4sin.TDATA(outbuswidth*(i+1)-1,outbuswidth*i)
					}
					io.axi4sin.TREADY := true.B
				}.otherwise{
					validreg := false.B
				}
			}
		}
	}.elsewhen(io.axi4sin.TVALID){
		shiftreg(numreg-1) := io.axi4sin.TDATA(inbuswidth-1,outbuswidth*(numreg-1))
		for(i<-0 until numreg-1){
			shiftreg(i) := io.axi4sin.TDATA(outbuswidth*(i+1)-1,outbuswidth*i)
		}
		validreg := true.B
	}
	when(io.flush){
		validreg := false.B
		cntreg := 0.U
	}
}