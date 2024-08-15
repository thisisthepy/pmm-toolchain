import os
import ctypes

assets_path = os.path.dirname(os.path.dirname(os.path.abspath(os.path.dirname(__file__))))
llama_cpp_lib_path = os.path.join(assets_path, "requirements", "llama_cpp", "lib")


import numpy  # Or any requirement other than llama_cpp
numpy.__loader__.finder.extract_if_changed("llama_cpp/lib/libllama.so")
numpy.__loader__.finder.extract_if_changed("llama_cpp/lib/libggml.so")
numpy.__loader__.finder.extract_if_changed("llama_cpp/lib/libllava.so")

ctypes.CDLL(os.path.join(llama_cpp_lib_path, "libggml.so"))

from llama_cpp import *
