import assert from "node:assert/strict";
import test from "node:test";
import { clientAddressFrom, trustedProxyHops } from "../src/lib/client-address.ts";

test("with no trusted proxies nothing is forwarded, whatever the visitor sends", () => {
  assert.equal(clientAddressFrom("203.0.113.7", 0), null);
  assert.equal(clientAddressFrom(null, 1), null);
  assert.equal(trustedProxyHops(undefined), 0);
  assert.equal(trustedProxyHops("x"), 0);
  assert.equal(trustedProxyHops("9"), 0);
  assert.equal(trustedProxyHops("1"), 1);
});

test("the visitor is the entry the trusted proxies appended; forged entries to the left are ignored", () => {
  // One load balancer: it appended the address it saw.
  assert.equal(clientAddressFrom("203.0.113.7", 1), "203.0.113.7");
  assert.equal(clientAddressFrom("6.6.6.6, 203.0.113.7", 1), "203.0.113.7");
  // CDN then load balancer.
  assert.equal(clientAddressFrom("6.6.6.6, 203.0.113.7, 10.0.0.5", 2), "203.0.113.7");
  assert.equal(clientAddressFrom("2001:db8::1", 1), "2001:db8::1");
  // Fewer entries than proxies, or something that is not an address.
  assert.equal(clientAddressFrom("203.0.113.7", 2), null);
  assert.equal(clientAddressFrom("not-an-ip", 1), null);
  assert.equal(clientAddressFrom("203.0.113.7:443", 1), null);
});
