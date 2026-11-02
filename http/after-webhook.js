// Runs after each webhook response: checks the 200 and prepares a new message id for the next call.
client.test("aceita com 200", () => client.assert(response.status === 200, "status " + response.status));
client.global.set("wamid", "wamid.IDE-" + Date.now() + "-" + Math.floor(Math.random() * 1e9));
