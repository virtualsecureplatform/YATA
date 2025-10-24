import chisel3._
import chisel3.util._

import math.log
import math.ceil

class RotatedTestVector(implicit val conf:Config) extends Module{
	val io = IO(new Bundle{
		val exponent = Input(UInt((conf.Nbit+1).W))
		val out = Output(Vec(conf.numcycle,Vec(conf.nttsize,UInt(conf.Qbit.W))))
	})

	for(i <- 0 until conf.numcycle){
		for(j <- 0 until conf.nttsize){
			io.out(i)(j) := Mux(io.exponent(conf.Nbit) ^ (((j<<conf.cyclebit)+i).U<io.exponent(conf.Nbit-1,0)),((1L<<conf.Qbit)-conf.mu).U,conf.mu.U)
		}
	}
}

class BlindRotateMemory(implicit val conf:Config) extends Module{
	val io = IO(new Bundle {
		val wreq = Input(Bool())
		val rreq = Input(Bool())
		val explower = Input(UInt(conf.cyclebit.W))
		val in = Input(UInt((conf.nttsize*conf.Qbit).W))
		val out = Output(Vec(2,UInt((conf.nttsize*conf.Qbit).W)))

		val flush = Input(Bool())
	})

	val mexplreg = RegNext(-io.explower)
	if(conf.useDualPort){
		val raddreg = RegInit(0.U((conf.cyclebit+log2Ceil(conf.k+1)).W))
		val waddreg = RegInit(0.U((conf.cyclebit+log2Ceil(conf.k+1)).W))

		val BRmem = Module(new RWSRmem((conf.k+1)*conf.numcycle,conf.nttsize*conf.Qbit))
		val wordlen = conf.nttsize*conf.Qbit

		BRmem.io.in := io.in
		io.out(0) := BRmem.io.rout
		io.out(1) := BRmem.io.out
		BRmem.io.addr := Mux(io.wreq,waddreg,(raddreg + mexplreg)(conf.cyclebit-1,0) | (raddreg(conf.cyclebit+log2Ceil(conf.k+1)-1,conf.cyclebit)<<conf.cyclebit))
		BRmem.io.raddr := raddreg
		BRmem.io.wen := io.wreq

		when(io.rreq){
			raddreg := raddreg + 1.U
			when(raddreg === ((conf.k+1)*conf.numcycle-1).U){
				raddreg := 0.U
			}
		}
		when(io.wreq){
			waddreg := waddreg + 1.U
			when(waddreg === ((conf.k+1)*conf.numcycle-1).U){
				waddreg := 0.U
			}
		}
		when(io.flush){
			raddreg := 0.U
			waddreg := 0.U
		}
	}else{
		val nummem = (conf.k+1)*2+1
		val BRmems = for( i <- 0 until nummem) yield{
			val BRmem = Module(new RWSmem(conf.numcycle,conf.nttsize*conf.Qbit))
			BRmem
		}
		val rselreg = RegInit(0.U(log2Ceil(nummem).W))
		val raddreg = RegInit(0.U(conf.cyclebit.W))
		val wselreg = RegInit(0.U(log2Ceil(nummem).W))
		val waddreg = RegInit(0.U(conf.cyclebit.W))
		val cntreg = RegInit(0.U)

		for( i <- 0 until 2){
			io.out(i) := DontCare
		}
		for( i <- 0 until nummem){
			BRmems(i).io.in := io.in
			BRmems(i).io.wen := false.B
			BRmems(i).io.addr := DontCare	
			when(RegNext(rselreg)===i.U){
				io.out(0) := BRmems(i).io.out
			}
			when(rselreg===i.U){
				BRmems(i).io.addr := raddreg
			}
			when(RegNext((rselreg+2.U)%nummem.U)===i.U){
				io.out(1) := BRmems(i).io.out
			}
			when(((rselreg+2.U)%nummem.U)===i.U){
				BRmems(i).io.addr := raddreg + mexplreg
			}
		}
		when(io.rreq){
			raddreg := raddreg + 1.U
			when(raddreg === (conf.numcycle-1).U){
				raddreg := 0.U
				rselreg := (rselreg + 1.U) % nummem.U
			}
		}
		when(io.wreq){
			for( i <- 0 until nummem){
				when((wselreg === i.U) | (((wselreg + 2.U)%nummem.U)===i.U)){
					BRmems(i).io.wen := io.wreq
					BRmems(i).io.addr := waddreg
				}
			}
			waddreg := waddreg + 1.U
			when(waddreg === (conf.numcycle-1).U){
				waddreg := 0.U
				wselreg := (wselreg + 1.U) % nummem.U
				cntreg := cntreg + 1.U
				when(cntreg === 1.U){
					cntreg := 0.U
					wselreg := (wselreg + 3.U) % nummem.U
				}
			}
		}
		when(io.flush){
			cntreg := 0.U
			rselreg := 0.U
			raddreg := 0.U
			wselreg := 0.U
			waddreg := 0.U
		}
	}
}

object BlindRotateState extends ChiselEnum {
  val WAIT,INIT,WAITCONTINUE,PMBXMOWAIT,RUN,OUT = Value
}

class BlindRotate(implicit val conf:Config) extends Module{
	val io = IO(new Bundle{
		val b = Input(UInt(conf.qbit.W))
		val a = Input(UInt(conf.qbit.W))
		val dimready = Output(Bool())
		val axi4sglobalout = new AXI4StreamManager(conf.spibuswidth)
		val axi4sin = Vec(conf.trlwenumbus,new AXI4StreamSubordinate(conf.buswidth))
		val axi4sout = Vec(conf.trlwenumbus,new AXI4StreamManager(conf.buswidth))
		val enable = Input(Bool())
		val run = Input(Bool())
		val incn = Output(Bool())
		val continue = Input(Bool())
		val maxpower = Input(Bool())
		val fin = Output(Bool())
		val maxpowerflush = Output(Bool())

		val debugout = Output(UInt((conf.nttsize*conf.Qbit).W))
		val debugvalid = Output(Bool())
		val debugbrcnt = Output(UInt(log2Ceil(conf.n).W))
	})

	io.incn := false.B
	io.fin := false.B
	io.maxpowerflush := false.B
	val dimreadywire = Wire(Bool())
	io.dimready := RegNext(dimreadywire)
	dimreadywire := false.B

	val BRmem = Module(new BlindRotateMemory)
	BRmem.io.flush := ~io.enable

	val pmbxmo = Module(new PolynomialMulByXaiMinusOne())
	pmbxmo.io.in := BRmem.io.out(1)
	pmbxmo.io.minusin := BRmem.io.out(0)
	BRmem.io.rreq := pmbxmo.io.rreq
	val pmbxmoenablewire = Wire(Bool())
	pmbxmo.io.enable := RegNext(pmbxmoenablewire)
	pmbxmoenablewire := false.B

	val initcnt = RegInit(0.U(log2Ceil(conf.numcycle*(conf.k+1)).W))

	val roundoffset = 1L<<(conf.Qbit-conf.Nbit-2)
	pmbxmo.io.exponent := (io.a + roundoffset.U)(conf.Qbit-1,conf.Qbit-(conf.Nbit+1))
	BRmem.io.explower := pmbxmo.io.exponent(conf.cyclebit-1,0)
	
	//Write Result
	when(ShiftRegister(io.axi4sin(0).TVALID,conf.interslr/2)){
		BRmem.io.rreq := true.B
		initcnt := initcnt + 1.U
	}
	BRmem.io.wreq := ShiftRegister(io.axi4sin(0).TVALID,conf.interslr/2+2)
	val tdatavec = Wire(Vec(conf.trlwenumbus,UInt(conf.buswidth.W)))
	for(i <- 0 until conf.trlwenumbus){
		io.axi4sin(i).TREADY := true.B
		tdatavec(i) :=  ShiftRegister(io.axi4sin(i).TDATA,conf.interslr/2)
	}
	val finreg = RegInit(0.U(2.W))
    when(RegNext(~ShiftRegister(io.axi4sin(0).TVALID,conf.interslr/2)&&ShiftRegister(io.axi4sin(0).TVALID,conf.interslr/2+1))){
        finreg := finreg +1.U
    }
	val addedres = Wire(Vec(conf.nttsize,UInt(conf.Qbit.W)))
	for(j <- 0 until conf.nttsize){
		addedres(j) := ShiftRegister(Cat(tdatavec.reverse)((j+1)*conf.Qbit-1,j*conf.Qbit),2) + RegNext(BRmem.io.out(0)((j+1)*conf.Qbit-1,j*conf.Qbit))
	}
	BRmem.io.in := Cat(addedres.reverse)
	io.debugout := BRmem.io.in
	io.debugvalid := BRmem.io.wreq

	for(i <- 0 until conf.trlwenumbus){
		io.axi4sout(i).TVALID := pmbxmo.io.valid
		io.axi4sout(i).TDATA := Cat(pmbxmo.io.out.reverse)((i+1)*conf.buswidth-1,i*conf.buswidth)
	}

	val brcntreg = RegInit(0.U(log2Ceil(conf.n).W))
	io.debugbrcnt := brcntreg

	val tvgen = Module(new RotatedTestVector)
	tvgen.io.exponent := (2*conf.N).U - io.b(conf.qbit-1,conf.qbit-(conf.Nbit+1))

	val outsizer = Module(new DownSizer(conf.trlwenumbus*conf.buswidth,conf.spibuswidth))
	outsizer.io.axi4sin.TDATA := BRmem.io.out(0)
	outsizer.io.axi4sin.TVALID := false.B
	outsizer.io.flush := ~io.enable
	io.axi4sglobalout.TVALID := false.B
	io.axi4sglobalout <> outsizer.io.axi4sout

	val statereg = RegInit(BlindRotateState.WAIT)
	switch(statereg){
		is(BlindRotateState.WAIT){
			when(io.enable&io.run){
				statereg := BlindRotateState.INIT
			}
		}
		is(BlindRotateState.INIT){
			BRmem.io.wreq := true.B
			BRmem.io.in := Mux((initcnt>>conf.cyclebit)===conf.k.U,Cat(tvgen.io.out(initcnt).reverse),0.U)
			io.debugout := BRmem.io.in
			when(initcnt =/= ((conf.k+1)*conf.numcycle-1).U){
				initcnt := initcnt + 1.U
			}.otherwise{
				initcnt := 0.U
				statereg := BlindRotateState.WAITCONTINUE
			}
		}
		is(BlindRotateState.WAITCONTINUE){
			when(io.continue | io.maxpower){
				io.incn := RegNext(~io.maxpower)
				pmbxmoenablewire := true.B
				statereg := BlindRotateState.PMBXMOWAIT
			}
		}
		is(BlindRotateState.PMBXMOWAIT){
			pmbxmoenablewire := true.B
			when(~pmbxmo.io.valid && RegNext(pmbxmo.io.valid)){
				statereg := BlindRotateState.RUN
				dimreadywire := RegNext(brcntreg =/= (conf.n-1).U)
			}
			io.debugout := Cat(pmbxmo.io.out.reverse)
			io.debugvalid := pmbxmo.io.valid
		}
		is(BlindRotateState.RUN){
			when(finreg===1.U){
				finreg := 0.U
				when(RegNext(brcntreg =/= (conf.n-1).U)){
					brcntreg := brcntreg + 1.U
					when(io.continue){
						pmbxmoenablewire := true.B
						io.incn := RegNext(~io.maxpower)
						statereg := BlindRotateState.PMBXMOWAIT
					}.otherwise{
						statereg := BlindRotateState.WAITCONTINUE
					}
				}.otherwise{
					when(io.maxpower){
						io.fin := true.B
						io.maxpowerflush := true.B
					}.otherwise{
						BRmem.io.rreq := true.B
						outsizer.io.axi4sin.TVALID := true.B
					}
					statereg := BlindRotateState.OUT
				}
			}
		}
		is(BlindRotateState.OUT){
			io.fin := true.B
			when(~io.maxpower){
				outsizer.io.axi4sin.TVALID := true.B
				BRmem.io.rreq := outsizer.io.axi4sin.TREADY
			}
		}
	}
	when(!io.enable){
		initcnt := 0.U
		finreg := 0.U
		brcntreg := 0.U
		statereg := BlindRotateState.WAIT
	}
}

class AXISBRFormer(implicit val conf:Config) extends Module{
	val io = IO(new Bundle{
		val axi4sglobalin = new AXI4StreamSubordinate(conf.spibuswidth)
		val axi4sglobalout = new AXI4StreamManager(conf.buswidth)
		val axi4sin = Vec(conf.trlwenumbus,new AXI4StreamSubordinate(conf.buswidth))
		val axi4sout = Vec(conf.nttnumbus,new AXI4StreamManager(conf.buswidth))

		val run = Input(Bool())
		val flush = Input(Bool())
		val fin = Output(Bool())
		val continue = Input(Bool())
		val incn = Output(Bool())
		val maxpower = Input(Bool())
		val maxpowerflush = Output(Bool())

		val debugout = Output(UInt((conf.nttsize*conf.Qbit).W))
		val debugvalid = Output(Bool())
		val debugbrcnt = Output(UInt(log2Ceil(conf.n).W))
	})

	val tlwe2index = Module(new TLWE2Index(conf.spibuswidth,conf.n,conf.qbit))
	val inslice = Module(new AXI4StreamRegisterSlice(conf.spibuswidth,conf.axi4snumslice))
	val outslice = Module(new AXI4StreamRegisterSlice(conf.spibuswidth,conf.axi4snumslice))
	val br = Module(new BlindRotate)
	val extpformer = Module(new ExternalProductFormer)
	val extppremiddle = Module(new ExternalProductPreMiddle)

	io.axi4sglobalin <> inslice.io.subordinate

	io.debugvalid := br.io.debugvalid
	io.debugout := br.io.debugout
	io.debugbrcnt := br.io.debugbrcnt

	io.axi4sglobalout <> outslice.io.manager

	br.io.run := io.run
	io.fin := br.io.fin
	io.incn := br.io.incn
	br.io.continue := io.continue
	br.io.maxpower := io.maxpower
	io.maxpowerflush := br.io.maxpowerflush
	br.io.axi4sout <> extpformer.io.axi4sin
	br.io.axi4sin <> io.axi4sin
	br.io.enable := RegNext(tlwe2index.io.validout)
	br.io.b := tlwe2index.io.b
	br.io.a := tlwe2index.io.a
	
	br.io.axi4sglobalout <> outslice.io.subordinate

	tlwe2index.io.axi4 <> inslice.io.manager
	tlwe2index.io.ready := br.io.dimready
	tlwe2index.io.enable := ~(io.flush | ShiftRegister(br.io.maxpowerflush,conf.axi4snumslice))
	tlwe2index.io.maxpower := io.maxpower

	extpformer.io.axi4sout <> extppremiddle.io.axi4sin

	extppremiddle.io.axi4sout <> io.axi4sout
}

class AXISBRMiddle(implicit val conf:Config) extends Module{
	val io = IO(new Bundle{
		val axi4bkin = Vec(conf.bknumbus,new AXI4StreamSubordinate(conf.buswidth))

		val axi4sin = Vec(conf.nttnumbus,new AXI4StreamSubordinate(conf.buswidth))
		val axi4sout = Vec(conf.nttnumbus,new AXI4StreamManager(conf.buswidth))

		val flush = Input(Bool())
	})

	val extpmiddle = Module(new ExternalProductMiddle)

	extpmiddle.io.axi4sin <> io.axi4sin
	extpmiddle.io.axi4sout <> io.axi4sout
	for(k <- 0 until conf.k+1){
		val tvalidvec = Wire(Vec(conf.bknumbus/(conf.k+1),Bool()))
		val tdatavec = Wire(Vec(conf.bknumbus/(conf.k+1),UInt(conf.buswidth.W)))
		for(i <- 0 until conf.bknumbus/(conf.k+1)){
			val slice = Module(new AXI4StreamRegisterSlice(conf.buswidth,conf.axi4snumslice))
			slice.io.subordinate <> io.axi4bkin(k*conf.bknumbus/(conf.k+1)+i)
			slice.io.manager.TREADY := extpmiddle.io.trgswinready(k) | ShiftRegister(io.flush,2 * conf.axi4snumslice)
			tvalidvec(i) := slice.io.manager.TVALID
			tdatavec(i) := slice.io.manager.TDATA
		}
		extpmiddle.io.trgswinvalid(k) := Cat(tvalidvec).andR
		extpmiddle.io.trgswin(k) := Cat(tdatavec.reverse)
	}
}

class AXISBRLater(implicit val conf:Config) extends Module{
	val io = IO(new Bundle{
		val axi4sin = Vec(conf.nttnumbus,new AXI4StreamSubordinate(conf.buswidth))
		val axi4sout = Vec(conf.trlwenumbus,new AXI4StreamManager(conf.buswidth))
	})

	val extplater = Module(new ExternalProductLater)
	io.axi4sin <> extplater.io.axi4sin
	extplater.io.axi4sout <> io.axi4sout
}

class BufferInitializer(numbus:Int)(implicit val conf:Config) extends Module{
	val io = IO(new Bundle{
		val axi4sin = new AXI4StreamSubordinate(conf.spibuswidth)
		val axi4sout = Vec(numbus,(new AXI4StreamManager((conf.buswidth/32)*(conf.wordbits-1))))
		val flush = Input(Bool())
	})
	val numreg = (conf.buswidth/32)*(conf.wordbits-1)/conf.spibuswidth
    val shiftreg = Reg(Vec(numreg,UInt(conf.spibuswidth.W)))
    for(i <- 0 until numbus){
		io.axi4sout(i).TDATA := Cat(shiftreg.reverse)
		io.axi4sout(i).TVALID := false.B
    }
    val cntreg = RegInit(0.U(log2Ceil(numreg).W))
    val busreg = RegInit(0.U(log2Ceil(numbus).W))
	io.axi4sin.TREADY := true.B
    io.axi4sout(RegNext(busreg)).TVALID := RegNext(cntreg === (numreg-1).U) & (cntreg === 0.U)
    when(io.axi4sin.TVALID){
        shiftreg(numreg-1) := io.axi4sin.TDATA
        for(i <- 0 until numreg-1){
            shiftreg(i) := shiftreg(i+1)
        }
        cntreg := cntreg + 1.U
        when(cntreg === (numreg-1).U){
            cntreg := 0.U
			busreg := busreg + 1.U
			when(busreg === (numbus-1).U){
				busreg := 0.U
			}
        }
    }
	when(io.flush){
		cntreg := 0.U
		busreg := 0.U
	}
}

class AXISBRWrapper(implicit val conf:Config) extends Module{
	val io = IO(new Bundle{
		val controlSPI = new SPIport()
		val compress = Input(UInt((conf.k+1).W))
		val maxpower = Input(Bool())
		val fin = Output(Bool())

		val debugout = Output(UInt((conf.nttsize*conf.Qbit).W))
		val debugvalid = Output(Bool())
		val debugbrcnt = Output(UInt(log2Ceil(conf.n).W))
	})

	val spicontroller = Module(new SPIControlModule)
	val axisbrformer = Module(new AXISBRFormer)
	val axisbrmiddle = Module(new AXISBRMiddle)
	val axisbrlater = Module(new AXISBRLater)

	val keycompression = Module(new KeyCompression)
	val seedinitializer = Module(new SeedInitializer)
	keycompression.io.flush := ShiftRegister(spicontroller.io.flush,2*conf.axi4snumslice)
	keycompression.io.seed <> seedinitializer.io.seed
	seedinitializer.io.axi4s <> spicontroller.io.axi4sprngseed

	val nttSLR0toSLR2slices = for(i <- 0 until conf.nttnumbus) yield{
        val  nttSLR0toSLR2slice = Module(new NTTdataPipeline)
         nttSLR0toSLR2slice
    }

	axisbrformer.io.maxpower := ShiftRegister(io.maxpower,2*conf.axi4snumslice)
	axisbrformer.io.run := ShiftRegister(spicontroller.io.run,2*conf.axi4snumslice)
	axisbrformer.io.flush := ShiftRegister(spicontroller.io.flush,2*conf.axi4snumslice)
	axisbrmiddle.io.flush := ShiftRegister(spicontroller.io.flush,2*conf.axi4snumslice) | ShiftRegister(axisbrformer.io.maxpowerflush, 2*conf.axi4snumslice)
	spicontroller.io.fin := ShiftRegister(axisbrformer.io.fin,2*conf.axi4snumslice)
	spicontroller.io.incn := ShiftRegister(axisbrformer.io.incn,2*conf.axi4snumslice)
	spicontroller.io.maxpower := ShiftRegister(io.maxpower,2*conf.axi4snumslice)
	axisbrformer.io.continue := ShiftRegister(spicontroller.io.continue,2*conf.axi4snumslice)
	io.fin := spicontroller.io.fin
	spicontroller.io.SPI <> io.controlSPI

	axisbrformer.io.axi4sglobalout <> spicontroller.io.axi4sout
	when(ShiftRegister(spicontroller.io.flush,2*conf.axi4snumslice)){
		axisbrformer.io.axi4sglobalout.TREADY := true.B
	}
	
	spicontroller.io.axi4sinfifo <> axisbrformer.io.axi4sglobalin

	axisbrformer.io.axi4sout <> axisbrmiddle.io.axi4sin
	for(i <- 0 until conf.k+1){
		val bkinitializer = Module(new BufferInitializer(conf.nttnumbus))
		bkinitializer.io.axi4sin <> spicontroller.io.axi4sbkfifo(i)
		bkinitializer.io.flush := ShiftRegister(spicontroller.io.flush, 2*conf.axi4snumslice) | ShiftRegister(axisbrformer.io.maxpowerflush, 2*conf.axi4snumslice)
		for(j <- 0 until conf.nttnumbus){
			keycompression.io.axi4sout(i*conf.nttnumbus+j).TREADY := false.B
			val buffer = Module(new Buffer((conf.buswidth/32)*(conf.wordbits-1),conf.bkbuffdepth,conf.useQueue4Buffer,true))
			buffer.io.axi4sin.TVALID := keycompression.io.axi4sout(i*conf.nttnumbus+j).TVALID
			keycompression.io.axi4sout(i*conf.nttnumbus+j).TREADY := buffer.io.axi4sin.TREADY
			val bufinwire = Wire(Vec(conf.buswidth/32,UInt((conf.wordbits-1).W)))
			for(k <- 0 until conf.buswidth/32){
				bufinwire(k) := keycompression.io.axi4sout(i*conf.nttnumbus+j).TDATA((k+1)*32-1,k*32)
			}
			buffer.io.axi4sin.TDATA := Cat(bufinwire.reverse)

			buffer.io.flush.get := ShiftRegister(spicontroller.io.flush, 2*conf.axi4snumslice) | ShiftRegister(axisbrformer.io.maxpowerflush, 2*conf.axi4snumslice)

			val bufoutwire = Wire(Vec(conf.buswidth/32,UInt(32.W)))
			for(k <- 0 until conf.buswidth/32){
				bufoutwire(k) := buffer.io.axi4sout.TDATA((k+1)*(conf.wordbits-1)-1,k*(conf.wordbits-1))
			}
			axisbrmiddle.io.axi4bkin(i*conf.nttnumbus+j).TDATA := Cat(bufoutwire.reverse)
			axisbrmiddle.io.axi4bkin(i*conf.nttnumbus+j).TVALID := ShiftRegister(spicontroller.io.run,2*conf.axi4snumslice) & buffer.io.axi4sout.TVALID
			buffer.io.axi4sout.TREADY :=  ShiftRegister(spicontroller.io.run,2*conf.axi4snumslice) & axisbrmiddle.io.axi4bkin(i*conf.nttnumbus+j).TREADY
			bkinitializer.io.axi4sout(j).TREADY := false.B
			when(ShiftRegister(io.compress(i)===1.U, conf.axi4snumslice)){
				buffer.io.axi4sin <> bkinitializer.io.axi4sout(j)
			}
		}
	}
	for(i <- 0 until conf.nttnumbus){
		nttSLR0toSLR2slices(i).io.subordinate <> axisbrmiddle.io.axi4sout(i)
		axisbrlater.io.axi4sin(i) <> nttSLR0toSLR2slices(i).io.manager
	}
	axisbrlater.io.axi4sout <> axisbrformer.io.axi4sin

	io.debugout := axisbrformer.io.debugout
	io.debugvalid := axisbrformer.io.debugvalid
	io.debugbrcnt := axisbrformer.io.debugbrcnt
}
object AXISBRWrapperTop extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new AXISBRWrapper()(Config()), Array("--emission-options=disableMemRandomization,disableRegisterRandomization"))
}