"""
Uploads the freshly-built APK to OneDrive using DELEGATED permissions
(acting as you, via a refresh token) instead of application permissions.
This avoids needing tenant admin consent.

Run by the GitHub Actions workflow after `./gradlew assembleDebug`.

Required environment variables (set as GitHub Secrets):
  MS_CLIENT_ID       - App registration's Application (client) ID
  MS_TENANT_ID       - Azure AD tenant (directory) ID
  MS_REFRESH_TOKEN   - Refresh token from scripts/get_refresh_token.py
                        (run that script once on your own machine first)
  MS_FOLDER_PATH     - Folder path inside your OneDrive, e.g. "AppReleases"

Required environment variables (set by the workflow from build.gradle):
  APP_VERSION_CODE
  APP_VERSION_NAME
  RELEASE_NOTES (optional)
"""
import json
import os
import sys

import requests

APK_PATH = "app/build/outputs/apk/debug/app-debug.apk"
MANIFEST_PATH = "manifest.json"
GRAPH_BASE = "https://graph.microsoft.com/v1.0"
CHUNK_SIZE = 10 * 1024 * 1024  # 10 MB per chunk


def get_access_token(client_id, tenant_id, refresh_token):
    """Exchanges the stored refresh token for a fresh access token.
    Public client flow -- no client secret needed here."""
    url = f"https://login.microsoftonline.com/{tenant_id}/oauth2/v2.0/token"
    data = {
        "client_id": client_id,
        "grant_type": "refresh_token",
        "refresh_token": refresh_token,
        "scope": "Files.ReadWrite offline_access",
    }
    resp = requests.post(url, data=data)
    resp.raise_for_status()
    token_data = resp.json()
    if "refresh_token" in token_data:
        # Microsoft rotates refresh tokens on use. Print a masked marker so
        # it can be spotted in logs if it ever changes -- old tokens stay
        # valid for a grace period, but good practice to refresh the secret.
        print("NOTE: a new refresh_token was issued by Microsoft. If "
              "uploads start failing months from now, re-run "
              "get_refresh_token.py and update the MS_REFRESH_TOKEN secret.")
    return token_data["access_token"]


def upload_large_file(token, folder_path, filename, filepath):
    headers = {"Authorization": f"Bearer {token}"}

    item_path = f"{folder_path}/{filename}" if folder_path else filename
    session_url = f"{GRAPH_BASE}/me/drive/root:/{item_path}:/createUploadSession"
    resp = requests.post(session_url, headers=headers, json={
        "item": {"@microsoft.graph.conflictBehavior": "replace"}
    })
    resp.raise_for_status()
    upload_url = resp.json()["uploadUrl"]

    file_size = os.path.getsize(filepath)
    with open(filepath, "rb") as f:
        start = 0
        while start < file_size:
            chunk = f.read(CHUNK_SIZE)
            end = start + len(chunk) - 1
            chunk_headers = {
                "Content-Length": str(len(chunk)),
                "Content-Range": f"bytes {start}-{end}/{file_size}",
            }
            print(f"Uploading bytes {start}-{end} of {file_size}...")
            put_resp = requests.put(upload_url, headers=chunk_headers, data=chunk)
            put_resp.raise_for_status()
            start += len(chunk)

    return put_resp.json()


def create_share_link(token, item_id):
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    url = f"{GRAPH_BASE}/me/drive/items/{item_id}/createLink"
    resp = requests.post(url, headers=headers, json={"type": "view", "scope": "anonymous"})
    resp.raise_for_status()
    return resp.json()["link"]["webUrl"]


def main():
    client_id = os.environ["MS_CLIENT_ID"]
    tenant_id = os.environ["MS_TENANT_ID"]
    refresh_token = os.environ["MS_REFRESH_TOKEN"]
    folder_path = os.environ.get("MS_FOLDER_PATH", "AppReleases")

    version_code = int(os.environ["APP_VERSION_CODE"])
    version_name = os.environ["APP_VERSION_NAME"]
    release_notes = os.environ.get("RELEASE_NOTES", f"Automated build {version_name}")

    if not os.path.exists(APK_PATH):
        print(f"ERROR: APK not found at {APK_PATH}")
        sys.exit(1)

    print("Refreshing access token...")
    token = get_access_token(client_id, tenant_id, refresh_token)

    filename = f"app-release-v{version_name}-code{version_code}.apk"
    print(f"Uploading {filename} to OneDrive (me:/{folder_path})...")
    item = upload_large_file(token, folder_path, filename, APK_PATH)
    item_id = item["id"]
    print(f"Uploaded. Item ID: {item_id}")

    print("Creating anonymous share link...")
    share_url = create_share_link(token, item_id)
    print(f"Share link: {share_url}")

    manifest = {
        "versionCode": version_code,
        "versionName": version_name,
        "apkUrl": share_url,
        "releaseNotes": release_notes,
    }

    with open(MANIFEST_PATH, "w") as f:
        json.dump(manifest, f, indent=2)

    print(f"manifest.json updated:\n{json.dumps(manifest, indent=2)}")


if __name__ == "__main__":
    main()
