from contextlib import contextmanager
from typing import Iterator

from psycopg import Connection
from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from jay_server.config import settings


pool: ConnectionPool | None = None


def open_database_pool() -> None:
    global pool
    pool = ConnectionPool(
        conninfo=settings.database_url.replace("postgresql+psycopg://", "postgresql://"),
        kwargs={"row_factory": dict_row},
        open=True,
    )
    pool.wait()


def close_database_pool() -> None:
    global pool
    if pool is not None:
        pool.close()
        pool = None


@contextmanager
def transaction() -> Iterator[Connection]:
    if pool is None:
        raise RuntimeError("Database pool is not open")
    with pool.connection() as connection:
        with connection.transaction():
            yield connection
