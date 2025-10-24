#include <bits/stdint-uintn.h>
#include <tfhe++.hpp>
#include <permutations.h>
#include <fstream>
#include <format>

constexpr uint32_t wordmask = (1U<<(raintt::wordbits-1))-1;
struct xoshiro128{
  std::array<uint32_t,4> s;
  uint32_t result;

  static inline uint32_t rotl(const uint32_t x, int k) {
    return (x << k) | (x >> (32 - k));
  }
  void next(void) {
    result = (rotl(s[1] * 5, 7) * 9)&wordmask;

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
      result = S.x0&wordmask;
      index = 1;
    }else{
      index = 0;
      result = (S.x0>>32)&wordmask;    
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

  //Compress Ver.
  std::cout<<"Generating Seed"<<std::endl;
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
  std::cout<<"xoshrioseed:"<<std::hex<<xoshiroprngs[0][0].s[0]<<std::endl;


  std::cout<<"Seed Write"<<std::endl;
  for(int k = 0; k < TFHEpp::lvl1param::k; k++){
    std::ofstream seed_file;
    seed_file.open("asconseed"+std::to_string(k)+".tv", std::ios::out);
    for(int i = 0; i < nttnumbus; i++){
      for(int j = 0; j < numprng; j++){
        seed_file<<std::format("{:016x}",asconprngs[k][i][j].S.x4)<<std::format("{:016x}",asconprngs[k][i][j].S.x3)<<std::format("{:016x}",asconprngs[k][i][j].S.x2)<<std::format("{:016x}",asconprngs[k][i][j].S.x1)<<std::format("{:016x}",asconprngs[k][i][j].S.x0)<<std::endl;
      }
    }
  }
  {
  std::ofstream seed_file;
  seed_file.open("xoshiroseed.tv", std::ios::out);
  for(int i = 0; i < nttnumbus; i++){
    for(int j = 0; j < numprng; j++){
        for(int k = 3; k >= 0; k--)
            seed_file<<std::format("{:08x}",xoshiroprngs[i][j].s[k]);
        seed_file<<std::endl;
    }
  }
  }
  {
  // std::ofstream bk_file;
  // bk_file.open("bk_rand.tv", std::ios::out);
  for(int k = 0; k < TFHEpp::lvl1param::k; k++) 
    for(int bus = 0; bus < bknumbus/(TFHEpp::lvl1param::k+1); bus++) for(int i = 0; i < TFHEpp::lvl0param::n; i++) for(int l = 0; l < TFHEpp::lvl1param::l; l++) for(int kindex = 0; kindex <= TFHEpp::lvl1param::k; kindex++) for(int word = 0; word<nttwordsinbus; word++)for(int cycle = 0; cycle < numcycle;){
      asconprngs[k][bus][word].next();
      if((asconprngs[k][bus][word].result) < raintt::P){
        (*bkraintt)[i][kindex*TFHEpp::lvl1param::l+l][k][cycle*bknumbus/(TFHEpp::lvl1param::k+1)*nttwordsinbus+bus*nttwordsinbus+word] = asconprngs[k][bus][word].result;
        // if((word==0)&&(bus==0)) bk_file <<std::hex<< (uint64_t)((*bkraintt)[i][kindex*TFHEpp::lvl1param::l+l][k][cycle*bknumbus/(TFHEpp::lvl1param::k+1)*nttwordsinbus+bus*nttwordsinbus+word]) << std::endl;
        cycle++;
      }
    }
  }
  {
  // std::ofstream xoshiro_file;
  // xoshiro_file.open("xoshiro.tv", std::ios::out);
  for(int bus = 0; bus < bknumbus/(TFHEpp::lvl1param::k+1); bus++) for(int i = 0; i < TFHEpp::lvl0param::n; i++) for(int l = 0; l < TFHEpp::lvl1param::l; l++) for(int kindex = 0; kindex <= TFHEpp::lvl1param::k; kindex++) for(int word = 0; word<nttwordsinbus; word++)for(int cycle = 0; cycle < numcycle;){
    xoshiroprngs[bus][word].next();
    if((xoshiroprngs[bus][word].result) < raintt::P){
      (*bkraintt)[i][kindex*TFHEpp::lvl1param::l+l][TFHEpp::lvl1param::k][cycle*bknumbus/(TFHEpp::lvl1param::k+1)*nttwordsinbus+bus*nttwordsinbus+word] = xoshiroprngs[bus][word].result;
      // if((word==0)&&(bus==0)) xoshiro_file <<std::hex<< (uint64_t)((*bkraintt)[i][kindex*TFHEpp::lvl1param::l+l][TFHEpp::lvl1param::k][cycle*bknumbus/(TFHEpp::lvl1param::k+1)*nttwordsinbus+bus*nttwordsinbus+word]) << std::endl;
      cycle++;
    }
  }
  }

  for(int l = 0; l < TFHEpp::lvl1param::l; l++) for(int kindex = 0; kindex <= TFHEpp::lvl1param::k; kindex++)for(int cycle = 0; cycle < numcycle; cycle++)
  std::cout<<std::hex<<"bk0:"<<l<<":"<<kindex<<":"<<cycle<<":"<<static_cast<int32_t>((*bkraintt)[0][kindex*TFHEpp::lvl1param::l+l][0][cycle*nttnumbus*nttwordsinbus])<<std::endl;
  // for(int word = 0; word < numprng; word++)
    // std::cout<<"bk0"<<word<<":"<<(*bkraintt)[0][0][0][word]<<std::endl;
  // std::cout<<"bk01:"<<(*bkraintt)[0][0][0][nttnumbus*nttwordsinbus]<<std::endl;
  std::cout<<"bk1:"<<static_cast<int32_t>((*bkraintt)[0][0][1][0])<<std::endl;
  for(int cycle = 0; cycle < numcycle; cycle++)
  std::cout<<"bk2"<<cycle<<":"<<static_cast<int32_t>((*bkraintt)[0][0][2][cycle*nttnumbus*nttwordsinbus])<<std::endl;

  bool p = (binary(engine) > 0);
  const TFHEpp::TLWE<TFHEpp::lvl0param> tlwe = TFHEpp::tlweSymEncrypt<TFHEpp::lvl0param>(p,TFHEpp::lvl0param::α,sk->key.lvl0);
  {
  std::cout<<"TLEW Input write"<<std::endl;
  std::ofstream input_file;
  input_file.open("input.tv", std::ios::out);
  for(int i = 0; i <= TFHEpp::lvl0param::n; i++) input_file<<std::hex<<tlwe[i]<<std::endl;
  }
  const uint32_t b̄ =
    2 * TFHEpp::lvl1param::n - (tlwe[TFHEpp::lvl0param::n] >>(std::numeric_limits<typename TFHEpp::lvl0param::T>::digits - 1 - TFHEpp::lvl1param::nbit));
  constexpr typename TFHEpp::lvl0param::T roundoffset = 1ULL << (std::numeric_limits<typename TFHEpp::lvl0param::T>::digits - 2 - TFHEpp::lvl1param::nbit);
  {
    TFHEpp::TRLWE<TFHEpp::lvl1param> trlwe;
    trlwe = {};
    TFHEpp::PolynomialMulByXai<TFHEpp::lvl1param>(trlwe[TFHEpp::lvl1param::k],TFHEpp::μpolygen<TFHEpp::lvl1param,TFHEpp::lvl1param::μ>(),b̄);

    {
    std::ofstream intermediate_file;
    intermediate_file.open("compress_intermediate.tv", std::ios::out);
    for (int nindex = 0; nindex < TFHEpp::lvl0param::n; nindex++) {
      const uint32_t ā = (tlwe[nindex]+roundoffset)>>(std::numeric_limits<typename TFHEpp::lvl0param::T>::digits - 1 - TFHEpp::lvl1param::nbit);

      // if (ā == 0) continue;
      // Do not use CMUXNTT to avoid unnecessary copy.
      TFHEpp::CMUXRAINTTwithPolynomialMulByXaiMinusOne<TFHEpp::lvl1param>(
          trlwe, (*bkraintt)[nindex], ā);
      intermediate_file<<std::hex<<trlwe[0][0]<<std::endl;
    }
    }

    TFHEpp::BlindRotate<TFHEpp::lvl01param>(trlwe,tlwe,*bkraintt,TFHEpp::μpolygen<TFHEpp::lvl1param, TFHEpp::lvl1param::μ>());

    {
    std::ofstream output_file;
    output_file.open("compress_output.tv", std::ios::out);
    for(int i = 0; i <= TFHEpp::lvl1param::k; i++){
      for(int cycle = 0; cycle<numcycle;cycle++){
        for(int bus = 0; bus < trlwenumbus; bus++){
          for(int m = 0; m<nttsize/trlwenumbus;m++){
            output_file<<std::hex<<trlwe[i][(bus*nttsize/trlwenumbus+m)*numcycle+cycle]<<std::endl;
          }
        }
      }
    }
    }
  }

  //Precise Ver.
  std::cout<<"BK gen"<<std::endl;
  TFHEpp::bkrainttgen<TFHEpp::lvl01param>(*bkraintt,*sk);
  for(int i = 0; i < TFHEpp::lvl0param::k*TFHEpp::lvl0param::n; i++) for(int l = 0; l < TFHEpp::lvl1param::l*(TFHEpp::lvl1param::k+1);l++) for(int k = 0; k < TFHEpp::lvl1param::k+1; k++) for(int j = 0; j < TFHEpp::lvl1param::n; j++)
    (*bkraintt)[i][l][k][j] = ((*bkraintt)[i][l][k][j] % raintt::P + raintt::P) % raintt::P;

  std::cout<<"Writing BK"<<std::endl;
  for(int k = 0; k < TFHEpp::lvl1param::k+1; k++){
    std::ofstream bk_file;
    bk_file.open("bkraintt"+std::to_string(k)+".tv", std::ios::out);
    for(int i = 0; i < TFHEpp::lvl0param::n*TFHEpp::lvl0param::k; i++) for(int l = 0; l < TFHEpp::lvl1param::l; l++) for(int kindex = 0; kindex <= TFHEpp::lvl1param::k; kindex++) for(int cycle = 0; cycle < numcycle; cycle++) for(int bus = 0; bus < bknumbus/(TFHEpp::lvl1param::k+1); bus++) for(int word = 0; word<nttwordsinbus; word++) 
      bk_file<<std::format("{:04x}",(uint64_t)((*bkraintt)[i][kindex*TFHEpp::lvl1param::l+l][k][cycle*bknumbus/(TFHEpp::lvl1param::k+1)*nttwordsinbus+bus*nttwordsinbus+word]))<<std::endl;
  }

  TFHEpp::TRLWE<TFHEpp::lvl1param> trlwe;
  trlwe = {};
  TFHEpp::PolynomialMulByXai<TFHEpp::lvl1param>(trlwe[TFHEpp::lvl1param::k],TFHEpp::μpolygen<TFHEpp::lvl1param,TFHEpp::lvl1param::μ>(),b̄);
  {
  std::ofstream intermediate_file;
  intermediate_file.open("precise_intermediate.tv", std::ios::out);
  for (int nindex = 0; nindex < TFHEpp::lvl0param::n; nindex++) {
    const uint32_t ā = (tlwe[nindex]+roundoffset)>>(std::numeric_limits<typename TFHEpp::lvl0param::T>::digits - 1 - TFHEpp::lvl1param::nbit);

    // if (ā == 0) continue;
    // Do not use CMUXNTT to avoid unnecessary copy.
    TFHEpp::CMUXRAINTTwithPolynomialMulByXaiMinusOne<TFHEpp::lvl1param>(
        trlwe, (*bkraintt)[nindex], ā);
    intermediate_file<<std::hex<<trlwe[0][0]<<std::endl;
  }
  }

  TFHEpp::BlindRotate<TFHEpp::lvl01param>(trlwe,tlwe,*bkraintt,TFHEpp::μpolygen<TFHEpp::lvl1param, TFHEpp::lvl1param::μ>());

  {
  std::ofstream output_file;
  output_file.open("precise_output.tv", std::ios::out);
  for(int i = 0; i <= TFHEpp::lvl1param::k; i++){
    for(int cycle = 0; cycle<numcycle;cycle++){
      for(int bus = 0; bus < trlwenumbus; bus++){
        for(int m = 0; m<nttsize/trlwenumbus;m++){
          output_file<<std::hex<<trlwe[i][(bus*nttsize/trlwenumbus+m)*numcycle+cycle]<<std::endl;
        }
      }
    }
  }
  }
}