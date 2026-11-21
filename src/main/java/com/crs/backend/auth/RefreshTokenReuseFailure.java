package com.crs.backend.auth;

/** Only detected reuse commits revocation while returning an error. All other issuance failures roll back. */
final class RefreshTokenReuseFailure extends AuthFailure {
    RefreshTokenReuseFailure() { super(401, "REFRESH_TOKEN_REUSED"); }
}
