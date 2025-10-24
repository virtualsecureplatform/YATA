#include <verilated.h>
#include <verilated_fst_c.h>
#include <VAXISBRWrapper.h>
#include <tfhe++.hpp>

struct xoshiro128{
  std::array<uint32_t,4> s;
  uint32_t result;

  static inline uint32_t rotl(const uint32_t x, int k) {
    return (x << k) | (x >> (32 - k));
  }
  void next(void) {
    result = (rotl(s[1] * 5, 7) * 9)&((1U<<30)-1);

    const uint32_t t = s[1] << 9;

    s[2] ^= s[0];
    s[3] ^= s[1];
    s[1] ^= s[2];
    s[0] ^= s[3];

    s[2] ^= t;

    s[3] = rotl(s[3], 11);
  }
};

//allgned to distribute to module
constexpr int radixbit = 3;
constexpr int radix = 1<<radixbit;
constexpr int nttsize = 64;
constexpr uint numcycle = 8;
constexpr int interslr = 8;

constexpr uint fiber = TFHEpp::lvl1param::n/numcycle;
constexpr uint bknumbus = 12;
constexpr uint buswidthlb = 9;
constexpr uint buswidth = 1<<buswidthlb;
constexpr uint buswords = 1U<<(buswidthlb-5);
constexpr uint wordsinbus = (1U<<buswidthlb)/std::numeric_limits<typename TFHEpp::lvl0param::T>::digits;
constexpr uint nttwordsinbus = (1U<<buswidthlb)/32;
constexpr uint alignedlenlvl0 = (((std::numeric_limits<TFHEpp::lvl0param::T>::digits*(TFHEpp::lvl0param::n+1)>>buswidthlb)+1)<<buswidthlb)/std::numeric_limits<TFHEpp::lvl0param::T>::digits;
constexpr uint alignedlenlvl1 = (((std::numeric_limits<TFHEpp::lvl1param::T>::digits*(TFHEpp::lvl1param::n+1)>>buswidthlb)+1)<<buswidthlb)/std::numeric_limits<TFHEpp::lvl1param::T>::digits;
constexpr uint trlwenumbus = nttsize/wordsinbus;

constexpr uint numprng = buswidth/32;

constexpr uint8_t cmd_seed = 0b10100000;
constexpr uint8_t cmd_infifo = 0b10110000;
constexpr uint8_t cmd_run = 0b11110000;
constexpr uint8_t cmd_runstep = 0b11100000;
constexpr uint8_t cmd_flush = 0b11000000;
constexpr std::array<uint8_t,3> cmd_bkfifo = {0b10101100,0b10101101,0b10101110};
constexpr uint8_t cmd_out = 0b10000000;
constexpr uint8_t nstep = 2;
constexpr uint8_t spibuswidth = 2;
using alignedTLWElvl0 = std::array<TFHEpp::lvl0param::T,alignedlenlvl0>;
using alignedTLWElvl1 = std::array<TFHEpp::lvl1param::T,alignedlenlvl1>;

constexpr uint asyncqueueinit = 8;

alignas(4096) std::array<std::array<std::array<std::array<std::array<uint32_t,nttwordsinbus>,numcycle>,(TFHEpp::lvl1param::k+1)*TFHEpp::lvl1param::l>,TFHEpp::lvl0param::n>,bknumbus> bkrainttaligned = {};
std::array<uint,bknumbus> buscycle = {};

static uint64_t time_counter = 0;

void clock(VAXISBRWrapper *dut, VerilatedFstC* tfp){
  dut->eval();
  tfp->dump(1000*time_counter);
  time_counter++;
  dut->clock = !dut->clock;
  dut->eval();
  tfp->dump(1000*time_counter);
  time_counter++;
  dut->clock = !dut->clock;
}


void spiclock(VAXISBRWrapper *dut, VerilatedFstC* tfp){
  constexpr uint clockratio = 3;
  for(int i = 0; i < clockratio; i++) clock(dut,tfp);
  dut->io_controlSPI_SCLK = !dut->io_controlSPI_SCLK;
  for(int i = 0; i < clockratio; i++) clock(dut,tfp);
  dut->io_controlSPI_SCLK = !dut->io_controlSPI_SCLK;
}

int main(int argc, char** argv) {
  //generatros
  std::random_device seed_gen;
  std::default_random_engine engine(seed_gen());
  std::uniform_int_distribution<uint32_t> binary(0, 1);
  std::uniform_int_distribution<uint32_t> bubble(0, 100);
  std::uniform_int_distribution<uint32_t> seedgen(0, std::numeric_limits<uint32_t>::max());
  
  //Initialize TFHEpp objects
  TFHEpp::SecretKey *sk = new TFHEpp::SecretKey();
  TFHEpp::BootstrappingKeyRAINTT<TFHEpp::lvl01param> *bkraintt = new TFHEpp::BootstrappingKeyRAINTT<TFHEpp::lvl01param>();
  TFHEpp::bkrainttgen<TFHEpp::lvl01param>(*bkraintt,*sk);
  for(int i = 0; i < TFHEpp::lvl0param::k*TFHEpp::lvl0param::n; i++) for(int l = 0; l < TFHEpp::lvl1param::l*(TFHEpp::lvl1param::k+1);l++) for(int k = 0; k < TFHEpp::lvl1param::k+1; k++) for(int j = 0; j < TFHEpp::lvl1param::n; j++)
    (*bkraintt)[i][l][k][j] = ((*bkraintt)[i][l][k][j] % raintt::P + raintt::P) % raintt::P;

  Verilated::commandArgs(argc, argv);
  VAXISBRWrapper *dut = new VAXISBRWrapper();

  Verilated::traceEverOn(true);
  VerilatedFstC* tfp = new VerilatedFstC;
  dut->trace(tfp, 100);  // Trace 100 levels of hierarchy
  tfp->open("simx.fst");

  std::array<std::array<xoshiro128,numprng>,bknumbus> prngs;
  for(int i = 0; i < bknumbus; i++){
    for(int j = 0; j < numprng; j++){
      for(int k = 0; k < 4; k++){
        prngs[i][j].s[k] = seedgen(engine);
      }
    }
  } 

  // Format
  dut->reset = 1;
  dut->clock = 0;
  dut->io_compress = (1<<4)-1;
  // dut->io_compress = 0;
  dut->io_controlSPI_SS = 1;
  dut->io_controlSPI_MOSI = 0;
  dut->io_controlSPI_SCLK = 0;

  // Reset
  clock(dut, tfp);

  //Release reset
  dut->reset = 0;

  for(int _ = 0; _ < 2; _++){

  std::cout<<"Initialize"<<std::endl;
  std::cout<<"BK Init"<<std::endl;
  for(int k = 0; k < TFHEpp::lvl1param::k+1; k++){
  std::cout<<k<<std::endl;
  dut->io_controlSPI_SS = 0;
  for(int i = 0; i < asyncqueueinit; i++) spiclock(dut, tfp);
  for(int i = 0; i < 8/spibuswidth; i++){
    dut->io_controlSPI_MOSI = (cmd_bkfifo[k]>>(i*spibuswidth))&((1<<spibuswidth)-1);
    spiclock(dut, tfp);
  }
  for(int i = 0; i < nstep; i++) for(int l = 0; l < TFHEpp::lvl1param::l; l++) for(int kindex = 0; kindex <= TFHEpp::lvl1param::k; kindex++) for(int cycle = 0; cycle < numcycle; cycle++) for(int bus = 0; bus < bknumbus/(TFHEpp::lvl1param::k+1); bus++) for(int word = 0; word<nttwordsinbus; word++) 
  for(int bit = 0; bit < (raintt::wordbits-1)/spibuswidth; bit++){
    dut->io_controlSPI_MOSI = ((*bkraintt)[i][kindex*TFHEpp::lvl1param::l+l][k][cycle*bknumbus/(TFHEpp::lvl1param::k+1)*nttwordsinbus+bus*nttwordsinbus+word]>>(bit*spibuswidth))&((1<<spibuswidth)-1);;
    spiclock(dut, tfp);
  }
  dut->io_controlSPI_MOSI = 0;
  for(int i = 0; i < 8; i++) clock(dut, tfp);
  dut->io_controlSPI_SS = 1;
  for(int i = 0; i < asyncqueueinit; i++) clock(dut, tfp);
  std::cout<<"bk:"<<std::hex<<static_cast<uint64_t>((*bkraintt)[0][0][k][0])<<std::endl;
  }
  
  alignedTLWElvl0 alignedcin = {};
  bool p = (binary(engine) > 0);
  const TFHEpp::TLWE<TFHEpp::lvl0param> tlwe = TFHEpp::tlweSymEncrypt<TFHEpp::lvl0param>(p,TFHEpp::lvl0param::α,sk->key.lvl0);
  for(int i = 0; i <= TFHEpp::lvl0param::n; i++) alignedcin[i] = tlwe[i];
  TFHEpp::Polynomial<TFHEpp::lvl1param> testvec;
  for (int i = 0; i < TFHEpp::lvl1param::n; i++)
      testvec[i] = TFHEpp::lvl1param::μ;

  TFHEpp::TRLWE<TFHEpp::lvl1param> trlwe, resaligned;
  trlwe = {};
  const uint32_t b̄ =
        2 * TFHEpp::lvl1param::n - (tlwe[TFHEpp::lvl0param::n] >>(std::numeric_limits<typename TFHEpp::lvl0param::T>::digits - 1 - TFHEpp::lvl1param::nbit) );
  TFHEpp::PolynomialMulByXai<TFHEpp::lvl1param>(trlwe[TFHEpp::lvl1param::k],TFHEpp::μpolygen<TFHEpp::lvl1param,TFHEpp::lvl1param::μ>(),b̄);

  std::cout<<"InFIFO Init"<<std::endl;
  dut->io_controlSPI_SS = 0;
  for(int i = 0; i < asyncqueueinit; i++) spiclock(dut, tfp);
  for(int i = 0; i < 8/spibuswidth; i++){
    dut->io_controlSPI_MOSI = (cmd_infifo>>(i*spibuswidth))&((1<<spibuswidth)-1);
    spiclock(dut, tfp);
  }
  for(int i = 0; i < TFHEpp::lvl0param::n+1; i++){
    for(int j = 0; j < std::numeric_limits<TFHEpp::lvl0param::T>::digits/spibuswidth; j++){
      dut->io_controlSPI_MOSI = (alignedcin[i]>>(j*spibuswidth))&((1<<spibuswidth)-1);
      spiclock(dut, tfp);
    }
  }
  dut->io_controlSPI_MOSI = 0;
  for(int i = 0; i < 8; i++) clock(dut, tfp);
  dut->io_controlSPI_SS = 1;

  for(int i = 0; i < 64; i++) clock(dut, tfp);

  std::cout<<"RUNSTEP"<<std::endl;
  dut->io_controlSPI_SS = 0;
  for(int i = 0; i < asyncqueueinit; i++) spiclock(dut, tfp);
  for(int i = 0; i < 8/spibuswidth; i++){
    dut->io_controlSPI_MOSI = (cmd_runstep>>(i*spibuswidth))&((1<<spibuswidth)-1);
    spiclock(dut, tfp);
  }
  for(int i = 0; i < nstep; i++){
    dut->io_controlSPI_MOSI = 1;
    spiclock(dut, tfp);
  }
  // for(int i = 0; i < 32; i++) spiclock(dut, tfp);
  
  int watchdog = 0;
  while(dut->io_debugvalid==0){
    clock(dut, tfp);
    watchdog++;
    if(watchdog>1000){
      dut->final();
      tfp->close(); 
      exit(1);
    }
  }

{
  int errorcount = 0;
  for(int l = 0; l < TFHEpp::lvl1param::k+1; l++)
  for(int i = 0; i < numcycle; i++){
    for(int j = 0; j < nttsize; j++) {
      // const int j = 0;
      if(dut->io_debugout[j]!=trlwe[l][numcycle*j+i]){
        std::cout<<"TVGEN ERROR:"<<l<<":"<<i<<":"<<j<<std::endl;
        std::cout<<static_cast<int>(j)<<":"<<nttsize<<":"<<(0<(nttsize-j))<<std::endl;
        std::cout<<dut->io_debugout[j]<<":"<<trlwe[l][numcycle*j+i]<<std::endl;
        errorcount++;
      }
    }
    clock(dut, tfp);
  }
  std::cout<<"a[0]:"<<tlwe[0]<<":"<<alignedcin[0]<<std::endl;
  std::cout<<"b:"<<tlwe[TFHEpp::lvl0param::n]<<std::endl;
  if(errorcount!=0){
    dut->final();
    tfp->close(); 
    exit(1);
  }
}
dut->io_controlSPI_SS = 1;

  // dut->final();
  // tfp->close(); 
  // exit(1);
  // if(dut->io_axi4in_TREADY == 1){
  //   dut->final();
  //   tfp->close(); 
  //   exit(1);
  // }
  
  std::cout<<TFHEpp::lvl1param::k<<std::endl;
  std::cout<<"Processing"<<std::endl;
  uint count = 0;
  
constexpr typename TFHEpp::lvl0param::T roundoffset = 1ULL << (std::numeric_limits<typename TFHEpp::lvl0param::T>::digits - 2 - TFHEpp::lvl1param::nbit);
for (int nindex = 0; nindex < TFHEpp::lvl0param::n; nindex++) {
  const uint32_t ā = (tlwe[nindex]+roundoffset)>>(std::numeric_limits<typename TFHEpp::lvl0param::T>::digits - 1 - TFHEpp::lvl1param::nbit);
  std::cout<<nindex<<std::endl;
  if((nindex != 0) & ((nindex % nstep) == 0)){
    std::cout<<"BK Init"<<std::endl;
    for(int k = 0; k < TFHEpp::lvl1param::k+1; k++){
      dut->io_controlSPI_SS = 0;
      for(int i = 0; i < asyncqueueinit; i++) spiclock(dut, tfp);
      for(int i = 0; i < 8/spibuswidth; i++){
        dut->io_controlSPI_MOSI = (cmd_bkfifo[k]>>(i*spibuswidth))&((1<<spibuswidth)-1);
        spiclock(dut, tfp);
      }
      for(int i = nindex; i < nindex+nstep; i++) for(int l = 0; l < TFHEpp::lvl1param::l; l++) for(int kindex = 0; kindex <= TFHEpp::lvl1param::k; kindex++) for(int cycle = 0; cycle < numcycle; cycle++) for(int bus = 0; bus < bknumbus/(TFHEpp::lvl1param::k+1); bus++) for(int word = 0; word<nttwordsinbus; word++){
        for(int bit = 0; bit < (raintt::wordbits-1)/spibuswidth; bit++){
          dut->io_controlSPI_MOSI = ((*bkraintt)[i][kindex*TFHEpp::lvl1param::l+l][k][cycle*bknumbus/(TFHEpp::lvl1param::k+1)*nttwordsinbus+bus*nttwordsinbus+word]>>(bit*spibuswidth))&((1<<spibuswidth)-1);;
          spiclock(dut, tfp);
        }
      }
      dut->io_controlSPI_MOSI = 0;
      for(int i = 0; i < 8; i++) clock(dut, tfp);
      dut->io_controlSPI_SS = 1;
      for(int i = 0; i < asyncqueueinit; i++) clock(dut, tfp);
      std::cout<<"bk:"<<std::hex<<static_cast<uint64_t>((*bkraintt)[nindex][0][k][0])<<std::endl;
    }
    std::cout<<"RUNSTEP"<<std::endl;
    dut->io_controlSPI_SS = 0;
    for(int i = 0; i < asyncqueueinit; i++) spiclock(dut, tfp);
    for(int i = 0; i < 8/spibuswidth; i++){
      dut->io_controlSPI_MOSI = (cmd_runstep>>(i*spibuswidth))&((1<<spibuswidth)-1);
      spiclock(dut, tfp);
    }
    for(int i = 0; i < nstep; i++){
      dut->io_controlSPI_MOSI = 1;
      spiclock(dut, tfp);
    }
    dut->io_controlSPI_MOSI = 0;
    // for(int i = 0; i < 8; i++) spiclock(dut, tfp);
  }

  TFHEpp::TRLWE<TFHEpp::lvl1param> pmbx;
  for(int k = 0; k < TFHEpp::lvl1param::k+1; k++)
    TFHEpp::PolynomialMulByXaiMinusOne<TFHEpp::lvl1param>(pmbx[k],trlwe[k],ā);
  watchdog = 0;
  while(dut->io_debugvalid==0){
    clock(dut, tfp);
    watchdog++;
    if(watchdog>1000){
      dut->final();
      tfp->close(); 
      exit(1);
    }
  }
  dut->io_controlSPI_SS = 1;
  {
  int errorcount = 0;
  for(int l = 0; l < TFHEpp::lvl1param::k+1; l++)
    for(int i = 0; i < numcycle; i++){
      for(int j = 0; j < nttsize; j++) {
        if(dut->io_debugout[j]!=pmbx[l][numcycle*j+i]){
          std::cout<<"PMBXERROR:"<<l<<":"<<i<<":"<<j<<std::endl;
          std::cout<<dut->io_debugout[j]<<":"<<pmbx[l][numcycle*j+i]<<std::endl;
          errorcount++;
        }
      }
      clock(dut, tfp);
    }
    // if(errorcount!=0){
    //   dut->final();
    //   tfp->close(); 
    //   exit(1);
    // }
  }

  // if (ā == 0) continue;
  // Do not use CMUXNTT to avoid unnecessary copy.
  TFHEpp::CMUXRAINTTwithPolynomialMulByXaiMinusOne<TFHEpp::lvl1param>(
      trlwe, (*bkraintt)[nindex], ā);
  
  watchdog = 0;
  while(dut->io_debugvalid==0){
    clock(dut, tfp);
    watchdog++;
    if(watchdog>1000){
      dut->final();
      tfp->close(); 
      exit(1);
    }
  }
  for(int k = 0; k < TFHEpp::lvl1param::k+1; k++){
    // watchdog = 0;
    // while(dut->io_debugvalid==0){
    //   clock(dut, tfp);
    //   watchdog++;
    //   if(watchdog>1000){
    //     dut->final();
    //     tfp->close(); 
    //     exit(1);
    //   }
    // }
    for(int cycle = 0; cycle<numcycle;cycle++){
      for(int m = 0; m<nttsize;m++){
        if(dut->io_debugout[m]!=trlwe[k][m*numcycle+cycle]){
          std::cout<<nindex<<":"<<k<<":"<<cycle<<":"<<m<<std::endl;
          std::cout<<tlwe[nindex]<<std::endl;
          std::cout<<"ERROR:"<<trlwe[k][m*numcycle+cycle]<<":"<<dut->io_debugout[m]<<std::endl;
          dut->final();
          tfp->close(); 
          exit(1);
        }
      }
      clock(dut, tfp);
    }
  }
}
  
  watchdog = 0;
  while(dut->io_fin==0){
    clock(dut, tfp);
    watchdog++;
    if(watchdog>1000){
      dut->final();
      tfp->close(); 
      exit(1);
    }
  }

  std::cout<<"Output"<<std::endl;
  dut->io_controlSPI_SS = 0;
  for(int i = 0; i < asyncqueueinit; i++) spiclock(dut, tfp);
  for(int i = 0; i < 8/spibuswidth; i++){
    dut->io_controlSPI_MOSI = (cmd_out>>(i*spibuswidth))&((1<<spibuswidth)-1);
    spiclock(dut, tfp);
  }
  clock(dut,tfp);
  clock(dut,tfp);
  for(int i = 0; i <= TFHEpp::lvl1param::k; i++){
    for(int cycle = 0; cycle<numcycle;cycle++){
      for(int bus = 0; bus < trlwenumbus; bus++){
        for(int m = 0; m<nttsize/trlwenumbus;m++){
          typename TFHEpp::lvl1param::T torus = 0;
          for(int bit = 0; bit < std::numeric_limits<TFHEpp::lvl1param::T>::digits/spibuswidth; bit++){
            torus |= dut->io_controlSPI_MISO<<(bit*spibuswidth);
            spiclock(dut, tfp);
          }
          resaligned[i][(bus*nttsize/trlwenumbus+m)*numcycle+cycle] = torus;
        }
      }
    }
  }
  dut->io_controlSPI_SS = 1;
  for(int i = 0; i < asyncqueueinit; i++) spiclock(dut, tfp);

  TFHEpp::BlindRotate<TFHEpp::lvl01param>(trlwe,tlwe,*bkraintt,TFHEpp::μpolygen<TFHEpp::lvl1param, TFHEpp::lvl1param::μ>());

  {
  int errorcount = 0;
  for(int i = 0; i <= TFHEpp::lvl1param::k; i++){
    for(int j = 0; j<TFHEpp::lvl1param::n;j++){
        uint32_t trueout = trlwe[i][j];
        uint32_t circout = resaligned[i][j];
        if(abs(static_cast<int>(trueout - circout)>1)){
          std::cout<<"Error: "<<trueout<<":"<<circout<<std::endl;
          std::cout<<i<<":"<<j<<std::endl;
          errorcount++;
        }
    }
    if(errorcount!=0){
      dut->final();
      tfp->close(); 
      exit(1);
    }
  }
  }
  std::cout<<"Flush" << std::endl;
  dut->io_controlSPI_SS = 0;
  for(int i = 0; i < asyncqueueinit; i++) spiclock(dut, tfp);
  for(int i = 0; i < 8; i++){
    dut->io_controlSPI_MOSI = (cmd_flush>>i)&1;
    spiclock(dut, tfp);
  }
  for(int i = 0; i < 2*asyncqueueinit; i++) spiclock(dut, tfp);
  dut->io_controlSPI_SS = 1;
  for(int i = 0; i < asyncqueueinit; i++) spiclock(dut, tfp);
  }
  std::cout<<"PASS"<<std::endl;
}