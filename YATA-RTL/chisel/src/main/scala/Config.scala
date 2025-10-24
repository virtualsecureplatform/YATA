
case class Config() {
    //Simulate Mode
    val velirate = true

    //Hardware Configuration
    val useSRAM = false
    val useQueue4Buffer = false
    val useDualPort = true

    //security parameters
    val n = 636
    val Nbit = 9
    val N = 1<<Nbit
    val Bgbit = 8
    val Bg = 1<<Bgbit
    val k = 2
    val l = 2
    val t = 7
    val basebit = 2
    val Qbit = 32
    val qbit = 32
    val mu = 1<<29

    // implementation specific parameters
    val buswidthbit = 9
    val buswidth = 1<<buswidthbit
    val hbmbuswidth = 512
    val cmdbuswidth = 104
    val changesizeslice = 6
    
    val axi4snumslice = 4
    val accnumslice = 4
    val interslr = 4

    //Constants
    val radixbit = 3
    val nttsizebit = 6
    val nttsize = 1<<nttsizebit
    val radixs2 = 1<<(radixbit-1)
    val rk = 5
    val K = Math.pow(rk,radixs2).toInt
    val shiftunit = 4
    val shiftamount = shiftunit*radixs2
    val P = (K << shiftamount) + 1

    val cyclebit = Nbit - nttsizebit
    val laternumbit = 6
    val laternum = 1<<laternumbit
    val radix = 1 << radixbit
    val numcycle = 1 << cyclebit

    val SREDCdelay = 1
    val consttwiddelay = 1
    val multiplierpipestage = 2
    val muldelay = multiplierpipestage + SREDCdelay
    val adddelay = 1
    val radixdelay = 3*adddelay+2*consttwiddelay+SREDCdelay+1

    val wordbits = 27
    val wordmask = ((1L << wordbits) - 1)

    val trlwenumbus = nttsize*Qbit/buswidth
    val nttnumbus = nttsize*32/buswidth
    val bknumbus = (k+1)*nttnumbus

    val xoshiroseedbit = 128
    val asconseedbit = 320
    val asconN = 4
    val numprng = buswidth/32
    val prngbufsize = 2*(k+1)*l*numcycle

    val spibuswidth = 2 
    val numConfigRegisterbit = 6
    val numConfigRegister = 1<<numConfigRegisterbit

    // val bkbuffdepth = 1<<15
    val bkbuffdepth = 1<<7

    val R = ((1L << wordbits) % P).toInt
    val R2 = ((R.toLong * R) % P).toInt

    def bitreverse(in: Int, n: Int, out: Int = 0): Int ={
        if (n == 0) out
        else bitreverse(in >>> 1, n - 1, (out << 1) | (in & 1))}

    def REDC(T: Long): Int = {
        val T0:Long = T & wordmask
        val m:Long = (((T0 * K) << shiftamount) - T0) & wordmask
        val t:Long = (T + ((m.toLong * K) << shiftamount) + m) >> wordbits
        if (t > P) (t - P).toInt else t.toInt
    }

    def SREDC(a: Long): Int = {
        val a0: Long = a & wordmask
        val a1: Int = (a >> wordbits).toInt
        val m: Int = ((((-((a0 * K) << shiftamount) + a0) & wordmask)<<(32-wordbits)).toInt)>>(32-wordbits)
        val t1: Int = ((((m.toLong * K) << shiftamount) + m) >> wordbits).toInt
        a1 - t1
    }

    def MulREDC(a: Int, b: Int): Int = {
        val mul = a.toLong * b
        REDC(mul)
    }

    def MulSREDC(a: Int, b: Int): Int = {
        val mul = a.toLong * b
        SREDC(mul)
    }

    def PowREDC(a: Int, e: Int): Int = {
        var res = 1
        val aR = MulREDC(R2, a)
        for (i <- 0 until e) res = MulREDC(res, aR)
        res
    }

    val W = PowREDC(31, K);


    def ext_gcd(a: Int, b: Int): (Int, Int, Int) = {
        if (b == 0) {
            (a, 1, 0)
        } else {
            val (d, y, x) = ext_gcd(b, a % b)
            (d, x, y - a / b * x)
        }
        }

    def inv_mod(a: Int): Int = {
        val (g, x, _) = ext_gcd(a, P)
        if (g != 1) {
            throw new Exception("Inverse doesn't exist")
        } else {
            (x % P + P) % P // this line ensures x is positive
        }
    }

    def NTTtableGen(): List[List[Int]] = {

        val wR = MulREDC(PowREDC(W, 1 << (shiftamount - Nbit)), R2)
        var twiddle:List[Int] = List(wR)

        for (i <- 1 until N-1){
            twiddle = twiddle :+ MulREDC(twiddle(i-1),wR)
        }
        assert(MulREDC(twiddle(N-2),wR)==R)
        twiddle = R::(twiddle.reverse)
        var twiddleR :List[Int] = List(R2)
        for(i <- 1 until N){
            twiddleR = twiddleR :+ MulREDC(twiddle(i),R2)
        }
        List(twiddle,twiddleR)
    }

    def INTTtableGen(): List[List[Int]] = {
        val wR = MulREDC(PowREDC(W, 1 << (shiftamount - Nbit)), R2)
        var twiddle:List[Int] = List(R)
        for(i <- 1 until N){
            twiddle = twiddle :+ MulREDC(twiddle(i-1),wR)
        }
        var twiddleR :List[Int] = List(R2)
        for(i <- 1 until N){
            twiddleR = twiddleR :+ MulREDC(twiddle(i),R2)
        }
        List(twiddle,twiddleR)
    }

    val intttable:List[List[Int]] = INTTtableGen()
    val ntttable:List[List[Int]] = NTTtableGen()

    def NTTtwistGen(): List[Int] = {
        val invN = inv_mod(N);

        val wR = MulREDC(PowREDC(W, 1 << (shiftamount - Nbit - 1)), R2)
        var twist:List[Int] = List(MulREDC(MulREDC(invN,R2),MulREDC(MulREDC(P-1,R2),wR)))
        for (i <- 1 until N){
            twist = twist :+ MulREDC(twist(i - 1),wR)
        }
        twist = twist.reverse
        assert(twist(0)==MulREDC(invN,R2))
        if(radixbit != 1){
            var twistR:List[Int] = List(twist(0))
            for (i <- 1 until N){
                if(((i>>(Nbit-radixbit))&((1<<(radixbit-1))-1))!=0){
                    twistR = twistR :+ MulREDC(twist(i),R2)
                }else{
                    twistR = twistR :+ twist(i)
                }
            }
            twistR
        }else{
            twist
        }
    }

    def INTTtwistGen(): List[Int] = {
        val wR = MulREDC(PowREDC(W, 1 << (shiftamount - Nbit - 1)), R2)
        var twist = List(R)
        for (i <- 1 until N){
            twist = twist :+ MulREDC(twist(i-1),wR)
        }
        twist
    }
    
    val intttwist:List[Int] = INTTtwistGen()
    val ntttwist:List[Int] = NTTtwistGen()
}