from pycomposeui.runtime import Composable, EmptyComposable, remember_saveable, CoroutineScope
from pycomposeui.material3 import SimpleText, SimpleColumn, SimpleRow, SimpleButton
from pycomposeui.ui import modifier, Alignment

from llm.llama import get_llama3, token_stream

from java import jclass


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

        scope = CoroutineScope()

        system_prompt = "You are a helpful, smart, kind, and efficient AI Assistant. You always fulfill the user's requests to the best of your ability. You need to keep listen to the conversations. Please answer in Korean language."
        user_prompt = remember_saveable("안녕하세요!")

        def change_prompt(prompt: str):
            user_prompt.setValue(prompt)

        llama3 = None

        def init_llama3():
            nonlocal llama3
            llama3 = get_llama3()

        def print_state(text: str):
            status.setValue(status.getValue() + " " + text)

        def print_messages(text: str):
            messages.setValue(messages.getValue() + " " + text)

        def get_recommendation(printer: callable = lambda x: print(x, end="", flush=True)):
            runner_scope = CoroutineScope()

            def runner():
                for chunk in llama3(system_prompt, user_prompt.getValue()):
                    chunk = token_stream(chunk)
                    print(chunk)
                    runner_scope.launch(lambda: printer(chunk))

            scope.launch(runner)

        SimpleColumn(modifier, content=lambda: {
            #UiTest(),
            #RichText(),
            SimpleText(messages.getValue()),
            SimpleText(user_prompt.getValue()),
            SimpleText(status.getValue()),
            SimpleButton(
                onclick=lambda: {
                    init_llama3(),
                    print_state("Llama3 Initialized")
                },
                content=lambda: {
                    SimpleText("Init Llama3")
                }
            ),
            SimpleButton(
                onclick=lambda: {
                    print_state("Getting Recommendation"),
                    get_recommendation(print_messages),
                    print_state("Coroutine Registration Finished.")
                },
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
