package com.cloudogu.gitops.utils.license

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Slf4j
class LicenseValidator {

    static void main(String[] args) {



    }

    static boolean verifyLicense(String licenseFile) {

        if (!licenseFile) {
            return false
        }

        String publicKeyBase64 = "MCowBQYDK2VwAyEAZUm7N1tqxr2WXFHMgxP9PJ+tDG573dYb5iz+G/Fz3pE="

        def json = new JsonSlurper().parseText(licenseFile)

        if (!json.signature) {
            println "Keine Signatur gefunden!"
            return false
        }

        String expires = json.license?.expires
        if (!expires) {
            println "Kein Ablaufdatum gefunden!"
            return false
        }

        LocalDate expiryDate = LocalDate.parse(expires, DateTimeFormatter.ISO_DATE)
        if (LocalDate.now().isAfter(expiryDate)) {
            println "Lizenz abgelaufen!"
            return false
        }

        String signatureBase64 = json.remove('signature')
        byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64)

        String jsonWithoutSignature = groovy.json.JsonOutput.toJson(json)
        byte[] jsonBytes = jsonWithoutSignature.getBytes("UTF-8")

        byte[] pubKeyBytes = Base64.getDecoder().decode(publicKeyBase64)
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(pubKeyBytes)
        PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(keySpec)

        Signature sig = Signature.getInstance("Ed25519")
        sig.initVerify(publicKey)
        sig.update(jsonBytes)
        return sig.verify(signatureBytes)
    }

}