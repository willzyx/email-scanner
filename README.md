# mailscan

Reads a personal Gmail inbox; a local model (`gemma4:e4b` in Ollama) rates how important each email is and writes a short summary. Important (🔴) and regular (🟡) emails go to Telegram right away; ads, newsletters and notifications are rated and skipped, nothing is sent for them. No cloud LLMs: emails never leave the machine, and the only thing that goes out is the sender, the subject and the summary, to your Telegram.

The summaries are in Russian by default; change the language with `SUMMARY_LANGUAGE`.

## How it works

`docker compose up -d` starts:

- **`ollama`**: the model. No port is published; only the scanner can reach it inside the compose network.
- **`ollama-pull`**: one-shot. On the first start it downloads the model (6.6 GB) and does nothing after that.
- **`mailscan`**: the scanner with its own schedule: it checks the mail, waits a minute (`SCAN_INTERVAL_MIN`) and checks again.

The mailbox is opened read-only: emails are not marked as read, moved or deleted. Only the inbox (`INBOX`) is scanned; the archive and other Gmail labels are not read.

If Telegram is unreachable when an important email comes in, the scanner stops at that email and retries in a minute. If Gmail renumbers the inbox (rare), the last 24 hours are re-read, and an important email from that window may arrive a second time.

## Machine requirements

- Docker with compose v2.
- 12 GB of RAM or more: the model takes ~7 GB while it processes emails and unloads after a minute of idling.
- ~16 GB of disk: the Ollama image is 8.5 GB and the model 6.6 GB.
- Internet: to build the image, on the first start (to download the model) and while running (Gmail, Telegram).

Speed depends on the CPU: a laptop with a Core Ultra 7 155U takes ~36 s per email. The scanner itself is a JVM process held to 256 MB; it takes about 50 MB.

## Setup

### 1. Gmail app password

1. Turn on 2-Step Verification in your Google account.
2. Create an app password at <https://myaccount.google.com/apppasswords>; the name doesn't matter, e.g. `mailscan`. It is 16 characters: that is what goes into `IMAP_PASSWORD`, the regular password won't work.
3. If the Gmail settings ("Forwarding and POP/IMAP") have an IMAP switch, it must be on.

The app password gives full access to the mailbox. It can be revoked on the same page at any time.

### 2. Telegram bot

1. Send `/newbot` to [@BotFather](https://t.me/BotFather) and get the token.
2. Find your bot in Telegram and send it any message; without that the bot cannot write to you.

### 3. The `.env` file

```sh
cp .env.example .env
chmod 600 .env
```

Fill in `IMAP_USER`, `IMAP_PASSWORD`, `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID`; the scanner does not start without all four. The other variables are described in `.env.example`; you don't have to change them. `SUMMARY_LANGUAGE` sets the language the model writes summaries in (`Russian` by default).

To find out `TELEGRAM_CHAT_ID`, ask Telegram what the bot has received, after you have sent it a message:

```sh
curl -s "https://api.telegram.org/bot<TELEGRAM_BOT_TOKEN>/getUpdates"
```

The number you need is `id` inside `"chat"`: `..."chat":{"id":123456789,"first_name":...`.

## Running

```sh
docker compose up -d
docker compose logs -f mailscan
```

The first start builds the image and downloads the model, which takes a while. The log then says `mailscan is up`, and a line per email follows as the mail comes in.

On the first run the scanner takes the emails from the last 24 hours. A wrong bot token or chat id shows up in the log when the first email is to be sent: `Telegram sendMessage: 400 Bad Request: chat not found`.

## Maintenance

| What | Command |
|---|---|
| Logs | `docker compose logs -f mailscan` |
| Stop and start the scanner | `docker compose stop mailscan`, `docker compose start mailscan` |
| Update the code | copy the new files, then `docker compose up -d --build` |
| Reset the state (the model stays) | `docker compose down && docker volume rm mailscan-data` |
| Remove everything, including the model | `docker compose down -v` |

After a state reset the scanner starts as on the first run: emails from the last 24 hours, and the important ones among them arrive again.

## Where the data lives

Nothing from the server's disk is mounted into the containers, only two Docker volumes:

- **`mailscan-ollama`**: the model.
- **`mailscan-data`**: a tiny SQLite database with one thing in it: where the scan stopped. Nothing about the emails themselves is stored.

Email bodies are never stored: they exist only in memory while being processed. The scanner container's file system is read-only; it can write only to its own volume. The logs contain the address of your mailbox, UIDs, importance levels and errors, and nothing from the emails themselves: no senders, subjects or text.

Secrets live in `.env`. Compose passes them to the container as environment variables, and `docker inspect` can show them: access to Docker on the server equals access to the mailbox.

## Development

You need JDK 21; Gradle comes with the wrapper. Building the image needs no JDK on the machine: it happens inside Docker.

```sh
./gradlew build          # ktlint, detekt and every test; the mail server and the database of the tests run inside the test process
./gradlew ktlintFormat   # fix what ktlint can fix by itself
./gradlew installDist    # then, without Docker, against a local Ollama on localhost:11434:
set -a; . ./.env; set +a; build/install/mailscan/bin/mailscan
```

The image build runs `./gradlew build` too, so an image whose tests fail is never built.

| Package | What lives there |
|---|---|
| `config` | the environment variables |
| `imap` | the read-only mailbox: which emails are new, and their content |
| `mail` | reading an email: sender, subject and the body as text (HTML without its hidden parts) |
| `llm` | the prompt, the Ollama client and the reading of the model's answer |
| `telegram` | the text of a message about an email and the Bot API client |
| `db` | where the scan stopped, in SQLite |
| `scan` | one pass over the new emails and the loop that repeats it |
| `http` | JSON over HTTP for the Ollama and Telegram clients |

The test emails in `src/test/resources` are made up: 20 home ones and 20 work ones, in 17 languages.
