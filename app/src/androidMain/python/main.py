from pycomposeui.runtime import Composable, EmptyComposable, remember_saveable
from pycomposeui.runtime import DefaultCoroutineScope, MainCoroutineScope
from pycomposeui.material3 import SimpleText, SimpleColumn, SimpleRow, SimpleButton
from pycomposeui.ui import modifier, Alignment

from llm.llama import get_llama3, token_stream

from java import jclass, detach


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
        messages = remember_saveable("")
        status = remember_saveable("")
        count = remember_saveable(0)

        scope = DefaultCoroutineScope()
        main_scope = MainCoroutineScope()

        system_prompt = "You are a helpful, smart, kind, and efficient AI Assistant. You always fulfill the user's requests to the best of your ability. You need to keep listen to the conversations. Please answer in Korean language."
        user_prompt = remember_saveable("안녕하세요!")

        def change_prompt(prompt: str):
            user_prompt.setValue(prompt)

        llama3 = None

        def init_llama3():
            def runner():
                nonlocal llama3
                llama3 = get_llama3()
                print_state("Llama3 Initialized")

            scope.launch(runner)

        def print_state(text: str):
            status.setValue(status.getValue() + "  " + text)

        def print_messages(text: str):
            messages.setValue(messages.getValue() + text)

        def run_llama3(printer: callable = lambda x: print(x, end="", flush=True)):
            system = system_prompt
            user = user_prompt.getValue()

            if llama3 is None:
                print_state("Llama3 Not Initialized!!")
            else:
                print_state("Inference...")

                def runner():
                    for chunk in llama3(system, user):
                        printer(token_stream(chunk))
                    printer("\n")

                scope.launch(runner)

        SimpleColumn(modifier, content=lambda: {
            #UiTest(),
            #RichText(),
            SimpleText(f"Current User Prompt: {user_prompt.getValue()}"),
            SimpleText(f"Log: {status.getValue()}"),
            SimpleText(""),
            SimpleText(messages.getValue()),
            SimpleButton(
                onclick=init_llama3,
                content=lambda: {
                    SimpleText("Init Llama3")
                }
            ),
            SimpleButton(
                onclick=lambda: run_llama3(print_messages),
                content=lambda: {
                    SimpleText(f"Send User Prompt")
                }
            ),
            SimpleButton(
                onclick=lambda: {
                    change_prompt("오늘 날씨는 어때요?")
                },
                content=lambda: {
                    SimpleText(f"Change Prompt")
                }
            )
        })
