# mailscan

Reads a personal Gmail inbox; a local model (`gemma4:e4b` in Ollama) rates how important each email is and writes a short summary. Important emails go to Telegram right away; everything else is rated and skipped, nothing is sent for it. No cloud LLMs: emails never leave the machine, and the only thing that goes out is the sender, the subject and the summary, to your Telegram.

The summaries are in Russian by default; change the language with `SUMMARY_LANGUAGE`.

## How it works

`docker compose up -d` starts:

- **`ollama`**: the model. No port is published; only the scanner can reach it inside the compose network.
- **`ollama-pull`**: one-shot. On the first start it downloads the model (6.6 GB) and does nothing after that.
- **`mailscan`**: the scanner with its own schedule: it checks the mail every 10 minutes.

The mailbox is opened read-only: emails are not marked as read, moved or deleted. Only the inbox (`INBOX`) is scanned; the archive and other Gmail labels are not read.

If Telegram is unreachable when an important email comes in, the scanner stops at that email and retries in a minute. If Gmail renumbers the inbox (rare), the last 24 hours are re-read, and an important email from that window may arrive a second time.

## Machine requirements

- Docker with compose v2.
- 12 GB of RAM or more: the model takes ~7 GB while it processes emails and unloads after a minute of idling.
- ~16 GB of disk: the Ollama image is 8.5 GB and the model 6.6 GB.
- Internet: on the first start (to download the model) and while running (Gmail, Telegram).

Speed depends on the CPU: a laptop with a Core Ultra 7 155U takes ~36 s per email.

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

Fill in `IMAP_USER`, `IMAP_PASSWORD`, `TELEGRAM_BOT_TOKEN`. The other variables are described in `.env.example`; you don't have to change them. `SUMMARY_LANGUAGE` sets the language the model writes summaries in (`Russian` by default).

Find out `TELEGRAM_CHAT_ID` and put it into `.env`:

```sh
docker compose build mailscan
./mailscan.sh telegram-chat-id
```

## Running

```sh
docker compose up -d
docker compose logs -f mailscan
```

The first start downloads the model, which takes a while. Check Telegram:

```sh
./mailscan.sh telegram-test
```

On the first run the scanner takes the emails from the last 24 hours.

## Maintenance

`./mailscan.sh` runs a `python -m mailscan` command in a fresh container that exits when the command is done; the running scanner is not touched.

| What | Command |
|---|---|
| Logs | `docker compose logs -f mailscan` |
| Status: where the scan stopped | `./mailscan.sh status` |
| See what the model says about the last 5 emails, without sending anything | `./mailscan.sh check` (`-n 20` for more) |
| Update the code | copy the new files, then `docker compose up -d --build` |
| Reset the state (the model stays) | `docker compose down && docker volume rm mailscan-data` |
| Remove everything, including the model | `docker compose down -v` |

After a state reset the scanner starts as on the first run: emails from the last 24 hours, and the important ones among them arrive again.

## Where the data lives

Nothing from the server's disk is mounted into the containers, only two Docker volumes:

- **`mailscan-ollama`**: the model.
- **`mailscan-data`**: a tiny SQLite database with one thing in it: where the scan stopped. Nothing about the emails themselves is stored.

Email bodies are never stored: they exist only in memory while being processed. The scanner container's file system is read-only; it can write only to its own volume. The logs contain UIDs, importance levels and errors, no subjects or addresses.

Secrets live in `.env`. Compose passes them to the container as environment variables, and `docker inspect` can show them: access to Docker on the server equals access to the mailbox.

## Development

You need [uv](https://docs.astral.sh/uv/). The scanner itself uses only the Python standard library; `pytest` is needed only for the tests.

```sh
uv sync
uv run pytest
uv run --env-file .env python -m mailscan check -n 3   # without Docker: the last 3 emails via a local Ollama on localhost:11434
```

`prototype/` is a model benchmark on 20 test home emails (RU/EN/EL); it is not included in the image:

```sh
uv run python prototype/bench.py --models gemma4:e4b --runs 1
```

The numbers in `prototype/bench-run*.log` were measured with the previous, Russian-language prompt. Re-run `bench.py` to refresh them.
