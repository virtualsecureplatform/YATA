add_executable(cpptest cpptest.cpp)
target_include_directories(cpptest
  PUBLIC ${PROJECT_SOURCE_DIR}/thirdparties/ascon-hardware/software/ascon_ref/crypto_hash/asconhashv12/ref
)
target_link_libraries(cpptest PUBLIC tfhe++)
verilate(cpptest SOURCES ../../../AXISBRWrapper.v THREADS 12 TRACE_FST)

# add_executable(bktest bktest.cpp)
# verilate(bktest SOURCES ../../../AXISBRWrapper.v THREADS 12 TRACE_FST)
# target_link_libraries(bktest PUBLIC tfhe++)

# add_executable(maxpower maxpower.cpp)
# verilate(maxpower SOURCES ../../../AXISBRWrapper.v THREADS 8 TRACE_FST)
# target_link_libraries(maxpower PUBLIC tfhe++)
# target_include_directories(maxpower
#   PUBLIC ${PROJECT_SOURCE_DIR}/thirdparties/ascon-hardware/software/ascon_ref/crypto_hash/asconhashv12/ref
# )