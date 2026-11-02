// Runs before each webhook request and does what Meta does: HMAC SHA-256 of the exact body,
// with the app secret, in the X-Hub-Signature-256 header.
// It must be deterministic: the HTTP Client may run a pre-request script more than once per request,
// so generating a random id here signs one body and sends another. The id comes from a global
// variable that after-webhook.js renews after each response.
if (!client.global.get("wamid")) {
    client.global.set("wamid", "wamid.IDE-" + Date.now());
}
const body = request.body.tryGetSubstituted();
const signature = crypto.hmac.sha256()
    .withTextSecret(request.environment.get("appSecret"))
    .updateWithText(body)
    .digest().toHex();
request.variables.set("signature", "sha256=" + signature);
