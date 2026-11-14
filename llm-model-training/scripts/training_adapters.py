"""Render assistant-only Qwen training turns with the publisher's non-thinking template."""


def render_training_turn(tokenizer, messages, config):
    prefix = tokenizer.apply_chat_template(messages[:-1], tokenize=False, add_generation_prompt=True, enable_thinking=False)
    full = tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=False, enable_thinking=False)
    if not full.startswith(prefix):
        raise ValueError("Tokenizer template prefix differs from training turn; review the pinned non-thinking template")
    return prefix, full
