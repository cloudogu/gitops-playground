package com.cloudogu.gitops.utils.license

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature


class LicenseGenerator {

    static void main(String[] args) {


        createKeyPair()

    }

    static void sign(){


}
    static void createKeyPair(){
        def keyGen = KeyPairGenerator.getInstance("Ed25519")
        def keyPair = keyGen.generateKeyPair()
        def publicKey = keyPair.getPublic()
        def privateKey = keyPair.getPrivate()

        println "Public Key generated."
        println "Private Key generated."

        def publicKeyBytes = publicKey.getEncoded()
        def privateKeyBytes = privateKey.getEncoded()

        def base64PublicKey = Base64.getEncoder().encodeToString(publicKeyBytes)
        def base64PrivateKey = Base64.getEncoder().encodeToString(privateKeyBytes)

        println "Base64 Encoded Public Key:\n${base64PublicKey}"
        println "Base64 Encoded Private Key:\n${base64PrivateKey}"



    }

}