/**
 * The MIT License
 * Copyright © 2017, 2019 WebFolder OÜ
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.webfolder.cormorant.internal.jaxrs;

import static java.lang.Long.parseLong;
import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static java.time.Instant.now;
import static javax.ws.rs.core.HttpHeaders.CONTENT_LENGTH;
import static javax.ws.rs.core.Response.status;
import static javax.ws.rs.core.Response.Status.FORBIDDEN;
import static javax.ws.rs.core.Response.Status.UNAUTHORIZED;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.sql.SQLException;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import io.webfolder.cormorant.api.exception.CormorantException;
import io.webfolder.cormorant.api.service.KeystoneService;
import io.webfolder.cormorant.api.service.ContainerService;
import io.webfolder.cormorant.api.service.MetadataService;

class SecurityFilter<T> implements ContainerRequestFilter {

    private final Map<String, Principal> tokens;

    private final KeystoneService        keystoneService;

    private final String                 permission;

    private final String                 AUTH_TOKEN          = "X-Auth-Token";

    private final String                 TEMP_URL_SIG        = "temp_url_sig";

    private final String                 TEMP_URL_EXPIRES    = "temp_url_expires";
 
    private final String                 HMAC_SHA1_ALGORITHM = "HmacSHA1";

    private final char[]                 HEX_ARRAY           = "0123456789abcdef".toCharArray();

    private final MetadataService accountMetadataService;

    private final ContainerService<T> containerService;

    public SecurityFilter(
                final Map<String, Principal> tokens,
                final String                 permission,
                final KeystoneService        keystoneService,
                final MetadataService        accountMetadataService,
                final ContainerService<T>    containerService) {
        this.tokens                 = tokens;
        this.permission             = permission;
        this.keystoneService        = keystoneService;
        this.accountMetadataService = accountMetadataService;
        this.containerService       = containerService;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        final String authToken = requestContext.getHeaderString(AUTH_TOKEN);
        final UriInfo uriInfo = requestContext.getUriInfo();
        if (authToken == null) {
            final String tus = uriInfo.getQueryParameters().getFirst(TEMP_URL_SIG);
            final String tue = uriInfo.getQueryParameters().getFirst(TEMP_URL_EXPIRES);

            if  ( tus != null && tue != null ) {
                long expires;
                try {
                    expires = parseLong(tue);
                } catch (NumberFormatException e) {
                    expires = 0L;
                }
                final long unixTime = expires * 1000L;
                if (unixTime > currentTimeMillis()) {
                    final String hmacBody = format("%s\n%s\n%s", requestContext.getMethod(), tue, uriInfo.getAbsolutePath().getPath());
                    final String account = uriInfo.getPathParameters().getFirst("account");
                    String tempUrlKey;
                    try {
                        tempUrlKey = accountMetadataService.get(account, "temp-url-key");
                    } catch (SQLException e) {
                        throw new CormorantException(e);
                    }
                    if ( tempUrlKey != null && ! tempUrlKey.isEmpty() ) {
                        String hash = calculateHash(hmacBody, tempUrlKey);
                        if (  tus.equalsIgnoreCase(hash) ) {
                            return;
                        }
                    }
                }
            } else {
                requestContext.abortWith(status(UNAUTHORIZED).entity("401 Unauthorized").build());
                return;
            }
        }

        final CormorantPrincipal principal = (CormorantPrincipal) tokens.get(authToken);
        if (principal == null) {
            requestContext.abortWith(status(UNAUTHORIZED).entity("401 Unauthorized").build());
            return;
        }

        if (now().isAfter(principal.getExpires())) {
            final String error = "Auth token has expired.";
            tokens.remove(authToken);
            requestContext
                .abortWith(status(UNAUTHORIZED)
                .header(CONTENT_LENGTH, error.length())
                .entity(error)
                .build());
        }

        // Let the authenticated user delete its own account
        // This logic is required to pass tempest (TokensV3Test.test_create_token)
        boolean deleteSelf = "cormorant-admin".equals(permission) &&
                                "DELETE".equalsIgnoreCase(requestContext.getMethod()) &&
                                principal.getName().equals(uriInfo.getPathParameters().getFirst("userId"));

        final boolean authorized = keystoneService.hasPermission(principal.getName(), permission, requestContext.getMethod());
        if ( ! deleteSelf && ! authorized ) {
            final String error = "Insufficient permission.";
            requestContext
                    .abortWith(status(FORBIDDEN)
                    .header(CONTENT_LENGTH, error.length())
                    .entity(error)
                    .build());
            return;
        }

        if ("cormorant-object".equals(permission)) {
            final String accountName = uriInfo.getPathParameters().getFirst("account");
            final String containerName = uriInfo.getPathParameters().getFirst("container");
            if ( ! containerService.contains(accountName, containerName) ) {
                final String error = "Container [" + containerName + "] not found.";
                requestContext
                        .abortWith(status(FORBIDDEN)
                        .header(CONTENT_LENGTH, error.length())
                        .entity(error)
                        .build());
                return;
            }
        }

        SecurityContext securityContext = requestContext.getSecurityContext();
        requestContext.setSecurityContext(new CormorantSecurityContext(securityContext,
                                                    principal,
                                                    keystoneService,
                                                    requestContext.getMethod()));
    }

    protected String bytesToHex(final byte[] bytes) {
        final char[] hexChars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            hexChars[i * 2]     = HEX_ARRAY[v >>> 4];
            hexChars[i * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    public String calculateHash(final String data, final String privateKey) {
        final SecretKeySpec signingKey = new SecretKeySpec(privateKey.getBytes(), HMAC_SHA1_ALGORITHM);
        final Mac mac;
        try {
            mac = Mac.getInstance(HMAC_SHA1_ALGORITHM);
            mac.init(signingKey);
            return bytesToHex(mac.doFinal(data.getBytes()));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new CormorantException(e);
        }
    }
}
