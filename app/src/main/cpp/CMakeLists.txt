# PRoot-Pi native PTY bridge — ported from PRoot-Pi (Apache-2.0)
cmake_minimum_required(VERSION 3.22.1)
project("term-pty" C)

add_library(term-pty SHARED term-pty.c)

target_link_libraries(term-pty
        android
        log)

target_compile_options(term-pty PRIVATE
        -Wall -Wextra -Werror -Wno-unused-parameter
        -fvisibility=hidden -fstack-protector-strong)
