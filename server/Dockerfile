FROM python:3.11-slim

WORKDIR /app
COPY pyproject.toml alembic.ini ./
COPY jay_server ./jay_server
COPY migrations ./migrations
RUN pip install --no-cache-dir .

CMD ["sh", "-c", "alembic upgrade head && exec uvicorn jay_server.main:app --host 0.0.0.0 --port ${PORT:-8000}"]
