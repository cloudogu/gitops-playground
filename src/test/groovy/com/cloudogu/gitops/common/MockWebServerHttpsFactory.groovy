package com.cloudogu.gitops.common

import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

class MockWebServerHttpsFactory {
    static HandshakeCertificates createSocketFactory() {
        def heldCertificate = new HeldCertificate.Builder()
                .addSubjectAlternativeName(InetAddress.getByName('localhost').getCanonicalHostName())
                .build()

        return new HandshakeCertificates.Builder()
                .heldCertificate(heldCertificate)
                .build()
    }
}
