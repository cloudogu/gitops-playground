
package com.cloudogu.gitops.utils.license

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

@Slf4j
class LicenseValidator {

    static void main(String[] args) {
    }

    /**
     * Parses and verifies the license file content.
     * @return a valid License object, or null if invalid/expired/tampered.
     */
    static License parseLicense(String licenseFile) {
        if (!licenseFile) {
            return null
        }

        String publicKeyBase64 = "MCowBQYDK2VwAyEAcQHZTZa+2zPmGCJaoiPERiu4YqP2JZ6jCl2x3TUgFAg="

        def json = new JsonSlurper().parseText(licenseFile) as Map

        if (!json.signature) {
            log.warn("Keine Signatur gefunden!")
            return null
        }

        License license = License.fromMap(json)

        if (license.expires == null) {
            log.warn("Kein Ablaufdatum gefunden!")
            return null
        }

        if (license.isExpired()) {
            log.warn("Lizenz abgelaufen!")
            return null
        }

        // Verify signature
        String signatureBase64 = json.remove('signature') as String
        byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64)

        String jsonWithoutSignature = groovy.json.JsonOutput.toJson(json)
        byte[] jsonBytes = jsonWithoutSignature.getBytes("UTF-8")

        byte[] pubKeyBytes = Base64.getDecoder().decode(publicKeyBase64)
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(pubKeyBytes)
        PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(keySpec)

        Signature sig = Signature.getInstance("Ed25519")
        sig.initVerify(publicKey)
        sig.update(jsonBytes)

        if (!sig.verify(signatureBytes)) {
            log.warn("Signatur ungültig!")
            return null
        }
        log.debug("License gültig!")
        return license
    }

    /**
     * Backwards-compatible: returns true if license is valid.
     */
    static boolean verifyLicense(String licenseFile) {
        return parseLicense(licenseFile) != null
    }
}