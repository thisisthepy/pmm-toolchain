from . import Llama


def token_stream(token):
    delta = token["choices"][0]["delta"]
    if "content" not in delta:
        return ""
    else:
        return delta["content"]


class ChatHistory(list):
    messages = []

    @classmethod
    def remove_last(cls):
        cls.messages.pop()

    @classmethod
    def add_messages(cls, role, content):
        if isinstance(content, str):
            cls.messages.append({'role': role, 'content': content})
        else:
            for r, c in zip(role, content):
                cls.messages.append({'role': r, 'content': c})

    @classmethod
    def create_prompt(cls, system_prompt: str, user_prompt: str = ""):
        return [
            {
                "role": "system",
                "content": system_prompt
            },
            *cls.messages,
            {
                "role": "user",
                "content": user_prompt
            }
        ]


def get_llama3():
    model_id = "lmstudio-community/Meta-Llama-3.1-8B-Instruct-GGUF"

    chat = Llama.from_pretrained(
        repo_id=model_id,
        filename="*Q4_K_M.gguf",
        #chat_format="llama-3",
        verbose=False
    ).create_chat_completion

    def llama3(system_prompt, user_prompt, temp=0.5, show_prompt=False):
        prompt = ChatHistory.create_prompt(system_prompt, user_prompt)

        if show_prompt:
            print("PROMPT:")
            for line in prompt:
                print(line)
            print()

        return chat(prompt, temperature=temp, stream=True)

    return llama3
