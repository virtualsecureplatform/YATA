#include <keyswitch.hpp>

namespace TFHEpp {

#define INST(P)                                                               \
    template void IdentityKeySwitch<P>(TLWE<typename P::targetP> & res,       \
                                       const TLWE<typename P::domainP> &tlwe, \
                                       const KeySwitchingKey<P> &ksk)
TFHEPP_EXPLICIT_INSTANTIATION_KEY_SWITCH_TO_TLWE(INST)
#undef INST

#define INST(P)                                \
    template void SubsetIdentityKeySwitch<P>(  \
        TLWE<typename P::targetP> & res,       \
        const TLWE<typename P::domainP> &tlwe, \
        const SubsetKeySwitchingKey<P> &ksk)
TFHEPP_EXPLICIT_INSTANTIATION_KEY_SWITCH_TO_TLWE(INST)
#undef INST

#define INST(P)                                                           \
    template void TLWE2TRLWEIKS<P>(TRLWE<typename P::targetP> & res,      \
                                   const TLWE<typename P::domainP> &tlwe, \
                                   const TLWE2TRLWEIKSKey<P> &iksk)
TFHEPP_EXPLICIT_INSTANTIATION_KEY_SWITCH_TO_TRLWE(INST)
#undef INST

#define INST(P)                                                      \
    template void EvalAuto<P>(TRLWE<P> & res, const TRLWE<P> &trlwe, \
                              const int d, const TRGSWFFT<P> &autokey)
TFHEPP_EXPLICIT_INSTANTIATION_TRLWE(INST)
#undef INST

#define INST(P)                              \
    template void AnnihilateKeySwitching<P>( \
        TRLWE<P> & res, const TRLWE<P> &trlwe, const AnnihilateKey<P> &ahk)
TFHEPP_EXPLICIT_INSTANTIATION_TRLWE(INST)
#undef INST

#define INST(P)                                                           \
    template void PrivKeySwitch<P>(TRLWE<typename P::targetP> & res,      \
                                   const TLWE<typename P::domainP> &tlwe, \
                                   const PrivateKeySwitchingKey<P> &privksk)
TFHEPP_EXPLICIT_INSTANTIATION_KEY_SWITCH_TO_TRLWE(INST)
#undef INST

#define INST(P)                                \
    template void SubsetPrivKeySwitch<P>(      \
        TRLWE<typename P::targetP> & res,      \
        const TLWE<typename P::targetP> &tlwe, \
        const SubsetPrivateKeySwitchingKey<P> &privksk)
TFHEPP_EXPLICIT_INSTANTIATION_KEY_SWITCH_TO_TRLWE(INST)
#undef INST

}  // namespace TFHEpp
