#
# For armhf and arm64: Download precompiled libraries and set up
# linking
# Also setup preprocessor definitions
#
cmake_minimum_required(VERSION 3.1)

set(CMAKE_SKIP_RPATH true)

# Make sure we have downloaded and unpacked master.zip
# OCPN_ANDROID_CACHEDIR may be preset on the cmake command line (or via env)
# to share one download across build trees.
if (NOT OCPN_ANDROID_CACHEDIR)
  set(
    OCPN_ANDROID_CACHEDIR "${CMAKE_SOURCE_DIR}/cache"
    CACHE STRING "Build download area"
  )
endif ()
set(_master_base ${OCPN_ANDROID_CACHEDIR}/OCPNAndroidCoreBuildSupport)
message(STATUS "Android Build support file base:  ${OCPN_ANDROID_CACHEDIR}/OCPNAndroidCoreBuildSupport")


# Download once; re-use a valid cached copy on later configures. The zip is
# ~311 MB, so blindly re-downloading on every configure is wasteful.
set(_support_zip ${OCPN_ANDROID_CACHEDIR}/support.zip)
set(_need_dl TRUE)
if (EXISTS ${_support_zip})
  file(SIZE ${_support_zip} _zip_size)
  # v1.2 asset is exactly 326050052 bytes; accept anything within 1 MB so a
  # future re-release does not silently reuse a stale file.
  if (_zip_size GREATER 325000000 AND _zip_size LESS 327000000)
    set(_need_dl FALSE)
    message(STATUS "Reusing cached Android support libs: ${_support_zip}")
  else ()
    message(STATUS "Cached support.zip has unexpected size (${_zip_size}), re-downloading")
  endif ()
endif ()
if (_need_dl)
  file(MAKE_DIRECTORY ${OCPN_ANDROID_CACHEDIR})
  file(
    DOWNLOAD
      https://github.com/bdbcat/OCPNAndroidCoreBuildSupport/releases/download/v1.2/OCPNAndroidCoreBuildSupport.zip
      ${_support_zip}
    SHOW_PROGRESS
  )
endif ()
# Extract once; a sentinel file marks a completed extraction.
set(_extract_sentinel ${_master_base}/.extracted_ok)
if (NOT EXISTS ${_extract_sentinel})
  execute_process(
    COMMAND ${CMAKE_COMMAND} -E tar -xzf ${_support_zip}
    WORKING_DIRECTORY "${OCPN_ANDROID_CACHEDIR}"
    RESULT_VARIABLE _tar_result
  )
  if (NOT _tar_result EQUAL 0)
    message(FATAL_ERROR "Failed to extract Android support libs from ${_support_zip}")
  endif ()
  file(TOUCH ${_extract_sentinel})
endif ()

# Setup directories and libraries
if ("${OCPN_TARGET_TUPLE}" MATCHES "Android-arm64")
  file(GLOB _wx_setup
    ${_master_base}/wxWidgets/libs/arm64/lib/wx/include/arm-linux-*-static-*
  )
  set(_qt_include  ${_master_base}/qt5/build_arm64_O3/qtbase/include)
  set(_qtlibs  ${_master_base}/qt5/build_arm64_O3/qtbase/lib)
  set(_wxlibs  ${_master_base}/wxWidgets/libs/arm64/lib)
  set(Qt_Base ${_master_base}/qt5)
  set(Qt_Build build_arm64_O3/qtbase)
  set(openssl_include ${_master_base}/openssl/arm64/include)

else ()
  file(GLOB _wx_setup
    ${_master_base}/wxWidgets/libs/armhf/lib/wx/include/arm-linux-*-static-*
  )
  set(_qt_include ${_master_base}/qt5/build_arm32_19_O3/qtbase/include)
  set(_qtlibs  ${_master_base}/qt5/build_arm32_19_O3/qtbase/lib)
  set(_wxlibs  ${_master_base}/wxWidgets/libs/armhf/lib)
  set(Qt_Base ${_master_base}/qt5)
  set(Qt_Build build_arm32_19_O3/qtbase)
  set(openssl_include ${_master_base}/openssl/armhf/include)
endif ()

message(STATUS "Android Build wx include directories: support file base:  ${_wx_setup}")

include_directories(
  ${base_include}
  ${_qt_include}
  ${_qt_include}/QtWidgets
  ${_qt_include}/QtCore
  ${_qt_include}/QtGui
  ${_qt_include}/QtOpenGL
  ${_qt_include}/QtTest
  ${_master_base}/wxWidgets/include/
  ${_wx_setup}
)
target_link_libraries(${PACKAGE_NAME} PRIVATE
  ${_qtlibs}/libQt5Core.so
  ${_qtlibs}/libQt5OpenGL.so
  ${_qtlibs}/libQt5Widgets.so
  ${_qtlibs}/libQt5Gui.so
  ${_qtlibs}/libQt5AndroidExtras.so
)

set(_all_wx_libs
    # Link order is critical to avoid circular dependencies
    ${_wxlibs}/libwx_qtu_html-3.1-arm-linux-androideabi.a
    ${_wxlibs}/libwx_baseu_xml-3.1-arm-linux-androideabi.a
    ${_wxlibs}/libwx_qtu_qa-3.1-arm-linux-androideabi.a
    ${_wxlibs}/libwx_qtu_adv-3.1-arm-linux-androideabi.a
    ${_wxlibs}/libwx_qtu_core-3.1-arm-linux-androideabi.a
    ${_wxlibs}/libwx_baseu-3.1-arm-linux-androideabi.a
    ${_wxlibs}/libwx_qtu_aui-3.1-arm-linux-androideabi.a
    ${_wxlibs}/libwxexpat-3.1-arm-linux-androideabi.a
    ${_wxlibs}/libwxregexu-3.1-arm-linux-androideabi.a
    ${_wxlibs}/libwxjpeg-3.1-arm-linux-androideabi.a
    ${_wxlibs}/libwxpng-3.1-arm-linux-androideabi.a
    ${_wxlibs}/libwx_qtu_gl-3.1-arm-linux-androideabi.a
    ${_wxlibs}/libwx_baseu_net-3.1-arm-linux-androideabi.a
)
target_link_libraries(${PACKAGE_NAME} PRIVATE ${_all_wx_libs})

if ("${OCPN_TARGET_TUPLE}" MATCHES "Android-armhf")
  target_link_libraries(${PACKAGE_NAME} PRIVATE "${CMAKE_SOURCE_DIR}/buildandroid/ndk/linux-atomic.o")
endif ()

add_compile_definitions(
  __WXQT__
  __OCPN__ANDROID__
  ocpnUSE_GLES
  ocpnUSE_GL
  USE_ANDROID_GLES2
  USE_GLSL
  USE_GLU_TESS
)

set(OPENGLES_FOUND "YES")
set(OPENGL_FOUND "YES")
set(USE_GLES2 ON )

set(ANDROID_WX_INCLUDES ${_wx_setup})
set(ANDROID_WX_LIBS ${_all_wx_libs})

#if (NOT CMAKE_BUILD_TYPE STREQUAL Debug)
#  string(APPEND CMAKE_SHARED_LINKER_FLAGS " -s")
#endif ()
