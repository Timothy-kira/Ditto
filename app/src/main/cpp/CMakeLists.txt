cmake_minimum_required(VERSION 3.22.1)
project(ditto_oboe LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(ditto_oboe SHARED oboe_mic.cpp)

find_package(oboe REQUIRED CONFIG)
find_library(log-lib log)

target_compile_options(ditto_oboe PRIVATE -Wall -Wextra)
target_link_options(ditto_oboe PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(ditto_oboe
    oboe::oboe
    android
    ${log-lib}
)
