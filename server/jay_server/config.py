from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    database_url: str = "postgresql+psycopg://jay:jay@127.0.0.1:5432/jay"
    public_url: str = "http://127.0.0.1:8000"
    invite_lifetime_hours: int = 24
    firebase_credentials_json: str | None = None
    google_play_credentials_json: str | None = None
    play_entitlement_lifetime_hours: int = 48
    alarm_occurrence_monitor_enabled: bool = True
    b2_s3_endpoint: str | None = None
    b2_bucket_name: str | None = None
    b2_application_key_id: str | None = None
    b2_application_key: str | None = None

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    @field_validator("database_url", mode="before")
    @classmethod
    def select_database_driver(cls, value: str) -> str:
        if value.startswith("postgresql+psycopg://"):
            return value
        if value.startswith("postgresql://"):
            return value.replace("postgresql://", "postgresql+psycopg://", 1)
        return value.replace("postgres://", "postgresql+psycopg://", 1)


settings = Settings()
