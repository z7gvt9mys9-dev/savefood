"""Dedicated background worker: `python -m backend.worker`.

Runs the maintenance ticks (backend/background.py) outside the API process so
the API can scale to several replicas without duplicating loops. Schema init
is idempotent (CREATE IF NOT EXISTS), so worker and API may start in any order.

Pair with BACKGROUND_TASKS=external on the API containers.
"""
import logging
import time

from backend import database
from backend.needy import db as needy_db
from backend.shop import db as shop_db
from backend.volunteer import db as vol_db
from backend import background

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")


def main():
    # Retry init until Postgres is reachable — compose may start us first.
    while True:
        try:
            database.init_common_db()
            shop_db.init_db()
            needy_db.init_db()
            vol_db.init_db()
            database.init_ticket_extensions()
            break
        except Exception as e:
            logging.warning("[worker] DB not ready (%s), retrying in 3s", e)
            time.sleep(3)

    background.start_threads()
    logging.info("[worker] background worker running")
    while True:
        time.sleep(3600)


if __name__ == "__main__":
    main()
