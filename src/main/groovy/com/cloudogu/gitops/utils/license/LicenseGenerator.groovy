package com.cloudogu.gitops.utils.license

import groovy.json.JsonOutput

import java.nio.file.Files
import java.nio.file.Paths
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec

class LicenseGenerator {

    static void main(String[] args) {

        signLicense()
        //createKeyPair()

    }

    static void signLicense() {

        String base64Key
        try {
            base64Key = readPrivateKeyBase64()
        } catch (Exception e) {
            base64Key = readPrivateKeyBase64Stdin()
        }

        PrivateKey privateKey = loadEd25519PrivateKey(base64Key)

        def licenseData = [
                license: [
                        name    : "Max Mustermann",
                        product : "GOP",
                        expires : "2026-12-31",
                        features: ["test", "test1"],
                        version : "1.0.0"
                ]
        ]

        String jsonWithoutSignature = JsonOutput.toJson(licenseData)
        byte[] jsonBytes = jsonWithoutSignature.getBytes("UTF-8")

        Signature signature = Signature.getInstance("Ed25519")
        signature.initSign(privateKey)
        signature.update(jsonBytes)
        byte[] signedBytes = signature.sign()

        String signatureBase64 = Base64.getEncoder().encodeToString(signedBytes)

        licenseData.signature = signatureBase64

        String finalJson = JsonOutput.prettyPrint(JsonOutput.toJson(licenseData))
        println(finalJson)

        Files.write(Paths.get("license_signed.json"), finalJson.getBytes("UTF-8"))
    }

    /*
        used to generate new Keypairs. Update the public key when you create new licenses with this keypair.
     */
    static void createKeyPair() {
        def keyGen = KeyPairGenerator.getInstance("Ed25519")
        def keyPair = keyGen.generateKeyPair()
        def publicKey = keyPair.getPublic()
        def privateKey = keyPair.getPrivate()

        def publicKeyBytes = publicKey.getEncoded()
        def privateKeyBytes = privateKey.getEncoded()

        def base64PublicKey = Base64.getEncoder().encodeToString(publicKeyBytes)
        def base64PrivateKey = Base64.getEncoder().encodeToString(privateKeyBytes)

        println "Base64 Encoded Public Key:\n${base64PublicKey}"
        println "Base64 Encoded Private Key:\n${base64PrivateKey}"
    }

    static PrivateKey loadEd25519PrivateKey(String base64) {
        byte[] keyBytes = Base64.getDecoder().decode(base64)
        def spec = new PKCS8EncodedKeySpec(keyBytes)
        return KeyFactory.getInstance("Ed25519").generatePrivate(spec)
    }
    static String readPrivateKeyBase64Stdin() {
        print "Enter Ed25519 private key (Base64): "
        return System.in.newReader().readLine()
    }

    static String readPrivateKeyBase64() {
        def console = System.console()
        if (console == null) {
            throw new IllegalStateException("No console available (run from terminal, not IDE)")
        }
        return new String(console.readPassword("Enter Ed25519 private key (Base64): "))
    }


}