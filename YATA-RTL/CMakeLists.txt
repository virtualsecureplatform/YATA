project(yata-test CXX C)
cmake_minimum_required(VERSION 3.16)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_FLAGS "-march=native -O3 -g")
# set(CMAKE_INTERPROCEDURAL_OPTIMIZATION TRUE)

find_package(OpenMP)
if(OpenMP_FOUND)
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${OpenMP_CXX_FLAGS}")
endif()

# add_compile_definitions(USE_CONCRETE)
add_compile_definitions(USE_COMPRESS)
add_subdirectory(thirdparties/TFHEpp)
if(TFHEpp_DEFINITIONS)
  foreach(DEF IN LISTS TFHEpp_DEFINITIONS)
    add_compile_definitions(${DEF})
  endforeach()
endif()

add_subdirectory(chisel)

add_subdirectory(testvectorgen)
