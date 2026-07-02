"""
Run this ONCE on your own computer to get a refresh token for delegated
OneDrive access. You'll log in with your Microsoft work account in a
browser, and this script prints a refresh token to store as a GitHub
secret (MS_REFRESH_TOKEN). GitHub Actions then uses that refresh token
to get new access tokens automatically, forever, without you logging in
again (as long as it's used at least once every ~90 days).

Setup:
    pip install msal

Fill in CLIENT_ID and TENANT_ID below (same values you already put in
GitHub secrets), then run:
    python get_refresh_token.py
"""
import msal

CLIENT_ID = "57c61946-70c2-427c-86de-ee90e601dd39"
TENANT_ID = "5e30b3be-fbd1-4c12-8398-36c3138f0795"
SCOPES = ["Files.ReadWrite"]

authority = f"https://login.microsoftonline.com/{TENANT_ID}"
app = msal.PublicClientApplication(CLIENT_ID, authority=authority)

flow = app.initiate_device_flow(scopes=SCOPES)
if "user_code" not in flow:
    raise Exception(f"Failed to create device flow: {flow}")

print(flow["message"])  # e.g. "To sign in, use a web browser to open
                         # https://microsoft.com/devicelogin and enter
                         # the code XXXXXXXXX"

result = app.acquire_token_by_device_flow(flow)  # blocks until you log in

if "refresh_token" in result:
    print("\n\n=== SUCCESS ===")
    print("Copy this refresh token and store it as the GitHub secret MS_REFRESH_TOKEN:\n")
    print(result["refresh_token"])
else:
    print("\n\n=== FAILED ===")
    print(result.get("error"))
    print(result.get("error_description"))
