from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    database_url: str = "postgresql+psycopg://jay:jay@127.0.0.1:5432/jay"
    public_url: str = "http://127.0.0.1:8000"
    invite_lifetime_hours: int = 72
    firebase_credentials_json: str | None = None

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


settings = Settings()
