import chisel3._
import chisel3.util._

class SeedPort()(implicit val conf:Config) extends Bundle{
	val xoshiroseed = Input(Vec(conf.nttnumbus,Vec(conf.numprng,UInt(conf.xoshiroseedbit.W))))
    val xoshiroseedwrite = Input(Vec(conf.nttnumbus,(Vec(conf.numprng,Bool()))))
    val asconseed = Input(Vec(conf.k,Vec(conf.nttnumbus,Vec(conf.numprng,UInt(conf.asconseedbit.W)))))
    val asconseedwrite = Input(Vec(conf.k,Vec(conf.nttnumbus,(Vec(conf.numprng,Bool())))))
}

class KeyCompression()(implicit val conf:Config) extends Module{
    val io = IO(new Bundle{
        val seed = new SeedPort()

        val axi4sout = Vec(conf.bknumbus,new AXI4StreamManager(conf.buswidth))

        val flush = Input(Bool())
    })

    for(k <- 0 until conf.k){
        for(bus <- 0 until conf.nttnumbus){
            val outwire = Wire(Vec(conf.numprng,UInt(32.W)))
            val validwire = Wire(Vec(conf.numprng,Bool()))
            for(i <- 0 until conf.numprng) {
                val prngenablereg = RegInit(false.B)
                when(io.seed.asconseedwrite(k)(bus)(i)){
                    prngenablereg := true.B
                }
                val prng = Module(new ASCONPRNG(conf.asconN))
                val flushseedreg = Reg(UInt(conf.asconseedbit.W))
                prng.io.seed := io.seed.asconseed(k)(bus)(i)
                prng.io.seedwrite := io.seed.asconseedwrite(k)(bus)(i)
                when(io.seed.asconseedwrite(k)(bus)(i)){
                    flushseedreg := io.seed.asconseed(k)(bus)(i)
                }

                val buffer = Module(new AXI4StreamRegisterSlice(conf.wordbits, conf.axi4snumslice))
                buffer.io.subordinate.TVALID := prngenablereg & (prng.io.axi4sout.TDATA(log2Ceil(conf.P)-1,0)<conf.P.U) & prng.io.axi4sout.TVALID
                buffer.io.subordinate.TDATA := prng.io.axi4sout.TDATA(log2Ceil(conf.P)-1,0)
                prng.io.axi4sout.TREADY := buffer.io.subordinate.TREADY | (prng.io.axi4sout.TDATA(log2Ceil(conf.P)-1,0)>=conf.P.U)
                validwire(i) := buffer.io.manager.TVALID
                buffer.io.manager.TREADY := io.axi4sout(k*conf.nttnumbus+bus).TREADY & io.axi4sout(k*conf.nttnumbus+bus).TVALID | ShiftRegister(io.flush, conf.axi4snumslice)
                outwire(i) := buffer.io.manager.TDATA(log2Ceil(conf.P)-1,0)

                when(io.flush){
                    prng.io.seed := flushseedreg
                    prngenablereg := false.B
                    prng.io.seedwrite := true.B
                }.elsewhen(RegNext(io.flush)){
                    prng.io.seed := flushseedreg
                    prngenablereg := true.B
                    prng.io.seedwrite := true.B
                }
            }
            io.axi4sout(k*conf.nttnumbus+bus).TVALID := Cat(validwire).andR
            io.axi4sout(k*conf.nttnumbus+bus).TDATA := Cat(outwire.reverse)
        }
    }

    for(bus <- 0 until conf.nttnumbus){
        val outwire = Wire(Vec(conf.numprng,UInt(32.W)))
        val validwire = Wire(Vec(conf.numprng,Bool()))
        for(i <- 0 until conf.numprng) {
            val prngenablereg = RegInit(false.B)
            when(io.seed.xoshiroseedwrite(bus)(i)){
                prngenablereg := true.B
            }
            val prng = Module(new xoshiro128())
            val flushseedreg = Reg(UInt(128.W))
            prng.io.seed := io.seed.xoshiroseed(bus)(i)
            prng.io.seedwrite := io.seed.xoshiroseedwrite(bus)(i)
            when(io.seed.xoshiroseedwrite(bus)(i)){
                flushseedreg := io.seed.xoshiroseed(bus)(i)
            }

            val buffer = Module(new AXI4StreamRegisterSlice(conf.wordbits, conf.axi4snumslice))
            buffer.io.subordinate.TVALID := prngenablereg & (prng.io.axi4sout.TDATA(log2Ceil(conf.P)-1,0)<conf.P.U)
            buffer.io.subordinate.TDATA := prng.io.axi4sout.TDATA(log2Ceil(conf.P)-1,0)
            prng.io.axi4sout.TREADY := buffer.io.subordinate.TREADY
            validwire(i) := buffer.io.manager.TVALID
            buffer.io.manager.TREADY := io.axi4sout(conf.k*conf.nttnumbus+bus).TREADY & io.axi4sout(conf.k*conf.nttnumbus+bus).TVALID | ShiftRegister(io.flush, conf.axi4snumslice)
            outwire(i) := buffer.io.manager.TDATA(log2Ceil(conf.P)-1,0)

            when(io.flush){
                prng.io.seed := flushseedreg
                prngenablereg := false.B
                prng.io.seedwrite := true.B
            }.elsewhen(RegNext(io.flush)){
                prng.io.seed := flushseedreg
                prngenablereg := true.B
                prng.io.seedwrite := true.B
            }
        }
        io.axi4sout(conf.k*conf.nttnumbus+bus).TVALID := Cat(validwire).andR
        io.axi4sout(conf.k*conf.nttnumbus+bus).TDATA := Cat(outwire.reverse)
    }
}

class SeedInitializer()(implicit val conf:Config) extends Module{
    val io = IO(new Bundle{
        val axi4s = new AXI4StreamSubordinate(conf.spibuswidth)
        val seed = Flipped(new SeedPort())
    })

    io.axi4s.TREADY := true.B
    
    val asconnumreg = conf.asconseedbit/conf.spibuswidth
    val xoshironumreg = conf.xoshiroseedbit/conf.spibuswidth
    val shiftreg = Reg(Vec(asconnumreg,UInt(conf.spibuswidth.W)))
    val asconvalidwire = Wire(Vec(conf.k,Vec(conf.nttnumbus,(Vec(conf.numprng,Bool())))))
    val xoshirovalidwire = Wire(Vec(conf.nttnumbus,(Vec(conf.numprng,Bool()))))

    for(k <- 0 until conf.k){
        for(i <- 0 until conf.nttnumbus){
            for(j <- 0 until conf.numprng){
                io.seed.asconseed(k)(i)(j) := ShiftRegister(Cat(shiftreg.reverse),conf.axi4snumslice)
                asconvalidwire(k)(i)(j) := false.B
                io.seed.asconseedwrite(k)(i)(j) := ShiftRegister(asconvalidwire(k)(i)(j),conf.axi4snumslice+1)
            }
        }
    }
    for(i <- 0 until conf.nttnumbus){
        for(j <- 0 until conf.numprng){
            io.seed.xoshiroseed(i)(j) := ShiftRegister(Cat(shiftreg.reverse),conf.axi4snumslice)
            xoshirovalidwire(i)(j) := false.B
            io.seed.xoshiroseedwrite(i)(j) := ShiftRegister(xoshirovalidwire(i)(j),conf.axi4snumslice+1)
        }
    }
    
    val cntreg = RegInit(0.U(log2Ceil(asconnumreg).W))
    val busreg = RegInit(0.U(log2Ceil(conf.nttnumbus).W))
    val kreg = RegInit(0.U(log2Ceil(conf.k+1).W))
    val prngreg = RegInit(0.U(log2Ceil(conf.numprng).W))
    val initreg = RegInit(true.B)
    val numlimit = Mux(kreg === conf.k.U,(xoshironumreg-1).U,(asconnumreg-1).U)
    when(io.axi4s.TVALID & initreg){
        for(i <- 0 until asconnumreg-1){
            shiftreg(i) := shiftreg(i+1)
        }
        shiftreg(numlimit) := io.axi4s.TDATA
        cntreg := cntreg + 1.U
        when(cntreg === numlimit){
            cntreg := 0.U
            when(kreg === conf.k.U){
                xoshirovalidwire(busreg)(prngreg) := true.B
            }.otherwise{
                asconvalidwire(kreg)(busreg)(prngreg) := true.B
            }

            prngreg := prngreg + 1.U
            when(prngreg === (conf.numprng-1).U){
                prngreg := 0.U
                busreg := busreg + 1.U
                when(busreg === (conf.nttnumbus-1).U){
                    busreg := 0.U
                    kreg := kreg + 1.U
                    when(kreg === conf.k.U){
                        initreg := false.B
                    }
                }
            }
        }
    }
}