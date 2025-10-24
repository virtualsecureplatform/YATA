add_executable(testvectorgen testvectorgen.cpp)
target_include_directories(testvectorgen
  PUBLIC ${PROJECT_SOURCE_DIR}/thirdparties/ascon-hardware/software/ascon_ref/crypto_hash/asconhashv12/ref
)
target_link_libraries(testvectorgen PUBLIC tfhe++)