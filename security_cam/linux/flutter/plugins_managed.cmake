#
# Managed plugin build rules for the Linux desktop target.
#
# `tflite_flutter` is deliberately excluded from the Linux build: its CMake
# target `flutter_tflite_plugin` collides with `flutter_litert` (used by face
# detection), and YAMNet audio (the only tflite_flutter consumer) is
# Android-only — the desktop audio classifier is a mock.
#
# This mirrors the plugin wiring of Flutter's generated
# `generated_plugins.cmake`, which the tool rewrites on every build (so a
# hand-edit there does not survive). If a new Linux plugin is added to the
# project, add it to the appropriate list below.

list(APPEND FLUTTER_PLUGIN_LIST
  face_detection_tflite
  flutter_secure_storage_linux
  record_linux
)

list(APPEND FLUTTER_FFI_PLUGIN_LIST
  flutter_litert
  jni
)

set(PLUGIN_BUNDLED_LIBRARIES)

foreach(plugin ${FLUTTER_PLUGIN_LIST})
  add_subdirectory(flutter/ephemeral/.plugin_symlinks/${plugin}/linux plugins/${plugin})
  target_link_libraries(${BINARY_NAME} PRIVATE ${plugin}_plugin)
  list(APPEND PLUGIN_BUNDLED_LIBRARIES $<TARGET_FILE:${plugin}_plugin>)
  list(APPEND PLUGIN_BUNDLED_LIBRARIES ${${plugin}_bundled_libraries})
endforeach(plugin)

foreach(ffi_plugin ${FLUTTER_FFI_PLUGIN_LIST})
  add_subdirectory(flutter/ephemeral/.plugin_symlinks/${ffi_plugin}/linux plugins/${ffi_plugin})
  list(APPEND PLUGIN_BUNDLED_LIBRARIES ${${ffi_plugin}_bundled_libraries})
endforeach(ffi_plugin)