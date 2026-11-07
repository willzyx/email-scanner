FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

# Fail the build if the image lacks the time zone database (needed for TZ).
# /app/data is pre-created and owned by mailscan: a new empty Docker volume inherits that ownership.
RUN python -c "from zoneinfo import ZoneInfo; ZoneInfo('Asia/Nicosia')" \
 && useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin mailscan \
 && mkdir -p /app/data \
 && chown mailscan:mailscan /app/data

WORKDIR /app
COPY mailscan/ /app/mailscan/

USER mailscan
CMD ["python", "-m", "mailscan", "run"]
