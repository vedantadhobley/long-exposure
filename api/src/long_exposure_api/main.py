"""Long Exposure API — read-only access to parsed events and narratives.

Day 1 stub. Endpoints fill in as the data layer lands during the build phase.
"""

import logging
import os

import structlog
from fastapi import FastAPI

structlog.configure(
    processors=[
        structlog.processors.TimeStamper(fmt="iso"),
        structlog.processors.add_log_level,
        structlog.processors.JSONRenderer(),
    ],
    wrapper_class=structlog.stdlib.BoundLogger,
    logger_factory=structlog.stdlib.LoggerFactory(),
)
logging.basicConfig(level=logging.INFO)
log = structlog.get_logger()

app = FastAPI(title="Long Exposure API", version="0.1.0")


@app.on_event("startup")
async def startup() -> None:
    log.info(
        "api.startup",
        env=os.environ.get("APP_ENV", "unset"),
        postgres_host=os.environ.get("POSTGRES_HOST", "unset"),
    )


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/api/v1/health")
async def health_v1() -> dict[str, str]:
    return {"status": "ok", "version": "0.1.0"}


# Endpoint stubs — implementations land as the data model is in place.
# GET /api/v1/market/today
# GET /api/v1/market/{date}
# GET /api/v1/market/{date}/events
# GET /api/v1/ticker/{symbol}/history
# GET /api/v1/event/{event_id}
