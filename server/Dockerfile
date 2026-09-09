FROM python:3.11.14-slim

WORKDIR /app
COPY pyproject.toml requirements.lock manage.py ./
COPY jay_server ./jay_server
RUN pip install --no-cache-dir --require-hashes -r requirements.lock && pip install --no-cache-dir --no-deps .

CMD ["sh", "-c", "exec uvicorn jay_server.asgi:application --host 0.0.0.0 --port ${PORT:-8000} --workers 1 --timeout-graceful-shutdown 25"]
