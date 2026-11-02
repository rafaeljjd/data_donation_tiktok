#!/usr/bin/env python3
"""Runs the server with synthetic videos, a compressed clock, and Chrome standing in for TikTok.

    .venv/bin/python scripts/run_dev_server.py [path/to/database.sqlite3] [port]
"""

from __future__ import annotations

import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
os.chdir(ROOT)

from server.app.config import emulator_dev_config  # noqa: E402

db_path = sys.argv[1] if len(sys.argv) > 1 else "server/data/dev.sqlite3"
port = int(sys.argv[2]) if len(sys.argv) > 2 else 8000
os.environ["FEEDSTUDY_CONFIG"] = json.dumps(emulator_dev_config())
os.environ["FEEDSTUDY_SERVER"] = json.dumps({"db_path": db_path})

import uvicorn  # noqa: E402

uvicorn.run("server.app.main:create_app", factory=True, host="127.0.0.1", port=port)
