from pycomposeui.runtime import Composable, EmptyComposable, remember_saveable
from pycomposeui.material3 import SimpleText, SimpleColumn, SimpleRow, SimpleButton
from pycomposeui.ui import modifier, Alignment

from java import jclass
import traceback


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
