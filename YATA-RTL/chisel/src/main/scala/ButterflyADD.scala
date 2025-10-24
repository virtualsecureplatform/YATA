import chisel3._
import chisel3.util._

class ButterflyBothMod(implicit val conf: Config) extends Module{
    val io = IO(new Bundle {
        val A = Input(SInt(conf.wordbits.W))
        val B = Input(SInt(conf.wordbits.W))
        val ADDY = Output(SInt(conf.wordbits.W))
        val SUBY = Output(SInt(conf.wordbits.W))
    })

    val adder = Module(new INTorusADD)
    adder.io.A := io.A
    adder.io.B := io.B
    io.ADDY := adder.io.Y
    
    val suber = Module(new INTorusSUB)
    suber.io.A := io.A
    suber.io.B := io.B
    io.SUBY := suber.io.Y
}

class ButterflyAddMod(implicit val conf: Config) extends Module{
    val io = IO(new Bundle {
        val A = Input(SInt(conf.wordbits.W))
        val B = Input(SInt(conf.wordbits.W))
        val ADDY = Output(SInt(conf.wordbits.W))
        val SUBY = Output(SInt((conf.wordbits+1).W))
    })

    val adder = Module(new INTorusADD)
    adder.io.A := io.A
    adder.io.B := io.B
    io.ADDY := adder.io.Y
    
    io.SUBY := ShiftRegister(io.A -& io.B,conf.adddelay)
}

class ButterflyAddBothMod(size: Int, implicit val conf: Config) extends Module{
    val io = IO(new Bundle {
        val in = Input(Vec(size,SInt(conf.wordbits.W)))
        val out = Output(Vec(size,SInt(conf.wordbits.W)))
    })
    val butterflys = for (i <- 0 until size/2) yield{
        val but = Module(new ButterflyBothMod)
        but
    }
    butterflys.zipWithIndex.map { case (but, i) =>
        but.io.A := io.in(i)
        but.io.B := io.in(i+size/2)
        io.out(i) := but.io.ADDY
        io.out(i+size/2) := but.io.SUBY
    }
}

class ButterflyAddBothSREDC(size: Int, implicit val conf: Config) extends Module{
    val io = IO(new Bundle {
        val in = Input(Vec(size,SInt((2*conf.wordbits).W)))
        val out = Output(Vec(size,SInt(conf.wordbits.W)))
    })
    for(i <- 0 until size/2){
        val addSREDC = Module(new INTorusSREDC)
        val subSREDC = Module(new INTorusSREDC)
        addSREDC.io.A := ShiftRegister(io.in(i) + io.in(i+size/2),conf.adddelay)
        subSREDC.io.A := ShiftRegister(io.in(i) - io.in(i+size/2),conf.adddelay)
        io.out(i) := addSREDC.io.Y
        io.out(i+size/2) := subSREDC.io.Y
    }
}

class ButterflyAddAddMod(size: Int, implicit val conf: Config) extends Module{
    val io = IO(new Bundle {
        val in = Input(Vec(size,SInt(conf.wordbits.W)))
        val outadd = Output(Vec(size/2,SInt(conf.wordbits.W)))
        val outsub = Output(Vec(size/2,SInt((conf.wordbits+1).W)))
    })
    val butterflys = for (i <- 0 until size/2) yield{
        val but = Module(new ButterflyAddMod)
        but
    }
    butterflys.zipWithIndex.map { case (but, i) =>
        but.io.A := io.in(i)
        but.io.B := io.in(i+size/2)
        io.outadd(i) := but.io.ADDY
        io.outsub(i) := but.io.SUBY
    }
}

class INTTradixButterflyUnitRadix4(size: Int, implicit val conf: Config) extends Module{
    val radixbit = 2
    val io = IO(new Bundle{
        val in = Input(Vec(size,SInt(conf.wordbits.W)))
        val out = Output(Vec(size,SInt(conf.wordbits.W)))
    })
    val butadder = Module(new ButterflyAddAddMod(size,conf))

    butadder.io.in := io.in

    val subbus = Wire(Vec(size/2,SInt((2*conf.wordbits).W)))
    val block = size >> radixbit
    subbus := ShiftRegister(butadder.io.outsub,conf.consttwiddelay+1)

    for(i <- 1 until 1<< (radixbit-1)){
        for(j <- 0 until block){
            val consttwidmul = Module(new INTorusConstTwiddleMul(radixbit,i,conf))
            consttwidmul.io.A := RegNext(butadder.io.outsub(i * block + j))
            subbus(i * block + j) := consttwidmul.io.Y
        }
    }

    // radix2
    val butadderbothmod = Module(new ButterflyAddBothMod(size/2,conf))
    butadderbothmod.io.in := ShiftRegister(butadder.io.outadd,conf.consttwiddelay+1)
    for(i <- 0 until size/2){
        io.out(i) := ShiftRegister(butadderbothmod.io.out(i),conf.SREDCdelay)
    }
    val butsredc = Module(new ButterflyAddBothSREDC(size/2,conf))
    butsredc.io.in := subbus
    for(i <- 0 until size/2){
        io.out(i+size/2) := butsredc.io.out(i)
    }
}

class INTTradixButterflyUnitRadix8(size: Int,implicit val conf: Config) extends Module{
    val radixbit = 3
    val io = IO(new Bundle{
        val in = Input(Vec(size,SInt(conf.wordbits.W)))
        val out = Output(Vec(size,SInt(conf.wordbits.W)))
    })
    val butadder = Module(new ButterflyAddAddMod(size,conf))

    butadder.io.in := RegNext(io.in)

    val radix4 = Module(new INTTradixButterflyUnitRadix4(size/2,conf))
    radix4.io.in := butadder.io.outadd
    for(i <- 0 until size/2){
        io.out(i) := radix4.io.out(i)
    }

    //radix 8
    val subbus = Wire(Vec(size/2,SInt((2*conf.wordbits).W)))
    val block = size >> radixbit
    
    for(i <- 0 until block){
        {
            val consttwidmul = Module(new INTorusConstTwiddleMul(radixbit,2,conf))
            consttwidmul.io.A := butadder.io.outsub(2*block + i)
            subbus(i) := ShiftRegister(ShiftRegister(butadder.io.outsub(i),conf.consttwiddelay) + consttwidmul.io.Y,conf.adddelay)
            subbus(2*block + i) := ShiftRegister(ShiftRegister(butadder.io.outsub(i),conf.consttwiddelay) - consttwidmul.io.Y,conf.adddelay)
        }
        {
            val consttwidmulone = Module(new INTorusConstTwiddleMul(radixbit,1,conf))
            val consttwidmulthree = Module(new INTorusConstTwiddleMul(radixbit,3,conf))
            consttwidmulone.io.A := butadder.io.outsub(1*block + i)
            consttwidmulthree.io.A := butadder.io.outsub(3*block + i)
            subbus(1*block + i) := ShiftRegister(consttwidmulone.io.Y + consttwidmulthree.io.Y,conf.adddelay)
        }
        {
            val consttwidmulone = Module(new INTorusConstTwiddleMul(radixbit,1,conf))
            val consttwidmulthree = Module(new INTorusConstTwiddleMul(radixbit,3,conf))
            consttwidmulone.io.A := butadder.io.outsub(3*block + i)
            consttwidmulthree.io.A := butadder.io.outsub(1*block + i)
            subbus(3*block + i) := ShiftRegister(consttwidmulone.io.Y + consttwidmulthree.io.Y,conf.adddelay)
        }
    }
    for(i <- 0 until 2){
        val butsredc = Module(new ButterflyAddBothSREDC(size/4,conf))
        for(j <- 0 until size/4){
            butsredc.io.in(j) := RegNext(subbus(i*size/4+j))
            io.out(size/2+i*size/4+j) := butsredc.io.out(j)
        }
    }
}

class INTTradixButterflyUnit64(implicit val conf: Config) extends Module{

    val size = 64
    val io = IO(new Bundle{
        val in = Input(Vec(size,SInt(conf.wordbits.W)))
        val out = Output(Vec(size,SInt(conf.wordbits.W)))
    })
    
    val formerbut = Module(new INTTradixButterflyUnitRadix8(size,conf))
    formerbut.io.in := io.in

    val block = size >> conf.radixbit
    for(i <- 0 until conf.radix){
        val laterbut = Module(new INTTradixButterflyUnitRadix8(block,conf))
        if(i == 0){
            for(j <- 0 until block){
                laterbut.io.in(j) := ShiftRegister(formerbut.io.out(i*block+j), conf.muldelay)
            }
        }else{
            for(j <- 0 until block){
                val mul = Module(new INTorusMULSREDC)
                mul.io.A := formerbut.io.out(i*block+j)
                mul.io.B := conf.intttable(if(i > 1) 1 else 0)(conf.bitreverse(i,conf.radixbit) * conf.radix * j).S
                laterbut.io.in(j) := mul.io.Y
            }
        }
        for(j <- 0 until block){
            io.out(i*block+j) := laterbut.io.out(j)
        }
    }
}


class NTTradixButterflyUnitRadix4noSREDC(size: Int, implicit val conf: Config) extends Module{
    val radixbit = 2
    val io = IO(new Bundle{
        val in = Input(Vec(size,SInt(conf.wordbits.W)))
        val outeven = Output(Vec(2,Vec(size/4,SInt(conf.wordbits.W))))
        val outodd = Output(Vec(2,Vec(size/4,SInt((2*conf.wordbits).W))))
    })
    // radix2
    val butadderbothmodupper = Module(new ButterflyAddBothMod(size/2,conf))
    val butadderbothmodlower = Module(new ButterflyAddBothMod(size/2,conf))
    for(i <- 0 until size/2){
        butadderbothmodupper.io.in(i) :=RegNext(io.in(i))
        butadderbothmodlower.io.in(i) :=RegNext(io.in(i+size/2))
    }
    for(i <- 0 until size/4){
        val add = Module(new INTorusADD)
        val sub = Module(new INTorusSUB)
        add.io.A := butadderbothmodupper.io.out(i)
        add.io.B := butadderbothmodlower.io.out(i)
        io.outeven(0)(i) := ShiftRegister(add.io.Y,conf.consttwiddelay)
        sub.io.A := butadderbothmodupper.io.out(i)
        sub.io.B := butadderbothmodlower.io.out(i)
        io.outeven(1)(i) := ShiftRegister(sub.io.Y,conf.consttwiddelay)

        val consttwidmul = Module(new INTorusConstTwiddleMul(radixbit,1,conf))
        consttwidmul.io.A := butadderbothmodlower.io.out(i+size/4)
        io.outodd(0)(i) := ShiftRegister(ShiftRegister(butadderbothmodupper.io.out(i+size/4),conf.consttwiddelay) - consttwidmul.io.Y,conf.adddelay)
        io.outodd(1)(i) := ShiftRegister(ShiftRegister(butadderbothmodupper.io.out(i+size/4),conf.consttwiddelay) + consttwidmul.io.Y,conf.adddelay)
    }
}

class NTTradixButterflyUnitRadix8(size: Int,implicit val conf: Config) extends Module{
    val radixbit = 3
    val io = IO(new Bundle{
        val in = Input(Vec(size,SInt(conf.wordbits.W)))
        val out = Output(Vec(size,SInt(conf.wordbits.W)))
    })
    val radix4 = Module(new NTTradixButterflyUnitRadix4noSREDC(size/2,conf))
    val radix2upper = Module(new ButterflyAddBothMod(size/4,conf))
    val radix2lower = Module(new ButterflyAddBothMod(size/4,conf))


    for(i <- 0 until size/2){
        radix4.io.in(i) := io.in(i)
    }
    for(i <- 0 until size/4){
        radix2upper.io.in(i) := io.in(i+size/2)
        radix2lower.io.in(i) := io.in(i+size/2+size/4)
    }

    val lowerwire = Wire(Vec(3,Vec(size/8, SInt((2*conf.wordbits).W))))
    val butadderaddmod = Module(new ButterflyAddAddMod(size/4,conf))
    for(i <- 0 until size/8){
        {
            butadderaddmod.io.in(i) := radix2upper.io.out(i)
            butadderaddmod.io.in(i+size/8) := radix2lower.io.out(i)
            val consttwidmultwo = Module(new INTorusConstTwiddleMul(radixbit, 2, conf))
            consttwidmultwo.io.A := butadderaddmod.io.outsub(i)
            lowerwire(1)(i) := -consttwidmultwo.io.Y
        }
        {
            val consttwidmulthree = Module(new INTorusConstTwiddleMul(radixbit, 3, conf))
            val consttwidmulone = Module(new INTorusConstTwiddleMul(radixbit, 1, conf))
            consttwidmulthree.io.A := radix2upper.io.out(i+size/8)
            consttwidmulone.io.A := radix2lower.io.out(i+size/8)
            lowerwire(0)(i) := ShiftRegister(-consttwidmulthree.io.Y - consttwidmulone.io.Y,conf.adddelay)
        }
        {
            val consttwidmulone = Module(new INTorusConstTwiddleMul(radixbit, 1, conf))
            val consttwidmulthree = Module(new INTorusConstTwiddleMul(radixbit, 3, conf))
            consttwidmulone.io.A := radix2upper.io.out(i+size/8)
            consttwidmulthree.io.A := radix2lower.io.out(i+size/8)
            lowerwire(2)(i) := ShiftRegister(-consttwidmulone.io.Y - consttwidmulthree.io.Y,conf.adddelay)
        }
        
    }

    {
        val butadderbothmod = Module(new ButterflyAddBothMod(size/4,conf))
        for(i <- 0 until size/8){
            butadderbothmod.io.in(i) := radix4.io.outeven(0)(i)
            butadderbothmod.io.in(i+size/8) := ShiftRegister(butadderaddmod.io.outadd(i),conf.adddelay+conf.consttwiddelay)
            io.out(i) := ShiftRegister(butadderbothmod.io.out(i),conf.SREDCdelay)
            io.out(i+size/2) := ShiftRegister(butadderbothmod.io.out(i+size/8),conf.SREDCdelay)
        }
    }

    val butadderbothsredc = Module(new ButterflyAddBothSREDC(3*size/4,conf))
    for(i <- 0 until size/8){
        butadderbothsredc.io.in(i) := radix4.io.outodd(0)(i)
        io.out(i+size/8) := butadderbothsredc.io.out(i)
        butadderbothsredc.io.in(i+size/8) := radix4.io.outeven(1)(i)
        io.out(i+2*size/8) := butadderbothsredc.io.out(i+size/8)
        butadderbothsredc.io.in(i+2*size/8) := radix4.io.outodd(1)(i)
        io.out(i+3*size/8) := butadderbothsredc.io.out(i+2*size/8)
        for(j <- 0 until 3){
            butadderbothsredc.io.in(i+(3+j)*size/8) := RegNext(lowerwire(j)(i))
            io.out(i+(5+j)*size/8) := butadderbothsredc.io.out(i+(3+j)*size/8)
        }
    }
}

class NTTradixButterflyUnit64(implicit val conf: Config) extends Module{
    val sizebit = 6
    val size = 1<<sizebit
    val radixbit = 3
    val io = IO(new Bundle{
        val in = Input(Vec(size,SInt(conf.wordbits.W)))
        val out = Output(Vec(size,SInt(conf.wordbits.W)))
    })
    
    val laterbut = Module(new NTTradixButterflyUnitRadix8(size,conf))
    val blockbit = sizebit - radixbit
    val block = size >> conf.radixbit
    io.out := laterbut.io.out
    for(i <- 0 until conf.radix){
        val formerbut = Module(new NTTradixButterflyUnitRadix8(conf.radix,conf))
        for(j <- 0 until conf.radix){
            formerbut.io.in(j) := io.in(i*conf.radix+j)
        }
        if(i == 0){
            for(j <- 0 until block){
                if(((j>>(blockbit-radixbit))&((1<<(radixbit-1))-1)) != 0){
                    val mul = Module(new INTorusMULSREDC)
                    mul.io.A := formerbut.io.out(j)
                    mul.io.B := conf.R2.S
                    laterbut.io.in(i*block+j) := mul.io.Y
                }else{
                    laterbut.io.in(i*block+j) := ShiftRegister(formerbut.io.out(j),conf.muldelay)
                }
            }
        }else{
            for(j <- 0 until block){
                val mul = Module(new INTorusMULSREDC)
                mul.io.A := formerbut.io.out(j)
                mul.io.B := conf.ntttable(if(((j>>(blockbit-radixbit))&((1<<(radixbit-1))-1)) != 0) 1 else 0)(conf.bitreverse(i,conf.radixbit) * conf.radix * j).S
                laterbut.io.in(i*block+j) := mul.io.Y
            }
        }
    }
}