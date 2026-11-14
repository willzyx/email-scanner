# mailscan

Reads your inboxes, one or several (Gmail unless told otherwise); a local model (`gemma4:e4b` in Ollama) rates how important each email is and writes a short summary. Important (🔴) and regular (🟡) emails go to Telegram right away, each mailbox to a chat of its own; ads, newsletters and notifications are rated and skipped, nothing is sent for them. No cloud LLMs: emails never leave the machine, and the only thing that goes out is the sender, the subject and the summary, to your Telegram.

The summaries are in Russian by default; change the language with `SUMMARY_LANGUAGE`.

## How it works

`docker compose up -d` starts:

- **`ollama`**: the model. No port is published; only the scanner can reach it inside the compose network.
- **`ollama-pull`**: one-shot. On the first start it downloads the model (6.6 GB) and does nothing after that.
- **`mailscan`**: the scanner with its own schedule: it checks the mailboxes one after another, waits a minute (`SCAN_INTERVAL_MIN`) and checks again.

A mailbox is opened read-only: emails are not marked as read, moved or deleted. Only the inbox (`INBOX`) is scanned; the archive and other Gmail labels are not read.

If Telegram is unreachable when an important email comes in, the scanner stops at that email and retries in a minute. A mailbox that fails, with a wrong password for one, does not hold up the others. If Gmail renumbers an inbox (rare), its last 24 hours are re-read, and an important email from that window may arrive a second time.

## Machine requirements

- Docker with compose v2.
- 12 GB of RAM or more: the model takes ~7 GB while it processes emails and unloads after a minute of idling.
- ~16 GB of disk: the Ollama image is 8.5 GB and the model 6.6 GB.
- Internet: to build the image, on the first start (to download the model) and while running (Gmail, Telegram).

Speed depends on the CPU: a laptop with a Core Ultra 7 155U takes ~36 s per email. The scanner itself is a JVM process held to 256 MB; it takes about 70 MB.

## Setup

### 1. Gmail app password

For each Gmail mailbox:

1. Turn on 2-Step Verification in the Google account.
2. Create an app password at <https://myaccount.google.com/apppasswords>; the name doesn't matter, e.g. `mailscan`. It is 16 characters: that is what goes into `password` in `mailboxes.yaml`, the regular password won't work.
3. If the Gmail settings ("Forwarding and POP/IMAP") have an IMAP switch, it must be on.

The app password gives full access to the mailbox. It can be revoked on the same page at any time.

### 2. Telegram bot

1. Send `/newbot` to [@BotFather](https://t.me/BotFather) and get the token. One bot reports for every mailbox.
2. Whoever is to get the messages finds the bot in Telegram and sends it any message; without that the bot cannot write to them. To report to a group, add the bot to it.

### 3. The `.env` file

```sh
cp .env.example .env
chmod 600 .env
```

Fill in `TELEGRAM_BOT_TOKEN`; the scanner does not start without it. The other variables are described in `.env.example`; you don't have to change them. `SUMMARY_LANGUAGE` sets the language the model writes summaries in (`Russian` by default).

### 4. The `mailboxes.yaml` file

```sh
cp mailboxes.example.yaml mailboxes.yaml
```

List the mailboxes in it: for each one the address (`user`), the app password (`password`) and the Telegram chat its emails are reported to (`chat_id`); `host` is needed only for a mailbox that is not on Gmail. Two mailboxes may report to the same chat.

To find out a `chat_id`, ask Telegram what the bot has received, after the chat has sent it a message:

```sh
curl -s "https://api.telegram.org/bot<TELEGRAM_BOT_TOKEN>/getUpdates"
```

The number you need is `id` inside `"chat"`: `..."chat":{"id":123456789,"first_name":...`.

The scanner runs in its container as a user of its own, with the id 10001. Hand the file over to that user, so that nobody else on the server can read the passwords:

```sh
sudo chown 10001 mailboxes.yaml
sudo chmod 400 mailboxes.yaml
```

From then on the file is edited with `sudoedit mailboxes.yaml`.

## Running

```sh
docker compose up -d
docker compose logs -f mailscan
```

The first start builds the image and downloads the model, which takes a while. The log then says `mailscan is up`, and a line per email follows as the mail comes in.

A mailbox scanned for the first time gives its emails from the last 24 hours. A mistake in `mailboxes.yaml` stops the scanner at the start, with a message that names the file. A wrong bot token or chat id shows up in the log when the first email is to be sent: `Telegram sendMessage: 400 Bad Request: chat not found`.

## Maintenance

| What | Command |
|---|---|
| Logs | `docker compose logs -f mailscan` |
| Stop and start the scanner | `docker compose stop mailscan`, `docker compose start mailscan` |
| Add or change a mailbox | `sudoedit mailboxes.yaml`, then `docker compose restart mailscan` |
| Update the code | copy the new files, then `docker compose up -d --build` |
| Reset the state (the model stays) | `docker compose down && docker volume rm mailscan-data` |
| Remove everything, including the model | `docker compose down -v` |

After a state reset the scanner starts as on the first run: emails from the last 24 hours of every mailbox, and the important ones among them arrive again.

## Where the data lives

One file from the server's disk is mounted into the scanner's container, read-only: `mailboxes.yaml`. The rest are two Docker volumes:

- **`mailscan-ollama`**: the model.
- **`mailscan-data`**: a tiny SQLite database with one thing in it: where the scan of each mailbox stopped. Nothing about the emails themselves is stored.

Email bodies are never stored: they exist only in memory while being processed. The scanner container's file system is read-only; it can write only to its own volume. The logs contain the addresses of your mailboxes, UIDs, importance levels and errors, and nothing from the emails themselves: no senders, subjects or text.

Secrets live in `.env` (the bot token) and in `mailboxes.yaml` (the passwords of the mailboxes). Compose passes the former to the container as environment variables, which `docker inspect` can show, and mounts the latter into it: access to Docker on the server equals access to the mailboxes.

## Development

You need JDK 21; Gradle comes with the wrapper. Building the image needs no JDK on the machine: it happens inside Docker.

```sh
./gradlew build          # ktlint, detekt and every test; the mail server and the database of the tests run inside the test process
./gradlew ktlintFormat   # fix what ktlint can fix by itself
./gradlew installDist    # then, without Docker, against a local Ollama on localhost:11434:
set -a; . ./.env; set +a; build/install/mailscan/bin/mailscan
```

Started this way the scanner reads `mailboxes.yaml` from the current directory and keeps its database in `data/` there.

The image build runs `./gradlew build` too, so an image whose tests fail is never built.

| Package | What lives there |
|---|---|
| `config` | the environment variables and the list of mailboxes |
| `imap` | the read-only mailbox: which emails are new, and their content |
| `mail` | reading an email: sender, subject and the body as text (HTML without its hidden parts) |
| `llm` | the prompt, the Ollama client and the reading of the model's answer |
| `telegram` | the text of a message about an email and the Bot API client |
| `db` | where the scan stopped, in SQLite |
| `scan` | one pass over the new emails of a mailbox and the loop that goes round the mailboxes |
| `http` | JSON over HTTP for the Ollama and Telegram clients |

The test emails in `src/test/resources` are made up: 20 home ones and 20 work ones, in 17 languages.
