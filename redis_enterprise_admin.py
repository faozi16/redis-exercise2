#!/usr/bin/env python3
import os
import sys
import json
import time
import requests
from requests.auth import HTTPBasicAuth
from urllib3.exceptions import InsecureRequestWarning
import urllib3

BASE_URL = os.getenv("REDIS_API_BASE_URL", "https://localhost:9443")
API_USER = os.getenv("REDIS_API_USER", "admin")
API_PASSWORD = os.getenv("REDIS_API_PASSWORD", "password")
DB_NAME = os.getenv("REDIS_DB_NAME", "python-demo-db")
TEMP_USER_PASSWORD = os.getenv("REDIS_TEMP_USER_PASSWORD", "ChangeMe123!")
VERIFY_TLS = os.getenv("REDIS_VERIFY_TLS", "false").lower() == "true"

if not VERIFY_TLS:
    urllib3.disable_warnings(InsecureRequestWarning)

session = requests.Session()
session.auth = HTTPBasicAuth(API_USER, API_PASSWORD)
session.headers.update({"Accept": "application/json"})
session.verify = VERIFY_TLS

def request(method, path, payload=None):
    url = f"{BASE_URL}{path}"
    headers = {}
    if payload is not None:
        headers["Content-Type"] = "application/json"

    resp = session.request(
        method=method,
        url=url,
        headers=headers,
        data=json.dumps(payload) if payload is not None else None,
        timeout=60,
    )
    if resp.status_code not in (200, 201, 204):
        raise RuntimeError(f"{method} {path} failed: HTTP {resp.status_code} -> {resp.text}")
    return resp

def create_database():
    # Redis Enterprise REST API expects the database configuration under the "bdb" field.
    # No modules are included here.
    payload = {
        "bdb": {
            "name": DB_NAME,
            "type": "redis",
            "memory_size": 1073741824,   # 1 GiB
            "shards_count": 1
        }
    }

    resp = request("POST", "/v1/bdbs", payload)
    data = resp.json()

    # Response may be the BDB object directly or nested in some deployments.
    db_obj = data if "uid" in data else data.get("bdb", {})
    db_uid = db_obj.get("uid")
    if db_uid is None:
        raise RuntimeError(f"Database create response did not contain uid: {data}")
    return db_uid

def create_user(name, email, role):
    payload = {
        "email": email,
        "password": TEMP_USER_PASSWORD,
        "name": name,
        "role": role,
        "email_alerts": False,
        "auth_method": "regular"
    }
    request("POST", "/v1/users", payload)
    print(f"Created user: {name} | {role} | {email}")

def list_users():
    resp = request("GET", "/v1/users")
    users = resp.json()

    print("\nUsers:")
    for u in users:
        name = u.get("name", "")
        role = u.get("role", "")
        email = u.get("email", "")
        print(f"{name} | {role} | {email}")

def delete_database(db_uid):
    request("DELETE", f"/v1/bdbs/{db_uid}")
    print(f"\nDeleted database uid={db_uid}")

def main():
    db_uid = create_database()
    print(f"Created database: {DB_NAME} (uid={db_uid})")

    create_user("John Doe", "john.doe@example.com", "db_viewer")
    create_user("Mike Smith", "mike.smith@example.com", "db_member")
    create_user("Cary Johnson", "cary.johnson@example.com", "admin")

    # Small wait can help in some lab environments if the user list is eventually consistent.
    time.sleep(1)
    list_users()

    delete_database(db_uid)

if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(1)
