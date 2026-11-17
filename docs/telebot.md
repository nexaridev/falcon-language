# Telegram bots

`libraries/telebot.fc` is a TeleBot-inspired optional module for Falcon 0.6+. It uses Telegram's HTTPS Bot API directly. Its function names resemble Python's pyTelegramBotAPI, but Falcon currently uses handles and explicit polling instead of Python objects, decorators, callbacks, or asynchronous handlers.

## Run the example

Create a bot using Telegram's official @BotFather and put its token in your shell's environment. From the Falcon project folder:

```powershell
$env:TELEGRAM_BOT_TOKEN = 'your BotFather token'
falcon run examples/telegram_bot.fc --max-steps 1000000000
```

The example replies to `/start`, echoes text (including Cyrillic), acknowledges inline-button callbacks, and writes timestamped logs to `bot.log` in the current directory. Ctrl+C stops it. No Telegram messages are sent merely by importing or checking the module. The example contacts Telegram only when you run it with a token.

If an existing webhook is set, polling will not work. Inspect it with `get_webhook_info`; explicitly call `delete_webhook(bot, false)` if you want to switch that bot to polling. The library never deletes webhooks or discards pending updates automatically. Run only one poller for a token.

## Request results and errors

Create a handle with `TeleBot(token)`. HTTP helpers return a map with these fields:

| Field | Meaning |
| --- | --- |
| `ok` | Whether the request succeeded |
| `result` | Telegram's result on success; null on failure |
| `error_code` | Telegram/HTTP error code, or 0 for transport, upload, or malformed-response errors |
| `description` | Error description with the bot token redacted |
| `retry_after` | Telegram's requested delay in seconds, or 0 |

Use `ok(response)`, `result(response)`, `error_code(response)`, `error(response)`, and `retry_after(response)` to access these fields. Network errors are returned, so Falcon's lack of catchable exceptions does not crash a polling loop. Invalid handles or arguments are still runtime errors. There are no automatic resends: a lost response to `send_message` may mean the message was delivered, so blindly retrying can duplicate it.

```fc
fix response = telebot.send_message(bot, chatId, "Hello!")
if (!telebot.ok(response)) {
    telebot.log(bot, telebot.error(response))
}
```

`poll(bot, timeoutSeconds)` remembers an in-memory offset and requests message/callback updates. Process the complete successful batch before calling it again: the next call acknowledges the previous batch. Failed or malformed batches do not advance the offset. The timeout is 0–50 seconds; 25 is a reasonable example value. Offsets are not persisted across process restarts; delivery is not exactly-once. `get_updates(bot, offset, timeoutSeconds)` lets you manage offsets yourself; do not mix it with automatic `poll` for one bot. Keep manual polling timeouts at 50 seconds or less.

`updates(response)` returns a list (empty on failure); check `ok` first to distinguish errors from an empty batch. Use `collections.asMap(item)`, `has_message(update)`, `message(update)`, `chat_id(message)`, and `text(message)` to read messages. `text` returns an empty string for non-text messages. `command` extracts a leading slash command and strips a possible `@botname` suffix; it does not verify whether that suffix addresses your bot. Applications in group chats should check the original text/Telegram command entities when targeting matters.

## Messages, keyboards, and uploads

`send_message`, `reply_to`, `edit_message_text`, `delete_message`, `send_photo`, and `send_document` provide common operations. Chat IDs accept integer IDs or strings such as `@channelname`. Upload helpers take a local file path and caption; they use multipart upload and have a library limit of 20 MiB. Telegram's method-specific limits still apply. For an existing Telegram file ID or URL, use `request` with the appropriate API parameters instead.

Use `send_message_with(bot, chatId, value, options)` for `parse_mode`, `reply_markup`, and other sendMessage options. It copies options rather than modifying your map. `reply_to` uses Telegram's `reply_parameters` field.

```fc
use "lib/telebot.fc" as telebot
use collections as c

// Inside a function, with bot and chatId already defined:
fix keyboard = telebot.inline_keyboard([[telebot.button("Hello", "hello")]])
fix options = c.newMap()
c.put(options, "reply_markup", keyboard)
telebot.send_message_with(bot, chatId, "Choose a button", options)
```

Handle the `callback_query` update and call `answer_callback_query` using its string `id`. Button data and markup are checked by Telegram. To call other Bot API methods, use `request(bot, "methodName", parameters)`. This supports JSON parameters, not arbitrary multipart uploads or webhook hosting.

## Logging and limits

`set_logging(bot, "INFO", "bot.log")` selects OFF, ERROR, INFO, or DEBUG and an optional file. Use an empty filename for console only. INFO is the default. Logs include UTC timestamps, request method names, and success/error status. They do not include tokens, request URLs, or message bodies. `log(bot, value)` adds a custom INFO entry with token redaction and newline/control-character removal. Files append UTF-8 and are not automatically rotated. Create parent folders yourself.

Requests do not follow redirects. Ordinary requests allow up to 60 seconds; automatic polling uses its timeout plus 10 seconds. Response bodies are bounded to 8 MiB. `close(bot)` releases the bot handle; program exit also cleans up state. Falcon's execution-step limit applies to polling loops, so set a suitable `--max-steps` budget for a long-lived process.

This is an initial synchronous client, not full pyTelegramBotAPI compatibility. There is no decorator handler registration, webhook server, download helper, background worker pool, automatic persistence, or automatic retry of sends.

References: [Telegram Bot API](https://core.telegram.org/bots/api), [pyTelegramBotAPI TeleBot](https://pytba.readthedocs.io/en/latest/sync_version/index.html).

## Function signatures

```fc
func TeleBot(token: string) -> int
func close(bot: int)
func set_logging(bot: int, level: string, filename: string)
func log(bot: int, value: string)
func request(bot: int, method: string, parameters: map) -> map
func ok(response: map) -> bool
func result(response: map) -> any
func error_code(response: map) -> int
func error(response: map) -> string
func retry_after(response: map) -> int
func get_me(bot: int) -> map
func get_webhook_info(bot: int) -> map
func delete_webhook(bot: int, dropPending: bool) -> map
func poll(bot: int, timeoutSeconds: int) -> map
func get_updates(bot: int, offset: int, timeoutSeconds: int) -> map
func updates(response: map) -> list
func has_message(update: map) -> bool
func message(update: map) -> map
func chat_id(incoming: map) -> int
func text(incoming: map) -> string
func command(incoming: map) -> string
func send_message(bot: int, chatId: any, value: string) -> map
func send_message_with(bot: int, chatId: any, value: string, options: map) -> map
func reply_to(bot: int, incoming: map, value: string) -> map
func edit_message_text(bot: int, chatId: any, messageId: int, value: string) -> map
func delete_message(bot: int, chatId: any, messageId: int) -> map
func send_photo(bot: int, chatId: any, filename: string, caption: string) -> map
func send_document(bot: int, chatId: any, filename: string, caption: string) -> map
func button(label: string, data: string) -> map
func inline_keyboard(rows: list) -> map
func answer_callback_query(bot: int, queryId: string, value: string) -> map
```
