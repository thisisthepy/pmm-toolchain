from pycomposeui.runtime import Composable, EmptyComposable, remember_saveable
from pycomposeui.material3 import SimpleText, SimpleColumn, SimpleRow, SimpleButton
from pycomposeui.ui import modifier, Alignment

from java import jclass
import traceback


import os
import sys
import zipfile
import ctypes
print(os.environ["HOME"])
print(os.path.abspath(os.path.dirname(__file__)))
print(sys.platform)

import numpy  # Or any requirement other than llama_cpp
numpy.__loader__.finder.extract_if_changed("llama_cpp/lib/libllama.so")
numpy.__loader__.finder.extract_if_changed("llama_cpp/lib/libggml.so")
numpy.__loader__.finder.extract_if_changed("llama_cpp/lib/libllava.so")
numpy.__loader__.finder.extract_if_changed("lib/libllama.so")
numpy.__loader__.finder.extract_if_changed("lib/libggml.so")
numpy.__loader__.finder.extract_if_changed("lib/libllava.so")

base_path = "/data/data/io.github.thisisthepy.pythonapptemplate/files/chaquopy/AssetFinder/requirements"

ggml_path = os.path.join(f"{base_path}/lib", "libggml.so")
llama_path = os.path.join(f"{base_path}/lib", "libllama.so")

ctypes.CDLL(ggml_path)
ctypes.CDLL(llama_path)

# ggml_path = os.path.join(f"{base_path}/llama_cpp/lib", "libggml.so")
# llama_path = os.path.join(f"{base_path}/llama_cpp/lib", "libllama.so")
#
# ctypes.CDLL(ggml_path)
# ctypes.CDLL(llama_path)

from llama_cpp import llama

@Composable
def UiTestCase(text: str = "UiTestCase"):
    SimpleText(text)


@Composable
class UiTest:
    def compose(self, content: Composable = EmptyComposable):
        SimpleColumn(modifier, content=lambda: {
            UiTestCase(text="UiTestCase in UiTest"),
            content()
        })


@Composable
class BasicText:
    @classmethod
    def compose(cls, text: str = "BasicText"):
        SimpleText(text)


@Composable
class RichText(Composable):
    @staticmethod
    def compose(content: Composable = EmptyComposable):
        SimpleColumn(modifier, content=Composable(lambda: {
            BasicText("Basic Text inside of Rich Text"),
            SimpleRow(lambda: {
                BasicText("Row Left Side  "),
                BasicText("Row Right Side")
            }),
            content()
        }))


@Composable
class App(Composable):
    @staticmethod
    def compose():
        hi = remember_saveable("Hi?")
        count = remember_saveable(0)

        SimpleColumn(modifier, content=lambda: {
            UiTest(),
            RichText(),
            SimpleText(hi.getValue()),
            SimpleButton(
                onclick=lambda: {
                    print("Button1 clicked!!!!!!!"),
                    hi.setValue(hi.getValue() + " Hi?")
                },
                content=lambda: {
                    SimpleText("Button 1")
                }
            ),
            SimpleButton(
                onclick=lambda: {
                    print("Button2 clicked!!!!!!!"),
                    count.setValue(count.getValue() + 1)
                },
                content=lambda: {
                    SimpleText(f"Button 2: Clicked {count.getValue()}")
                }
            )
        })
