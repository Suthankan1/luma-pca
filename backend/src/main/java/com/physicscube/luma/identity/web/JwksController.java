package com.physicscube.luma.identity.web;

import com.physicscube.luma.identity.SigningKeys;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Publishes the public halves of the access-token keys. */
@RestController
class JwksController {
    private final SigningKeys keys;

    JwksController(SigningKeys keys) {
        this.keys = keys;
    }

    @GetMapping("/.well-known/jwks.json")
    Map<String, Object> jwks() {
        return keys.publicJwks();
    }
}
