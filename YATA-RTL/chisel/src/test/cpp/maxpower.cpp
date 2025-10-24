#include <bits/stdint-uintn.h>
#include <verilated.h>
#include <verilated_fst_c.h>
#include <VAXISBRWrapper.h>
#include <tfhe++.hpp>
#include <permutations.h>

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

struct ASCONPRNG{
  state_t S;
  uint index = 0;
  uint32_t result;

  void next(void){
    if(index == 0){
      P12(&S);
      result = S.x0&((1U<<30)-1);
      index = 1;
    }else{
      index = 0;
      result = (S.x0>>32)&((1U<<30)-1);    
    }
  }
};

//allgned to distribute to module
constexpr int radixbit = 3;
constexpr int radix = 1<<radixbit;
constexpr int nttsize = 64;
constexpr uint numcycle = 8;
constexpr int interslr = 8;

constexpr uint fiber = TFHEpp::lvl1param::n/numcycle;
constexpr uint nttnumbus = 4;
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

// alignas(4096) std::array<std::array<std::array<std::array<std::array<uint32_t,nttwordsinbus>,numcycle>,(TFHEpp::lvl1param::k+1)*TFHEpp::lvl1param::l>,TFHEpp::lvl0param::n>,bknumbus> bkrainttaligned = {};
std::array<uint,bknumbus> buscycle = {};

static uint time_counter = 0;

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
  std::uniform_int_distribution<uint32_t> xoshiroseedgen(0, std::numeric_limits<uint32_t>::max());
  std::uniform_int_distribution<uint64_t> asconseedgen(0, std::numeric_limits<uint64_t>::max());
  
  //Initialize TFHEpp objects
  TFHEpp::SecretKey *sk = new TFHEpp::SecretKey();
  TFHEpp::BootstrappingKeyRAINTT<TFHEpp::lvl01param> *bkraintt = new TFHEpp::BootstrappingKeyRAINTT<TFHEpp::lvl01param>();
  TFHEpp::bkrainttgen<TFHEpp::lvl01param>(*bkraintt,*sk);

  Verilated::commandArgs(argc, argv);
  VAXISBRWrapper *dut = new VAXISBRWrapper();

  Verilated::traceEverOn(true);
  VerilatedFstC* tfp = new VerilatedFstC;
  dut->trace(tfp, 100);  // Trace 100 levels of hierarchy
  tfp->open("simx.fst");

  std::array<std::array<std::array<ASCONPRNG,numprng>,nttnumbus>, TFHEpp::lvl1param::k> asconprngs;
  for(int k = 0; k < TFHEpp::lvl1param::k; k++){
    for(int i = 0; i < nttnumbus; i++){
      for(int j = 0; j < numprng; j++){
        asconprngs[k][i][j].S.x0 = asconseedgen(engine);
        asconprngs[k][i][j].S.x1 = asconseedgen(engine);
        asconprngs[k][i][j].S.x2 = asconseedgen(engine);
        asconprngs[k][i][j].S.x3 = asconseedgen(engine);
        asconprngs[k][i][j].S.x4 = asconseedgen(engine);
      }
    }
  }

  std::array<std::array<xoshiro128,numprng>,nttnumbus> xoshiroprngs;
  for(int i = 0; i < nttnumbus; i++){
    for(int j = 0; j < numprng; j++){
      for(int k = 0; k < 4; k++){
        xoshiroprngs[i][j].s[k] = xoshiroseedgen(engine);
      }
    }
  }

  // Format
  dut->reset = 1;
  dut->clock = 0;
  dut->io_compress = 0b000;
  dut->io_controlSPI_SS = 1;
  dut->io_controlSPI_MOSI = 0;
  dut->io_controlSPI_SCLK = 0;
  dut->io_maxpower = 0;

  // Reset
  clock(dut, tfp);

  //Release reset
  dut->reset = 0;
  dut->io_maxpower = 1;

  std::cout<<"Initialize"<<std::endl;
  std::cout<<"Seed Init"<<std::endl;
  dut->io_controlSPI_SS = 0;
  for(int i = 0; i < asyncqueueinit; i++) spiclock(dut, tfp);
  for(int i = 0; i < 8/spibuswidth; i++){
    dut->io_controlSPI_MOSI = (cmd_seed>>(i*spibuswidth))&((1<<spibuswidth)-1);
    spiclock(dut, tfp);
  }
  for(int k = 0; k < TFHEpp::lvl1param::k; k++){
    for(int i = 0; i < nttnumbus; i++){
      for(int j = 0; j < numprng; j++){
        for(int l = 0; l < 64/spibuswidth; l++){
          dut->io_controlSPI_MOSI = (asconprngs[k][i][j].S.x0>>(l*spibuswidth))&((1<<spibuswidth)-1);
          spiclock(dut, tfp);
        }
        for(int l = 0; l < 64/spibuswidth; l++){
          dut->io_controlSPI_MOSI = (asconprngs[k][i][j].S.x1>>(l*spibuswidth))&((1<<spibuswidth)-1);
          spiclock(dut, tfp);
        }
        for(int l = 0; l < 64/spibuswidth; l++){
          dut->io_controlSPI_MOSI = (asconprngs[k][i][j].S.x2>>(l*spibuswidth))&((1<<spibuswidth)-1);
          spiclock(dut, tfp);
        }
        for(int l = 0; l < 64/spibuswidth; l++){
          dut->io_controlSPI_MOSI = (asconprngs[k][i][j].S.x3>>(l*spibuswidth))&((1<<spibuswidth)-1);
          spiclock(dut, tfp);
        }
        for(int l = 0; l < 64/spibuswidth; l++){
          dut->io_controlSPI_MOSI = (asconprngs[k][i][j].S.x4>>(l*spibuswidth))&((1<<spibuswidth)-1);
          spiclock(dut, tfp);
        }
      }
    }
  }
  for(int i = 0; i < nttnumbus; i++){
    for(int j = 0; j < numprng; j++){
      for(int k = 0; k < 4; k++){
        for(int l = 0; l < 32/spibuswidth; l++){
          dut->io_controlSPI_MOSI = (xoshiroprngs[i][j].s[k]>>(l*spibuswidth))&((1<<spibuswidth)-1);
          spiclock(dut, tfp);
        }
      }
    }
  }
  dut->io_controlSPI_MOSI = 0;
  for(int i = 0; i < 8; i++) spiclock(dut, tfp);
  dut->io_controlSPI_SS = 1;
  for(int i = 0; i < asyncqueueinit; i++) clock(dut, tfp);

  std::cout<<"asconseed:"<<std::hex<<asconprngs[0][0][0].S.x0<<std::endl;
  std::cout<<"asconseed:"<<std::hex<<asconprngs[0][nttnumbus-1][numprng-1].S.x0<<std::endl;
  std::cout<<"xoshrioseed:"<<std::hex<<xoshiroprngs[0][0].s[0]<<std::endl;
  for(int count = 0; count < 4; count++)
    std::cout<<"xoshiroseed:"<<count<<":"<<std::hex<<xoshiroprngs[nttnumbus-1][numprng-1].s[count]<<std::endl;
  // for(int k =0; k <= TFHEpp::lvl1param::k; k++) for(int bus = 0; bus < bknumbus/(TFHEpp::lvl1param::k+1); bus++) for(int i = 0; i < TFHEpp::lvl0param::n; i++) for(int l = 0; l < TFHEpp::lvl1param::l; l++) for(int kindex = 0; kindex <= TFHEpp::lvl1param::k; kindex++) for(int cycle = 0; cycle < numcycle; cycle++) for(int word = 0; word<nttwordsinbus; word++) bkrainttaligned[k*bknumbus/(TFHEpp::lvl1param::k+1)+bus][i][(TFHEpp::lvl1param::k+1)*l+kindex][cycle][word] = (*bkraintt)[i][kindex*TFHEpp::lvl1param::l+l][k][cycle*bknumbus/(TFHEpp::lvl1param::k+1)*nttwordsinbus+bus*nttwordsinbus+word];
  for(int k = 0; k < TFHEpp::lvl1param::k; k++) 
    for(int bus = 0; bus < bknumbus/(TFHEpp::lvl1param::k+1); bus++) for(int i = 0; i < TFHEpp::lvl0param::n; i++) for(int l = 0; l < TFHEpp::lvl1param::l; l++) for(int kindex = 0; kindex <= TFHEpp::lvl1param::k; kindex++) for(int word = 0; word<nttwordsinbus; word++)for(int cycle = 0; cycle < numcycle;){
      asconprngs[k][bus][word].next();
      // if(k==0&&bus==0&&word==0)std::cout<<asconprngs[k][bus][word].S.x0<<":"<<asconprngs[k][bus][word].result[0]<<":"<<asconprngs[k][bus][word].result[1]<<std::endl;
      if(asconprngs[k][bus][word].result < raintt::P){
        (*bkraintt)[i][kindex*TFHEpp::lvl1param::l+l][k][cycle*bknumbus/(TFHEpp::lvl1param::k+1)*nttwordsinbus+bus*nttwordsinbus+word] = asconprngs[k][bus][word].result;
        cycle++;
      }
    }
  for(int bus = 0; bus < bknumbus/(TFHEpp::lvl1param::k+1); bus++) for(int i = 0; i < TFHEpp::lvl0param::n; i++) for(int l = 0; l < TFHEpp::lvl1param::l; l++) for(int kindex = 0; kindex <= TFHEpp::lvl1param::k; kindex++) for(int word = 0; word<nttwordsinbus; word++)for(int cycle = 0; cycle < numcycle;){
    xoshiroprngs[bus][word].next();
    if(xoshiroprngs[bus][word].result < raintt::P){
      (*bkraintt)[i][kindex*TFHEpp::lvl1param::l+l][TFHEpp::lvl1param::k][cycle*bknumbus/(TFHEpp::lvl1param::k+1)*nttwordsinbus+bus*nttwordsinbus+word] = xoshiroprngs[bus][word].result;
      cycle++;
    }
  }
  // for(int l = 0; l < TFHEpp::lvl1param::l; l++) for(int kindex = 0; kindex <= TFHEpp::lvl1param::k; kindex++)for(int cycle = 0; cycle < numcycle; cycle++)
    // std::cout<<"bk0:"<<l<<":"<<kindex<<":"<<cycle<<":"<<(*bkraintt)[0][kindex*TFHEpp::lvl1param::l+l][0][cycle*nttnumbus*nttwordsinbus]<<std::endl;
  // for(int word = 0; word < numprng; word++)
    // std::cout<<"bk0"<<word<<":"<<(*bkraintt)[0][0][0][word]<<std::endl;
  // std::cout<<"bk01:"<<(*bkraintt)[0][0][0][nttnumbus*nttwordsinbus]<<std::endl;
  // std::cout<<"bk1:"<<(*bkraintt)[0][0][1][0]<<std::endl;
  // for(int cycle = 0; cycle < numcycle; cycle++)
  // std::cout<<"bk2"<<cycle<<":"<<(*bkraintt)[0][0][2][cycle*nttnumbus*nttwordsinbus]<<std::endl;


  std::cout<<"RUN"<<std::endl;
  dut->io_controlSPI_SS = 0;
  for(int i = 0; i < asyncqueueinit; i++) spiclock(dut, tfp);
  for(int i = 0; i < 8/spibuswidth; i++){
    dut->io_controlSPI_MOSI = (cmd_run>>(i*spibuswidth))&((1<<spibuswidth)-1);
    spiclock(dut, tfp);
  }

  for(int _ = 0; _ < 2; _++){

  std::cout<<"LOOP"<<std::endl;

  // for(int i = 0; i < 64; i++) clock(dut, tfp);
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
      }
      clock(dut, tfp);
    }
    if(errorcount!=0){
      dut->final();
      tfp->close(); 
      exit(1);
    }
  }
  dut->io_controlSPI_SS = 1;
  
  std::cout<<TFHEpp::lvl1param::k<<std::endl;
  std::cout<<"Processing"<<std::endl;
  uint count = 0;
  
constexpr typename TFHEpp::lvl0param::T roundoffset = 1ULL << (std::numeric_limits<typename TFHEpp::lvl0param::T>::digits - 2 - TFHEpp::lvl1param::nbit);
for (int nindex = 0; nindex < TFHEpp::lvl0param::n; nindex++) {
  std::cout<<nindex<<std::endl;

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
      clock(dut, tfp);
    }
    // if(errorcount!=0){
    //   dut->final();
    //   tfp->close(); 
    //   exit(1);
    // }
  }
  
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
  }
  std::cout<<"PASS"<<std::endl;
}