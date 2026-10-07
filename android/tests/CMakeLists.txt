# android/tests/CMakeLists.txt — host-only unit tests for Android chart/storage
# logic. Pure C++17, no wx/Qt/Android dependencies. Built on desktop CI and
# developer machines; never part of the Android cross-build.
cmake_minimum_required(VERSION 3.16)

enable_testing()

add_executable(android_unit_tests tests.cpp)
target_include_directories(android_unit_tests PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_features(android_unit_tests PRIVATE cxx_std_17)
if (CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
  target_compile_options(android_unit_tests PRIVATE -Wall -Wextra)
endif ()

add_test(NAME android_unit_tests COMMAND android_unit_tests)
